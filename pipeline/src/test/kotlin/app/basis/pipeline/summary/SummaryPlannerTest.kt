package app.basis.pipeline.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SummaryPlannerTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 9, 29)
    private val d0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val h = 3_600_000L

    private fun t(hour: Int, min: Int, created: Long = d0 + hour * h + min * 60_000L + 1000) =
        TranscriptRef(d0 + hour * h + min * 60_000L, created)

    @Test
    fun onlyFinishedHoursDuringTheDay() {
        val ts = listOf(t(9, 5), t(9, 40), t(10, 10), t(11, 2))
        val p = SummaryPlanner.plan(day, ts, emptyList(), nowMs = d0 + 11 * h + 30 * 60_000, manual = false, zone = zone)
        assertEquals(listOf(d0 + 9 * h, d0 + 10 * h), p.hours.map { it.hourStartMs })
        assertFalse("day not over", p.day)
    }

    @Test
    fun manualIncludesCurrentHourAndDay() {
        val ts = listOf(t(9, 5), t(11, 2))
        val p = SummaryPlanner.plan(day, ts, emptyList(), nowMs = d0 + 11 * h + 5 * 60_000, manual = true, zone = zone)
        assertEquals(2, p.hours.size)
        assertTrue(p.day)
    }

    @Test
    fun freshSummariesAreSkippedAndStaleOnesRedone() {
        val ts = listOf(t(9, 5), t(9, 40), t(10, 10))
        val existing = listOf(
            ExistingSummary(d0 + 9 * h, 2, ts[1].createdMs),  // fresh
            ExistingSummary(d0 + 10 * h, 1, 0),              // stale: created_ms changed
        )
        val p = SummaryPlanner.plan(day, ts, existing, nowMs = d0 + 2 * 24 * h, manual = false, zone = zone)
        assertEquals(listOf(d0 + 10 * h), p.hours.map { it.hourStartMs })
        assertTrue(p.day)
    }

    @Test
    fun upToDateDayIsSkipped() {
        val ts = listOf(t(9, 5))
        val existing = listOf(ExistingSummary(d0 + 9 * h, 1, ts[0].createdMs), ExistingSummary(0, 1, ts[0].createdMs))
        val p = SummaryPlanner.plan(day, ts, existing, nowMs = d0 + 2 * 24 * h, manual = false, zone = zone)
        assertTrue(p.hours.isEmpty())
        assertFalse(p.day)
    }

    @Test
    fun lateTranscriptForPastDayReopensHourAndDay() {
        val ts = listOf(t(9, 5), t(9, 50, created = d0 + 30 * h)) // recognized the next day
        val existing = listOf(ExistingSummary(d0 + 9 * h, 1, ts[0].createdMs), ExistingSummary(0, 1, ts[0].createdMs))
        val p = SummaryPlanner.plan(day, ts, existing, nowMs = d0 + 31 * h, manual = false, zone = zone)
        assertEquals(1, p.hours.size)
        assertTrue(p.day)
    }
}
