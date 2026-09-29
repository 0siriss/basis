package app.basis.audio.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatsTest {
    @Test
    fun fullCoverageWhenAudioMatchesWallClock() {
        val stats = CaptureStats(16_000, startMs = 0)
        repeat(1000) { stats.onFrame(512, screenOn = true, silenced = false, allZero = false, dbfs = -40.0) }
        // 1000 * 512 samples = 32 s of audio
        val r = stats.roll(nowMs = 32_000)
        assertEquals(32_000, r.audioMs)
        assertEquals(1.0, r.coverage, 1e-9)
        assertEquals(-40.0, r.avgDbfs, 1e-9)
    }

    @Test
    fun gapsReduceCoverageAndScreenOffIsCounted() {
        val stats = CaptureStats(16_000, startMs = 0)
        repeat(500) { stats.onFrame(512, screenOn = false, silenced = false, allZero = false, dbfs = -50.0) }
        val r = stats.roll(nowMs = 32_000)
        assertEquals(0.5, r.coverage, 1e-9)
        assertEquals(16_000, r.screenOffAudioMs)
        assertEquals(16_000, stats.totalScreenOffAudioMs)
    }

    @Test
    fun rollResetsWindowButKeepsTotals() {
        val stats = CaptureStats(16_000, startMs = 0)
        repeat(10) { stats.onFrame(1600, screenOn = true, silenced = true, allZero = true, dbfs = -96.0) }
        val first = stats.roll(1000)
        assertEquals(10, first.zeroFrames)
        assertEquals(1000, first.silencedAudioMs)
        val second = stats.roll(2000)
        assertEquals(0, second.frames)
        assertEquals(1000, stats.totalAudioMs)
    }

    @Test
    fun dbfsOfFullScaleAndSilence() {
        val full = ShortArray(100) { if (it % 2 == 0) Short.MAX_VALUE else (-Short.MAX_VALUE).toShort() }
        assertEquals(0.0, CaptureStats.dbfs(full, 100), 1e-6)
        assertEquals(CaptureStats.SILENCE_DBFS, CaptureStats.dbfs(ShortArray(100), 100), 1e-9)
        assertTrue(CaptureStats.isAllZero(ShortArray(10), 10))
        assertFalse(CaptureStats.isAllZero(shortArrayOf(0, 1), 2))
    }
}
