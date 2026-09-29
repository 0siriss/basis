package app.basis.audio.vad

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class HourReport(
    val hourStartMs: Long,
    val audioMs: Long,
    val speechMs: Long,
    val segments: Int,
    val droppedShort: Int,
) {
    val speechShare: Double get() = if (audioMs == 0L) 0.0 else speechMs.toDouble() / audioMs
}

/** Accumulates processed audio vs. saved speech per clock hour (local time). Pure; unit-tested. */
class SpeechStats(private val zone: ZoneId = ZoneId.systemDefault()) {
    private var hourStart = -1L
    private var audioMs = 0L
    private var speechMs = 0L
    private var segments = 0
    private var dropped = 0

    var totalSpeechMs = 0L
        private set
    var totalSegments = 0
        private set

    /** Returns the report of the previous hour when [nowMs] crosses an hour boundary. */
    fun onAudio(ms: Long, nowMs: Long): HourReport? {
        val h = hourOf(nowMs)
        var report: HourReport? = null
        if (hourStart >= 0 && h != hourStart && audioMs > 0) report = HourReport(hourStart, audioMs, speechMs, segments, dropped)
        if (h != hourStart) {
            hourStart = h; audioMs = 0; speechMs = 0; segments = 0; dropped = 0
        }
        audioMs += ms
        return report
    }

    fun onSegment(durationMs: Long) {
        speechMs += durationMs
        segments++
        totalSpeechMs += durationMs
        totalSegments++
    }

    fun onDroppedShort() {
        dropped++
    }

    fun current(): HourReport = HourReport(hourStart, audioMs, speechMs, segments, dropped)

    private fun hourOf(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
}
