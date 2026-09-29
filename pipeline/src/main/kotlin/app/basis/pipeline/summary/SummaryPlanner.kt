package app.basis.pipeline.summary

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class TranscriptRef(val startMs: Long, val createdMs: Long)
data class ExistingSummary(val periodStartMs: Long, val sourceCount: Int, val sourceMaxCreatedMs: Long)

data class HourSource(val hourStartMs: Long, val count: Int, val maxCreatedMs: Long)

data class DayPlan(
    val hours: List<HourSource>,
    val allHours: List<HourSource>,
    val day: Boolean,
    val dayCount: Int,
    val dayMaxCreatedMs: Long,
)

/**
 * Decides what to (re)summarize for one day. Pure; unit-tested.
 * - An hour is summarized once it is over (or on a manual run), and again whenever new transcripts
 *   arrive for it (count or newest created_ms changed).
 * - The day summary is built once the day is over (or on a manual run), and rebuilt when stale.
 */
object SummaryPlanner {
    fun plan(
        day: LocalDate,
        transcripts: List<TranscriptRef>,
        existing: List<ExistingSummary>,
        nowMs: Long,
        manual: Boolean,
        zone: ZoneId,
    ): DayPlan {
        val hours = transcripts.groupBy { hourStart(it.startMs, zone) }.map { (h, ts) ->
            HourSource(h, ts.size, ts.maxOf { it.createdMs })
        }.sortedBy { it.hourStartMs }
        val byStart = existing.associateBy { it.periodStartMs }
        fun fresh(start: Long, count: Int, maxCreated: Long) =
            byStart[start]?.let { it.sourceCount == count && it.sourceMaxCreatedMs == maxCreated } == true

        val hourMs = 3_600_000L
        val todo = hours.filter { h -> (manual || h.hourStartMs + hourMs <= nowMs) && !fresh(h.hourStartMs, h.count, h.maxCreatedMs) }
        val pendingAfterRun = hours.any { h -> !(manual || h.hourStartMs + hourMs <= nowMs) }
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val count = transcripts.size
        val maxCreated = transcripts.maxOfOrNull { it.createdMs } ?: 0
        val needDay = count > 0 && (manual || dayEnd <= nowMs) && !pendingAfterRun && !fresh(0, count, maxCreated)
        return DayPlan(todo, hours, needDay, count, maxCreated)
    }

    fun hourStart(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
}
