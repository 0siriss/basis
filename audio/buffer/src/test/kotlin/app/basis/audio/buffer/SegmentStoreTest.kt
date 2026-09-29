package app.basis.audio.buffer

import app.basis.core.crypto.SoftwareAeadCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.AEADBadTagException

class SegmentStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cipher = SoftwareAeadCipher()

    private fun store(max: Long = Long.MAX_VALUE) = SegmentStore(tmp.newFolder("segments"), cipher, max)

    private fun pcm(n: Int, seed: Int = 1) = ShortArray(n) { ((it * 31 + seed) % 2000 - 1000).toShort() }

    @Test
    fun writeReadRoundTrip() {
        val s = store()
        val audio = pcm(16_000)
        val r = s.write(SegmentMeta(startMs = 1_000, sampleRate = 16_000, numSamples = audio.size), audio)
        assertEquals(1000L, r.segment.meta.durationMs)
        assertArrayEquals(audio, s.read(s.list().single()))
    }

    @Test
    fun fileDoesNotContainPlainPcm() {
        val s = store()
        val audio = pcm(4_000)
        val seg = s.write(SegmentMeta(0, 16_000, audio.size), audio).segment
        val raw = seg.file.readBytes()
        val plain = SegmentFormat.pcmToBytes(audio)
        // No 64-byte window of the plain PCM appears in the file.
        val window = plain.copyOfRange(1000, 1064)
        assertFalse(raw.toList().windowed(64).any { it.toByteArray().contentEquals(window) })
    }

    @Test
    fun listIsOrderedAndDeleteRemovesFile() {
        val s = store()
        s.write(SegmentMeta(3_000, 16_000, 160), pcm(160))
        s.write(SegmentMeta(1_000, 16_000, 160), pcm(160))
        s.write(SegmentMeta(2_000, 16_000, 160), pcm(160))
        val list = s.list()
        assertEquals(listOf(1_000L, 2_000L, 3_000L), list.map { it.meta.startMs })
        assertTrue(s.delete(list.first()))
        assertEquals(2, s.list().size)
        assertEquals(2, s.allFiles().size)
    }

    @Test
    fun capDropsOldestSegments() {
        // Each 1600-sample segment is ~3.2 KB encrypted; cap at ~7 KB keeps the two newest.
        val s = store(max = 7_000)
        repeat(5) { i -> s.write(SegmentMeta(i * 1000L, 16_000, 1600), pcm(1600, i)) }
        val left = s.list()
        assertEquals(listOf(3_000L, 4_000L), left.map { it.meta.startMs })
    }

    @Test
    fun statsSumDurations() {
        val s = store()
        s.write(SegmentMeta(0, 16_000, 8_000), pcm(8_000))
        s.write(SegmentMeta(10_000, 16_000, 16_000), pcm(16_000))
        val st = s.stats()
        assertEquals(2, st.count)
        assertEquals(1500L, st.audioMs)
    }

    @Test
    fun sweepRemovesTmpAndCorruptFiles() {
        val s = store()
        s.write(SegmentMeta(0, 16_000, 160), pcm(160))
        val dir = s.allFiles().first().parentFile!!
        File(dir, "x.bseg.tmp").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "junk.bseg").writeBytes(byteArrayOf(9, 9))
        val removed = s.sweep()
        assertEquals(setOf("x.bseg.tmp", "junk.bseg"), removed.toSet())
        assertEquals(1, s.list().size)
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedHeaderFailsDecryption() {
        val s = store()
        val seg = s.write(SegmentMeta(0, 16_000, 160), pcm(160)).segment
        val bytes = seg.file.readBytes()
        bytes[10] = (bytes[10] + 1).toByte() // inside startMs
        seg.file.writeBytes(bytes)
        s.read(s.list().single())
    }

    @Test
    fun clearDeletesEverything() {
        val s = store()
        repeat(3) { s.write(SegmentMeta(it * 10L, 16_000, 160), pcm(160)) }
        assertEquals(3, s.clear())
        assertTrue(s.allFiles().isEmpty())
    }
}
