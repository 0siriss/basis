package app.basis.audio.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class SpeechStatsTest {
    private val hour = 3_600_000L

    @Test
    fun reportsPreviousHourOnBoundary() {
        val s = SpeechStats(ZoneOffset.UTC)
        val base = 1_000 * hour // exact hour boundary
        assertNull(s.onAudio(60_000, base + 1_000))
        s.onSegment(6_000)
        s.onDroppedShort()
        assertNull(s.onAudio(60_000, base + 30 * 60_000))
        s.onSegment(12_000)
        val r = s.onAudio(1_000, base + hour + 5)!!
        assertEquals(base, r.hourStartMs)
        assertEquals(120_000, r.audioMs)
        assertEquals(18_000, r.speechMs)
        assertEquals(2, r.segments)
        assertEquals(1, r.droppedShort)
        assertEquals(0.15, r.speechShare, 1e-9)
        // New hour starts clean, totals keep going.
        assertEquals(1_000, s.current().audioMs)
        assertEquals(18_000, s.totalSpeechMs)
    }
}
