package app.basis.pipeline

import app.basis.audio.buffer.SegmentMeta
import app.basis.audio.buffer.SegmentStore
import app.basis.core.crypto.SoftwareAeadCipher
import app.basis.ml.asr.AsrResult
import app.basis.ml.asr.SpeechRecognizer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptionRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeRecognizer(val texts: MutableList<String>, val failOn: Int = -1) : SpeechRecognizer {
        override val modelId = "fake"
        var calls = 0
        override fun transcribe(pcm: ShortArray, sampleRate: Int): AsrResult {
            if (calls == failOn) throw IllegalStateException("boom")
            calls++
            return AsrResult(texts.removeAt(0), "")
        }
        override fun close() = Unit
    }

    private fun store(dir: File = tmp.newFolder("seg")) = SegmentStore(dir, SoftwareAeadCipher(), Long.MAX_VALUE)

    private fun SegmentStore.add(startMs: Long, samples: Int = 1600) =
        write(SegmentMeta(startMs, 16_000, samples), ShortArray(samples) { 1 })

    @Test
    fun transcribesInOrderAndDeletesAudio() = runTest {
        val s = store()
        s.add(2_000); s.add(1_000); s.add(3_000, 3200)
        val saved = mutableListOf<Pair<Long, String>>()
        var clock = 0L
        val rec = FakeRecognizer(mutableListOf("один", "два", "три"))
        val r = TranscriptionRunner(s, rec, { m, res, _ -> saved += m.startMs to res.text }, clockMs = { clock += 50; clock }).run()
        assertEquals(listOf(1_000L to "один", 2_000L to "два", 3_000L to "три"), saved)
        assertEquals(3, r.processed)
        assertEquals(400L, r.audioMs)
        assertEquals(150L, r.processingMs)
        assertEquals(0.375, r.rtf, 1e-9)
        assertTrue("no audio files may remain", s.allFiles().isEmpty())
    }

    @Test
    fun emptyTextIsNotSavedButAudioIsDeleted() = runTest {
        val s = store()
        s.add(1_000)
        val saved = mutableListOf<String>()
        val r = TranscriptionRunner(s, FakeRecognizer(mutableListOf("  ")), { _, res, _ -> saved += res.text }).run()
        assertTrue(saved.isEmpty())
        assertEquals(1, r.empty)
        assertTrue(s.allFiles().isEmpty())
    }

    @Test
    fun recognizerFailureKeepsSegmentForNextRun() = runTest {
        val s = store()
        s.add(1_000); s.add(2_000)
        val saved = mutableListOf<String>()
        try {
            TranscriptionRunner(s, FakeRecognizer(mutableListOf("a", "b"), failOn = 1), { _, res, _ -> saved += res.text }).run()
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(listOf("a"), saved)
        assertEquals(listOf(2_000L), s.list().map { it.meta.startMs })
    }

    @Test
    fun undecryptableSegmentIsDropped() = runTest {
        val dir = tmp.newFolder("seg2")
        store(dir).add(1_000)           // written with another key
        val s = store(dir)
        s.add(2_000)
        val saved = mutableListOf<Long>()
        val r = TranscriptionRunner(s, FakeRecognizer(mutableListOf("x")), { m, _, _ -> saved += m.startMs }).run()
        assertEquals(listOf(2_000L), saved)
        assertEquals(1, r.undecryptable)
        assertTrue(s.allFiles().isEmpty())
    }

    @Test
    fun stopRequestLeavesRemainingSegments() = runTest {
        val s = store()
        repeat(5) { s.add(it * 1000L) }
        var n = 0
        val r = TranscriptionRunner(s, FakeRecognizer(MutableList(5) { "t$it" }), { _, _, _ -> }, shouldStop = { n++ >= 2 }).run()
        assertTrue(r.stoppedEarly)
        assertEquals(2, r.processed)
        assertEquals(3, r.remaining)
        assertFalse(s.allFiles().isEmpty())
    }

    @Test
    fun segmentsAddedDuringRunAreAlsoProcessed() = runTest {
        val s = store()
        s.add(1_000)
        var added = false
        val saved = mutableListOf<Long>()
        TranscriptionRunner(s, FakeRecognizer(mutableListOf("a", "b")), { m, _, _ ->
            saved += m.startMs
            if (!added) { added = true; s.add(5_000) }
        }).run()
        assertEquals(listOf(1_000L, 5_000L), saved)
        assertTrue(s.allFiles().isEmpty())
    }
}
