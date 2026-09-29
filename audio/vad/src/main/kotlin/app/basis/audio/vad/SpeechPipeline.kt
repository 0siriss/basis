package app.basis.audio.vad

import app.basis.audio.buffer.BufferStats
import app.basis.audio.buffer.SegmentMeta
import app.basis.audio.buffer.SegmentStore
import app.basis.core.common.AppLog
import app.basis.core.common.formatDuration
import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SpeechLive(
    val running: Boolean = false,
    val vadReady: Boolean = false,
    val speaking: Boolean = false,
    val probability: Float = 0f,
    val sessionSpeechMs: Long = 0,
    val sessionSegments: Int = 0,
    val droppedFrames: Long = 0,
    val hour: HourReport? = null,
    val buffer: BufferStats = BufferStats(),
    val lastVadMicros: Long = 0,
)

/**
 * capture thread → [onFrame] (copy into a bounded queue) → VAD thread: Silero probability →
 * [Segmenter] → encrypted [SegmentStore]. Only speech segments ever reach storage, and only encrypted.
 */
@Singleton
class SpeechPipeline @Inject constructor(
    private val models: ModelManager,
    private val store: SegmentStore,
) {
    private class Item(val pcm: ShortArray, val timeMs: Long)

    private val cfg = SegmenterConfig()
    private val queue = ArrayBlockingQueue<Item>(QUEUE_FRAMES)
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var dropped = 0L

    private val _live = MutableStateFlow(SpeechLive())
    val live: StateFlow<SpeechLive> = _live.asStateFlow()

    init {
        val removed = store.sweep()
        if (removed.isNotEmpty()) AppLog.w(TAG, "удалены незавершённые/повреждённые сегменты: ${removed.size}")
        _live.value = SpeechLive(buffer = store.stats())
    }

    fun start() {
        if (running) return
        running = true
        dropped = 0
        queue.clear()
        thread = Thread(::loop, "basis-vad").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
    }

    /** Called on the capture thread; never blocks. */
    fun onFrame(samples: ShortArray, count: Int, timeMs: Long) {
        if (!running) return
        // Frame start ≈ time the read completed minus its duration.
        val item = Item(samples.copyOf(count), timeMs - count * 1000L / cfg.sampleRate)
        if (!queue.offer(item)) dropped++
    }

    fun refreshBufferStats() {
        _live.value = _live.value.copy(buffer = store.stats())
    }

    private fun loop() {
        var engine: VadEngine? = null
        var nextModelCheck = 0L
        val stats = SpeechStats()
        val segmenter = Segmenter(cfg) { seg -> save(seg, stats) }
        var lastDropped = 0
        val window = FloatArray(cfg.frameSamples)
        var lastLive = 0L
        var vadMicros = 0L
        _live.value = _live.value.copy(running = true, sessionSpeechMs = 0, sessionSegments = 0)
        try {
            while (running || queue.isNotEmpty()) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val now = System.currentTimeMillis()
                if (engine == null && now >= nextModelCheck) {
                    engine = openEngine()
                    nextModelCheck = now + 10_000
                }
                stats.onAudio(item.pcm.size * 1000L / cfg.sampleRate, item.timeMs)?.let(::logHour)
                if (engine == null || item.pcm.size != cfg.frameSamples) continue

                for (i in item.pcm.indices) window[i] = item.pcm[i] / 32768f
                val t0 = System.nanoTime()
                val p = engine.probability(window)
                vadMicros = (vadMicros * 15 + (System.nanoTime() - t0) / 1000) / 16
                segmenter.accept(item.pcm, p, item.timeMs)
                if (segmenter.droppedShort != lastDropped) {
                    lastDropped = segmenter.droppedShort
                    stats.onDroppedShort()
                }
                if (now - lastLive >= 200) {
                    lastLive = now
                    _live.value = _live.value.copy(
                        vadReady = true,
                        speaking = segmenter.isSpeaking,
                        probability = p,
                        sessionSpeechMs = stats.totalSpeechMs,
                        sessionSegments = stats.totalSegments,
                        droppedFrames = dropped,
                        hour = stats.current(),
                        lastVadMicros = vadMicros,
                    )
                }
            }
            segmenter.flush()
        } catch (t: Throwable) {
            AppLog.e(TAG, "сбой в потоке VAD", t)
        } finally {
            engine?.close()
            val cur = stats.current()
            AppLog.i(
                TAG,
                "VAD остановлен: за сессию речь ${formatDuration(stats.totalSpeechMs / 1000)}, сегментов ${stats.totalSegments}, " +
                    "в текущем часе речь ${formatDuration(cur.speechMs / 1000)} из ${formatDuration(cur.audioMs / 1000)}" +
                    if (dropped > 0) ", потеряно кадров (очередь переполнена): $dropped" else "",
            )
            _live.value = _live.value.copy(running = false, speaking = false, vadReady = false, buffer = store.stats())
        }
    }

    private fun openEngine(): VadEngine? {
        val spec = ModelCatalog.SILERO_VAD
        if (!models.isReady(spec)) {
            AppLog.w(TAG, "модель VAD не загружена — речь не сохраняется (главный экран → «Скачать»)")
            _live.value = _live.value.copy(vadReady = false)
            return null
        }
        return try {
            val t0 = System.currentTimeMillis()
            SileroVadEngine(models.file(spec, spec.files.single().name)).also {
                AppLog.i(TAG, "Silero VAD загружен за ${System.currentTimeMillis() - t0} мс")
                _live.value = _live.value.copy(vadReady = true)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "не удалось загрузить Silero VAD", t)
            null
        }
    }

    private fun save(seg: Segment, stats: SpeechStats) {
        try {
            val meta = SegmentMeta(seg.startMs, cfg.sampleRate, seg.pcm.size)
            val r = store.write(meta, seg.pcm)
            seg.pcm.fill(0)
            stats.onSegment(meta.durationMs)
            AppLog.d(
                TAG,
                "сегмент ${HMS.format(Instant.ofEpochMilli(seg.startMs).atZone(ZoneId.systemDefault()))} " +
                    "%.1f с (речь %.1f с)%s → %d КБ, шифрование %d мс".format(
                        meta.durationMs / 1000.0, seg.speechMs / 1000.0, if (seg.splitByMax) ", разрезан по 28 с" else "",
                        r.segment.sizeBytes / 1024, r.encryptMs,
                    ),
            )
            if (r.droppedForCap > 0) AppLog.w(TAG, "буфер переполнен: удалено старых сегментов ${r.droppedForCap}")
            _live.value = _live.value.copy(buffer = store.stats())
        } catch (t: Throwable) {
            AppLog.e(TAG, "не удалось сохранить сегмент", t)
        }
    }

    private fun logHour(r: HourReport) {
        val buf = store.stats()
        AppLog.i(
            TAG,
            "за час ${HM.format(Instant.ofEpochMilli(r.hourStartMs).atZone(ZoneId.systemDefault()))}: " +
                "речь ${formatDuration(r.speechMs / 1000)} из ${formatDuration(r.audioMs / 1000)} записанного " +
                "(%.1f%%), сегментов ${r.segments}, отброшено коротких ${r.droppedShort}; ".format(r.speechShare * 100) +
                "в буфере ${buf.count} сегм., ${formatDuration(buf.audioMs / 1000)}, ${buf.bytes / (1024 * 1024)} МБ",
        )
    }

    private companion object {
        const val TAG = "VAD"
        /** ~6.5 s of 32 ms frames: absorbs model load and GC pauses without blocking capture. */
        const val QUEUE_FRAMES = 200
        val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
