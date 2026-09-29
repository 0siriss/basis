package app.basis.audio.vad

data class SegmenterConfig(
    val sampleRate: Int = 16_000,
    val frameSamples: Int = 512,
    /** Speech starts when probability ≥ this. */
    val threshold: Float = 0.5f,
    /** Silence is counted only below this (hysteresis between the two). */
    val negThreshold: Float = 0.35f,
    /** Segments with less speech than this are dropped (clicks, coughs). */
    val minSpeechMs: Int = 250,
    /** A pause this long ends the segment. */
    val minSilenceMs: Int = 700,
    /** Audio kept before the detected start, so first syllables aren't cut. */
    val preRollMs: Int = 300,
    /** Trailing silence kept at the end of a segment. */
    val tailMs: Int = 200,
    /** Long monologues are split so ASR (Whisper: 30 s window) gets bounded chunks. */
    val maxSegmentMs: Int = 28_000,
) {
    val frameMs: Double get() = frameSamples * 1000.0 / sampleRate
    fun frames(ms: Int): Int = kotlin.math.ceil(ms / frameMs).toInt()
}

/** A finished speech segment: 16 kHz mono PCM and the wall-clock time of its first sample. */
class Segment(val startMs: Long, val pcm: ShortArray, val speechMs: Long, val splitByMax: Boolean)

/**
 * Turns per-frame speech probabilities into speech segments. Pure logic (no Android, no model):
 * unit-tested with synthetic probabilities. Not thread-safe; driven by the single VAD thread.
 */
class Segmenter(private val cfg: SegmenterConfig, private val onSegment: (Segment) -> Unit) {
    private class Frame(val pcm: ShortArray, val startMs: Long)

    private val preRollMax = cfg.frames(cfg.preRollMs)
    private val preRoll = ArrayDeque<Frame>(preRollMax + 1)
    private val current = ArrayList<Frame>()
    private var inSpeech = false
    private var silenceRun = 0
    private var speechFrames = 0
    private val silenceFrames = cfg.frames(cfg.minSilenceMs)
    private val tailFrames = cfg.frames(cfg.tailMs)
    private val maxFrames = cfg.frames(cfg.maxSegmentMs)
    private val minSpeechFrames = cfg.frames(cfg.minSpeechMs)

    var droppedShort = 0
        private set

    val isSpeaking: Boolean get() = inSpeech

    /** [frame] must be owned by the segmenter (the caller must not reuse the array). */
    fun accept(frame: ShortArray, prob: Float, frameStartMs: Long) {
        val f = Frame(frame, frameStartMs)
        if (!inSpeech) {
            if (prob >= cfg.threshold) {
                inSpeech = true
                current.addAll(preRoll)
                preRoll.clear()
                current.add(f)
                speechFrames = 1
                silenceRun = 0
            } else {
                preRoll.addLast(f)
                if (preRoll.size > preRollMax) preRoll.removeFirst()
            }
            return
        }

        current.add(f)
        when {
            prob >= cfg.threshold -> { speechFrames++; silenceRun = 0 }
            prob < cfg.negThreshold -> silenceRun++
            else -> Unit // between thresholds: neither speech nor silence
        }

        if (silenceRun >= silenceFrames) {
            // Keep only a short tail of the trailing silence.
            val drop = (silenceRun - tailFrames).coerceAtLeast(0)
            repeat(drop) { current.removeAt(current.lastIndex) }
            finish(splitByMax = false)
            inSpeech = false
        } else if (current.size >= maxFrames) {
            finish(splitByMax = true)
            // Continue the monologue as a new segment without pre-roll.
            speechFrames = 0
            silenceRun = 0
        }
    }

    /** Ends the current segment (recording paused/stopped). */
    fun flush() {
        if (inSpeech) {
            val drop = (silenceRun - tailFrames).coerceAtLeast(0).coerceAtMost(current.size)
            repeat(drop) { current.removeAt(current.lastIndex) }
            finish(splitByMax = false)
        }
        inSpeech = false
        preRoll.clear()
    }

    private fun finish(splitByMax: Boolean) {
        val frames = current.toList()
        current.clear()
        silenceRun = 0
        if (frames.isEmpty()) return
        if (speechFrames < minSpeechFrames && !splitByMax) {
            droppedShort++
            return
        }
        val total = frames.sumOf { it.pcm.size }
        val pcm = ShortArray(total)
        var pos = 0
        for (fr in frames) {
            fr.pcm.copyInto(pcm, pos)
            pos += fr.pcm.size
        }
        onSegment(Segment(frames.first().startMs, pcm, (speechFrames * cfg.frameMs).toLong(), splitByMax))
    }
}
