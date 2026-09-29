package app.basis.audio.capture

/** Summary of one reporting window (default: one minute) of capture. */
data class WindowReport(
    val wallMs: Long,
    val audioMs: Long,
    val screenOffAudioMs: Long,
    val silencedAudioMs: Long,
    val zeroFrames: Int,
    val frames: Int,
    val avgDbfs: Double,
) {
    /** Share of wall-clock time covered by delivered audio; < ~0.98 means gaps (CPU suspended, mic lost). */
    val coverage: Double get() = if (wallMs <= 0) 0.0 else audioMs.toDouble() / wallMs
}

/**
 * Pure accounting of captured audio, independent of Android APIs (unit-tested).
 * Audio time is derived from sample counts, so gaps in delivery show up as coverage < 100%.
 */
class CaptureStats(private val sampleRate: Int, startMs: Long) {
    private var windowStartMs = startMs
    private var samples = 0L
    private var screenOffSamples = 0L
    private var silencedSamples = 0L
    private var zeroFrames = 0
    private var frames = 0
    private var dbSum = 0.0

    var totalAudioMs = 0L
        private set
    var totalScreenOffAudioMs = 0L
        private set

    fun onFrame(count: Int, screenOn: Boolean, silenced: Boolean, allZero: Boolean, dbfs: Double) {
        samples += count
        if (!screenOn) screenOffSamples += count
        if (silenced) silencedSamples += count
        if (allZero) zeroFrames++
        frames++
        dbSum += dbfs
        val ms = count * 1000L / sampleRate
        totalAudioMs += ms
        if (!screenOn) totalScreenOffAudioMs += ms
    }

    fun elapsedInWindow(nowMs: Long): Long = nowMs - windowStartMs

    fun roll(nowMs: Long): WindowReport {
        val report = WindowReport(
            wallMs = nowMs - windowStartMs,
            audioMs = samples * 1000 / sampleRate,
            screenOffAudioMs = screenOffSamples * 1000 / sampleRate,
            silencedAudioMs = silencedSamples * 1000 / sampleRate,
            zeroFrames = zeroFrames,
            frames = frames,
            avgDbfs = if (frames == 0) SILENCE_DBFS else dbSum / frames,
        )
        windowStartMs = nowMs
        samples = 0; screenOffSamples = 0; silencedSamples = 0; zeroFrames = 0; frames = 0; dbSum = 0.0
        return report
    }

    companion object {
        const val SILENCE_DBFS = -96.0

        /** RMS level of 16-bit PCM in dBFS, clamped to [SILENCE_DBFS, 0]. */
        fun dbfs(buf: ShortArray, count: Int): Double {
            if (count <= 0) return SILENCE_DBFS
            var sum = 0.0
            for (i in 0 until count) {
                val v = buf[i].toDouble()
                sum += v * v
            }
            val rms = kotlin.math.sqrt(sum / count) / Short.MAX_VALUE
            return if (rms <= 0) SILENCE_DBFS else (20 * kotlin.math.log10(rms)).coerceIn(SILENCE_DBFS, 0.0)
        }

        fun isAllZero(buf: ShortArray, count: Int): Boolean {
            for (i in 0 until count) if (buf[i].toInt() != 0) return false
            return true
        }
    }
}
