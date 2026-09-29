package app.basis.audio.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {
    private val cfg = SegmenterConfig() // 512-sample frames = 32 ms
    private val out = mutableListOf<Segment>()
    private val seg = Segmenter(cfg) { out += it }
    private var t = 0L
    private var marker: Short = 0

    /** Feeds [n] frames with probability [p]; each frame is filled with a running marker to track which ones survive. */
    private fun feed(n: Int, p: Float) = repeat(n) {
        marker++
        seg.accept(ShortArray(cfg.frameSamples) { marker }, p, t)
        t += 32
    }

    private fun frames(s: Segment) = s.pcm.size / cfg.frameSamples

    @Test
    fun silenceProducesNothing() {
        feed(500, 0.05f)
        seg.flush()
        assertTrue(out.isEmpty())
    }

    @Test
    fun speechWithPreRollAndTail() {
        feed(50, 0.1f)          // 1.6 s silence
        feed(40, 0.9f)          // 1.28 s speech
        feed(30, 0.1f)          // 0.96 s silence (> 700 ms) ends the segment
        assertEquals(1, out.size)
        val s = out.single()
        // preRoll = ceil(300/32)=10 frames, speech 40, tail = ceil(200/32)=7 frames
        assertEquals(10 + 40 + 7, frames(s))
        // First pre-roll frame is frame #41 (50 silence - 10 + 1), which started at 40*32 ms.
        assertEquals(40 * 32L, s.startMs)
        assertEquals(41.toShort(), s.pcm.first())
        assertFalse(s.splitByMax)
        assertFalse(seg.isSpeaking)
    }

    @Test
    fun shortBlipIsDropped() {
        feed(20, 0.1f)
        feed(10, 0.9f)          // 320 ms < 400 ms
        feed(30, 0.1f)
        assertTrue(out.isEmpty())
        assertEquals(1, seg.droppedShort)
    }

    @Test
    fun shortPauseDoesNotSplit() {
        feed(10, 0.1f)
        feed(30, 0.9f)
        feed(15, 0.1f)          // 480 ms pause < 700 ms
        feed(30, 0.9f)
        feed(30, 0.1f)
        assertEquals(1, out.size)
    }

    @Test
    fun hysteresisBandDoesNotCountAsSilence() {
        feed(30, 0.9f)
        feed(40, 0.4f)          // between neg (0.35) and threshold (0.5)
        assertTrue(out.isEmpty())
        assertTrue(seg.isSpeaking)
        feed(30, 0.1f)
        assertEquals(1, out.size)
    }

    @Test
    fun longMonologueIsSplitAtMax() {
        val maxFrames = cfg.frames(cfg.maxSegmentMs) // 875
        feed(2 * maxFrames + 100, 0.95f)
        seg.flush()
        assertEquals(3, out.size)
        assertTrue(out[0].splitByMax)
        assertEquals(maxFrames, frames(out[0]))
        assertEquals(maxFrames, frames(out[1]))
        // Segments are contiguous: second starts right after the first.
        assertEquals(out[0].startMs + maxFrames * 32L, out[1].startMs)
        assertFalse(out[2].splitByMax)
    }

    @Test
    fun flushEmitsOngoingSpeech() {
        feed(20, 0.9f)
        seg.flush()
        assertEquals(1, out.size)
        assertEquals(20, frames(out.single()))
    }
}
