package app.basis.pipeline.summary

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.basis.core.common.AppLog
import app.basis.core.common.formatDuration
import app.basis.core.database.SummaryDao
import app.basis.core.database.SummaryEntity
import app.basis.core.database.TranscriptDao
import app.basis.core.datastore.AsrPrefs
import app.basis.core.datastore.LlmPrefs
import app.basis.ml.llm.ChatPrompt
import app.basis.ml.llm.GenOptions
import app.basis.ml.llm.LlamaCppEngine
import app.basis.ml.llm.LlmGuard
import app.basis.ml.llm.LlmEngine
import app.basis.ml.llm.LlmModel
import app.basis.ml.models.ModelManager
import app.basis.pipeline.CurrentMeter
import app.basis.pipeline.TranscriptionScheduler
import app.basis.pipeline.TranscriptionStatus
import app.basis.pipeline.heavyWorkAllowed
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds hour and day digests with the local LLM (map-reduce, see [Summarizer]).
 * Same conditions as ASR (charging / battery threshold) unless started manually.
 */
@HiltWorker
class SummarizeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transcripts: TranscriptDao,
    private val summaries: SummaryDao,
    private val models: ModelManager,
    private val llmPrefs: LlmPrefs,
    private val asrPrefs: AsrPrefs,
    private val lockHolder: TranscriptionStatus,
    private val status: SummaryStatus,
    private val scheduler: TranscriptionScheduler,
) : CoroutineWorker(context, params) {

    private class Stats {
        var calls = 0; var promptTokens = 0L; var promptMs = 0L; var genTokens = 0L; var genMs = 0L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!lockHolder.lock.tryLock()) {
            AppLog.d(TAG, "идёт другая тяжёлая задача — сводки позже")
            return@withContext Result.retry()
        }
        try {
            run(manual)
        } finally {
            lockHolder.lock.unlock()
            status.update { it.copy(running = false, step = "") }
            // New/changed digests and transcripts → search index.
            scheduler.enqueueIndex(manual)
        }
    }

    private suspend fun run(manual: Boolean): Result {
        if (!manual && !allowed()) return Result.success()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val llmSettings = llmPrefs.current()
        val model = LlmModel.byId(llmSettings.modelId)

        // Which days changed since their day summary?
        val dayStats = summaries.transcriptDayStats()
        var engine: LlmEngine? = null
        val stats = Stats()
        var hoursDone = 0
        var daysDone = 0
        val meter = CurrentMeter(applicationContext).also { it.start() }
        val t0 = System.currentTimeMillis()
        try {
            for (ds in dayStats) {
                if (isStopped || (!manual && !allowed())) break
                val day = LocalDate.parse(ds.day)
                val existing = summaries.byDay(ds.day)
                val daySummary = existing.firstOrNull { it.kind == SummaryEntity.DAY }
                if (daySummary != null && daySummary.sourceCount == ds.count && daySummary.sourceMaxCreatedMs == ds.maxCreatedMs) continue
                val rows = transcripts.byDay(ds.day)
                val plan = SummaryPlanner.plan(
                    day,
                    rows.map { TranscriptRef(it.startMs, it.createdMs) },
                    existing.map { ExistingSummary(it.periodStartMs, it.sourceCount, it.sourceMaxCreatedMs) },
                    now, manual, zone,
                )
                if (plan.hours.isEmpty() && !plan.day) continue

                if (engine == null) {
                    if (LlmGuard.blocked(applicationContext, model.spec.id) && !(manual && LlmGuard.consumeManualGrant(applicationContext))) {
                        val msg = "модель ${model.spec.title} ${LlmGuard.failures(applicationContext, model.spec.id)} раза завершалась вместе с процессом " +
                            "(не хватает памяти?) — автоматические сводки остановлены. Выберите модель меньше или запустите вручную"
                        AppLog.e(TAG, msg)
                        status.update { it.copy(last = msg) }
                        return Result.success()
                    }
                    if (!models.isReady(model.spec)) {
                        AppLog.w(TAG, "модель ${model.spec.title} не загружена — сводки ждут")
                        status.update { it.copy(last = "Модель ${model.spec.title} не загружена") }
                        return Result.success()
                    }
                    status.update { it.copy(running = true, step = "загрузка ${model.spec.title}") }
                    runCatching { setForeground(foregroundInfo(model.spec.title)) }
                        .onFailure { AppLog.w(TAG, "не удалось перейти в foreground (${it.javaClass.simpleName})") }
                    engine = LlamaCppEngine.load(applicationContext, model, models, llmSettings.threads, llmSettings.contextSize)
                }
                val summarizer = summarizer(engine, stats)

                for (h in plan.hours) {
                    if (isStopped || (!manual && !allowed())) break
                    val lines = rows.filter { SummaryPlanner.hourStart(it.startMs, zone) == h.hourStartMs }
                        .map { Line(HM.format(Instant.ofEpochMilli(it.startMs).atZone(zone)), it.text) }
                    val label = hourLabel(h.hourStartMs, zone)
                    status.update { it.copy(step = "час $label") }
                    val before = stats.calls
                    val ts = System.currentTimeMillis()
                    val digest = try {
                        summarizer.summarizePeriod(label, lines)
                    } catch (e: org.json.JSONException) {
                        // Stored empty so the hour is not retried until new speech arrives; the transcript stays.
                        AppLog.w(TAG, "сводка за $label: модель вернула неразборчивый ответ — час пропущен")
                        Digest()
                    }
                    val took = System.currentTimeMillis() - ts
                    summaries.upsert(
                        SummaryEntity(ds.day, h.hourStartMs, SummaryEntity.HOUR, digest.toJson(), model.spec.id, h.count, h.maxCreatedMs, System.currentTimeMillis(), took),
                    )
                    hoursDone++
                    AppLog.i(TAG, "сводка за $label: ${lines.size} фрагм., вызовов LLM ${stats.calls - before}, ${formatDuration(took / 1000)}")
                }
                if (plan.day && !isStopped) {
                    val hourDigests = summaries.byDay(ds.day).filter { it.kind == SummaryEntity.HOUR }
                        .map { hourLabel(it.periodStartMs, zone) to Digest.parse(it.json) }
                    status.update { it.copy(step = "итог дня ${ds.day}") }
                    val ts = System.currentTimeMillis()
                    val digest = try {
                        summarizer.summarizeDay(dayLabel(day), hourDigests)
                    } catch (e: org.json.JSONException) {
                        // Deterministic failure (same input → same output): retrying only burns battery.
                        AppLog.w(TAG, "итог дня ${ds.day}: модель вернула неразборчивый ответ — итог собран из часовых сводок без LLM")
                        summarizer.fallbackDay(hourDigests)
                    }
                    val took = System.currentTimeMillis() - ts
                    summaries.upsert(
                        SummaryEntity(ds.day, 0, SummaryEntity.DAY, digest.toJson(), model.spec.id, plan.dayCount, plan.dayMaxCreatedMs, System.currentTimeMillis(), took),
                    )
                    daysDone++
                    AppLog.i(TAG, "итог дня ${ds.day}: из ${hourDigests.size} часов, ${formatDuration(took / 1000)}")
                }
                summaries.deleteStaleHours(ds.day, plan.allHours.map { it.hourStartMs })
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AppLog.e(TAG, "сбой при построении сводок", t)
            status.update { it.copy(last = "Сбой: ${t.message}") }
            // A manual run must not come back by itself (it ignores the battery rule); automatic runs get
            // a few retries for transient failures, then wait for the next recognition run.
            return if (!manual && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            engine?.close()
            meter.stop()
        }

        if (stats.calls > 0) {
            LlmGuard.success(applicationContext, model.spec.id)
            val e = meter.result()
            val msg = "сводки: часов $hoursDone, дней $daysDone, вызовов LLM ${stats.calls}, " +
                "промпт ${stats.promptTokens} ток. (%.0f ток/с), ответ ${stats.genTokens} ток. (%.1f ток/с), всего ${formatDuration((System.currentTimeMillis() - t0) / 1000)}".format(
                    if (stats.promptMs > 0) stats.promptTokens * 1000.0 / stats.promptMs else 0.0,
                    if (stats.genMs > 0) stats.genTokens * 1000.0 / stats.genMs else 0.0,
                ) + (e?.let { ", средний ток %.0f мА, ≈%.1f мА·ч".format(it.avgMa, it.mah) } ?: "") + ", модель ${model.spec.title}"
            AppLog.i(TAG, msg)
            status.update { it.copy(last = "часов $hoursDone, дней $daysDone, генерация %.1f ток/с".format(if (stats.genMs > 0) stats.genTokens * 1000.0 / stats.genMs else 0.0)) }
        } else if (manual) {
            AppLog.i(TAG, "сводки актуальны — делать нечего")
            status.update { it.copy(last = "Сводки актуальны") }
        }
        return Result.success()
    }

    private fun summarizer(engine: LlmEngine, stats: Stats): Summarizer {
        val maxOut = 700
        val dayOut = 1400
        val overhead = engine.countTokens(Prompts.SYSTEM) + 400 // instructions + chat markup
        val budget = (engine.contextSize - dayOut - overhead).coerceAtLeast(512)
        val llm = object : TextLlm {
            override fun countTokens(text: String) = engine.countTokens(text)
            override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
                val r = engine.generate(ChatPrompt(system, user), GenOptions(maxTokens = maxTokens, temperature = 0.2f, grammar = grammar)) { !isStopped }
                stats.calls++
                stats.promptTokens += r.promptTokens; stats.promptMs += r.promptMs
                stats.genTokens += r.genTokens; stats.genMs += r.genMs
                AppLog.d(TAG, "LLM: промпт ${r.promptTokens} ток. за ${r.promptMs} мс (%.0f ток/с), ответ ${r.genTokens} ток. за ${r.genMs} мс (%.1f ток/с)".format(r.promptTps, r.genTps) +
                    if (r.stalls > 0) ", торможение — потоки снижены до ${r.threadsAtEnd}" else "")
                return r.text
            }
        }
        return Summarizer(llm, budget, maxOut, dayOut)
    }

    private suspend fun allowed(): Boolean = heavyWorkAllowed(applicationContext, asrPrefs)

    private fun foregroundInfo(title: String): androidx.work.ForegroundInfo {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("processing", "Обработка", android.app.NotificationManager.IMPORTANCE_LOW))
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, "processing")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Сводка дня")
            .setContentText(title)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return androidx.work.ForegroundInfo(21, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun hourLabel(start: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(start).atZone(zone)
        return "${HM.format(t)}–${HM.format(t.plusHours(1))}, ${DAY.format(t)}"
    }

    private fun dayLabel(day: LocalDate) = DAY.format(day)

    companion object {
        const val KEY_MANUAL = "manual"
        private const val TAG = "Summary"
        private const val MAX_ATTEMPTS = 3
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.forLanguageTag("ru"))
    }
}
