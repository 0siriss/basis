package app.basis.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.basis.audio.buffer.SegmentStore
import app.basis.core.common.AppLog
import app.basis.core.common.formatDuration
import app.basis.core.database.TranscriptDao
import app.basis.core.database.TranscriptEntity
import app.basis.core.datastore.AsrPrefs
import app.basis.core.datastore.AsrSettings
import app.basis.core.datastore.ProcessingMode
import app.basis.ml.asr.AsrModel
import app.basis.ml.asr.AsrOptions
import app.basis.ml.asr.SpeechRecognizers
import app.basis.ml.models.ModelManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Background ASR over the encrypted buffer. Runs when charging (or, if enabled, when the battery
 * is above the threshold) or on demand (`manual`). Logs speed (RTF) and battery/CPU cost.
 */
@HiltWorker
class TranscribeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: SegmentStore,
    private val models: ModelManager,
    private val prefs: AsrPrefs,
    private val dao: TranscriptDao,
    private val status: TranscriptionStatus,
    private val scheduler: TranscriptionScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val settings = prefs.current()
        if (!status.lock.tryLock()) {
            AppLog.d(TAG, "распознавание уже идёт — пропуск")
            return@withContext Result.success()
        }
        try {
            run(manual, settings)
        } finally {
            status.lock.unlock()
        }
    }

    private suspend fun run(manual: Boolean, settings: AsrSettings): Result {
        val battery = battery()
        if (!manual && !allowed(settings, battery)) {
            AppLog.d(TAG, "условия не выполнены (зарядка=${battery.charging}, ${battery.level}%) — позже")
            return Result.success()
        }
        val pending = store.list()
        if (pending.isEmpty()) {
            if (manual) AppLog.i(TAG, "буфер пуст — распознавать нечего")
            scheduler.enqueueSummaries()
            return Result.success()
        }
        val model = AsrModel.byId(settings.modelId)
        if (!models.isReady(model.spec)) {
            AppLog.w(TAG, "модель ${model.spec.title} не загружена — ${pending.size} сегм. ждут")
            return Result.success()
        }

        runCatching { setForeground(foregroundInfo(model.spec.title)) }
            .onFailure { AppLog.w(TAG, "не удалось перейти в foreground (${it.javaClass.simpleName}) — работаю в фоне") }

        val audioPending = pending.sumOf { it.meta.durationMs }
        AppLog.i(
            TAG,
            "старт${if (manual) " (вручную)" else ""}: ${pending.size} сегм., ${formatDuration(audioPending / 1000)} аудио, " +
                "модель ${model.spec.title}, потоков ${settings.threads}, зарядка=${battery.charging}, ${battery.level}%",
        )
        status.update { it.copy(running = true, done = 0, total = pending.size, modelTitle = model.spec.title) }

        val t0 = System.currentTimeMillis()
        val cpu0 = Process.getElapsedCpuTime()
        val meter = CurrentMeter(applicationContext).also { it.start() }
        val recognizer = try {
            SpeechRecognizers.create(model, models, AsrOptions(threads = settings.threads, whisperLanguage = settings.whisperLanguage))
        } catch (t: Throwable) {
            AppLog.e(TAG, "не удалось загрузить ${model.spec.title}", t)
            meter.stop()
            status.update { it.copy(running = false, last = "Ошибка загрузки модели: ${t.message}") }
            return Result.success()
        }
        val loadMs = System.currentTimeMillis() - t0
        AppLog.i(TAG, "модель загружена за $loadMs мс")

        val zone = ZoneId.systemDefault()
        val report = try {
            TranscriptionRunner(
                store = store,
                recognizer = recognizer,
                sink = { meta, res, modelId ->
                    dao.insert(
                        TranscriptEntity(
                            startMs = meta.startMs,
                            endMs = meta.endMs,
                            day = Instant.ofEpochMilli(meta.startMs).atZone(zone).toLocalDate().toString(),
                            text = res.text,
                            model = modelId,
                            lang = res.lang,
                        ),
                    )
                },
                shouldStop = { isStopped || (!manual && !allowed(settings, battery())) },
                onProgress = { done, total -> status.update { it.copy(done = done, total = total) } },
            ).run()
        } catch (t: Throwable) {
            AppLog.e(TAG, "сбой распознавания — сегмент оставлен до следующего запуска", t)
            status.update { it.copy(running = false, last = "Сбой: ${t.message}") }
            return Result.retry()
        } finally {
            recognizer.close()
            meter.stop()
        }

        val wallMs = System.currentTimeMillis() - t0
        val cpuMs = Process.getElapsedCpuTime() - cpu0
        val after = battery()
        val energy = meter.result()
        val audit = AudioAudit.scan(applicationContext)
        val summary = "распознано ${report.processed} сегм. (пустых ${report.empty}${if (report.undecryptable > 0) ", нерасшифруемых ${report.undecryptable}" else ""}), " +
            "аудио ${formatDuration(report.audioMs / 1000)} за ${formatDuration(report.processingMs / 1000)}: " +
            "RTF %.3f (%.1f× быстрее реального времени), всего с загрузкой ${formatDuration(wallMs / 1000)}, CPU ${formatDuration(cpuMs / 1000)}".format(
                report.rtf, if (report.rtf > 0) 1 / report.rtf else 0.0,
            ) +
            (if (!battery.charging && !after.charging) ", батарея ${battery.level}%→${after.level}%" +
                (energy?.let { ", средний ток телефона %.0f мА, ≈%.1f мА·ч".format(it.avgMa, it.mah) } ?: "") else ", на зарядке") +
            (if (report.stoppedEarly) ", остановлено (условия/система), осталось ${report.remaining}" else "")
        AppLog.i(TAG, summary)
        if (audit.otherAudio.isEmpty()) {
            AppLog.i(TAG, "проверка диска: посторонних аудиофайлов нет ✓, в буфере ждут ${audit.pendingSegments} сегм.")
        } else {
            AppLog.e(TAG, "проверка диска: найдены аудиофайлы: ${audit.otherAudio.joinToString { it.name }}")
        }
        status.update {
            it.copy(
                running = false,
                last = "${report.processed} сегм., ${formatDuration(report.audioMs / 1000)} аудио, RTF %.3f, %s".format(report.rtf, model.spec.title),
            )
        }
        if (report.stoppedEarly && !isStopped) scheduler.scheduleFromSettings()
        scheduler.enqueueSummaries()
        return Result.success()
    }

    private data class Battery(val charging: Boolean, val level: Int, val chargeCounterUah: Long)

    private fun battery(): Battery {
        val bm = applicationContext.getSystemService(BatteryManager::class.java)
        val sticky = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return Battery(
            charging = plugged || bm.isCharging,
            level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            chargeCounterUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
        )
    }

    private fun allowed(s: AsrSettings, b: Battery): Boolean =
        b.charging || (s.mode == ProcessingMode.CHARGING_OR_BATTERY_ABOVE && b.level >= s.batteryThreshold)

    private fun foregroundInfo(title: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обработка", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Распознавание речи")
            .setContentText(title)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_MANUAL = "manual"
        private const val TAG = "ASR"
        private const val CHANNEL = "processing"
        private const val NOTIFICATION_ID = 20
    }
}
