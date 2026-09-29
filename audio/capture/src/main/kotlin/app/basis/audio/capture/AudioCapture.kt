package app.basis.audio.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.Process
import app.basis.core.common.AppLog

/** Consumer of 16 kHz mono PCM frames. Called on the capture thread; must be fast. */
fun interface AudioFrameSink {
    fun onFrame(samples: ShortArray, count: Int, captureTimeMs: Long)
}

/** Live values for the UI, published from the capture thread. */
data class CaptureLive(
    val levelDbfs: Double,
    val silencedByOs: Boolean,
    val totalAudioMs: Long,
    val screenOffAudioMs: Long,
    val lastWindow: WindowReport?,
)

/**
 * Reads AudioRecord (16 kHz, mono, PCM16) on a dedicated high-priority thread and feeds [sink].
 * Audio is never written to disk here.
 */
class AudioCapture(
    private val context: Context,
    private val sink: AudioFrameSink,
    private val onLive: (CaptureLive) -> Unit,
    /** Called when capture ends without [stop] (device error, dead AudioRecord). */
    private val onUnexpectedEnd: () -> Unit,
    private val reportWindowMs: Long = 60_000,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.e(TAG, "нет разрешения RECORD_AUDIO")
            return false
        }
        val record = createRecord() ?: return false
        running = true
        thread = Thread({ loop(record) }, "basis-capture").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun createRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            AppLog.e(TAG, "getMinBufferSize вернул $minBuf")
            return null
        }
        return try {
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                // ~1 s of headroom so short scheduling hiccups don't drop audio.
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.e(TAG, "AudioRecord не инициализирован (state=${rec.state})")
                rec.release()
                null
            } else {
                AppLog.i(TAG, "AudioRecord готов: ${SAMPLE_RATE} Гц mono PCM16, minBuf=$minBuf B, источник VOICE_RECOGNITION")
                rec
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "не удалось создать AudioRecord", t)
            null
        }
    }

    private fun loop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val power = context.getSystemService(PowerManager::class.java)
        val buf = ShortArray(FRAME_SAMPLES)
        val stats = CaptureStats(SAMPLE_RATE, System.currentTimeMillis())
        var screenOn = power.isInteractive
        var silenced = false
        var lastPollMs = 0L
        var lastLiveMs = 0L
        var lastReport: WindowReport? = null
        try {
            record.startRecording()
            AppLog.i(TAG, "запись началась (recordingState=${record.recordingState})")
            while (running) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    AppLog.e(TAG, "AudioRecord.read ошибка $n — перезапуск чтения")
                    if (n == AudioRecord.ERROR_DEAD_OBJECT) break
                    Thread.sleep(100)
                    continue
                }
                if (n == 0) continue
                val now = System.currentTimeMillis()
                if (now - lastPollMs >= 1000) {
                    lastPollMs = now
                    val on = power.isInteractive
                    if (on != screenOn) {
                        AppLog.i(TAG, if (on) "экран включён" else "экран выключен — запись продолжается")
                        screenOn = on
                    }
                    val s = record.activeRecordingConfiguration?.isClientSilenced ?: false
                    if (s != silenced) {
                        if (s) AppLog.w(TAG, "система заглушила микрофон (isClientSilenced=true) — другое приложение/звонок или нет while-in-use доступа")
                        else AppLog.i(TAG, "микрофон снова доступен")
                        silenced = s
                    }
                }
                val db = CaptureStats.dbfs(buf, n)
                stats.onFrame(n, screenOn, silenced, CaptureStats.isAllZero(buf, n), db)
                sink.onFrame(buf, n, now)

                if (stats.elapsedInWindow(now) >= reportWindowMs) {
                    val r = stats.roll(now)
                    lastReport = r
                    val msg = "за %.0f с: аудио %.1f с (%.1f%%), при выкл. экране %.1f с, заглушено %.1f с, нулевых кадров %d, ср. уровень %.0f dBFS".format(
                        r.wallMs / 1000.0, r.audioMs / 1000.0, r.coverage * 100, r.screenOffAudioMs / 1000.0,
                        r.silencedAudioMs / 1000.0, r.zeroFrames, r.avgDbfs,
                    )
                    if (r.coverage < 0.95 || r.silencedAudioMs > 0) AppLog.w(TAG, msg) else AppLog.i(TAG, msg)
                }
                if (now - lastLiveMs >= 200) {
                    lastLiveMs = now
                    onLive(CaptureLive(db, silenced, stats.totalAudioMs, stats.totalScreenOffAudioMs, lastReport))
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "сбой в потоке записи", t)
        } finally {
            runCatching { record.stop() }
            record.release()
            val unexpected = running
            running = false
            if (unexpected) onUnexpectedEnd()
            AppLog.i(TAG, "запись остановлена, всего аудио ${stats.totalAudioMs / 1000} с")
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 32 ms — the Silero VAD window at 16 kHz. */
        const val FRAME_SAMPLES = 512
        private const val TAG = "Capture"
    }
}
