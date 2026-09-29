package app.basis.pipeline

import app.basis.audio.buffer.SegmentMeta
import app.basis.audio.buffer.SegmentStore
import app.basis.ml.asr.AsrResult
import app.basis.ml.asr.SpeechRecognizer
import java.io.IOException
import java.security.GeneralSecurityException

/** Where recognized text goes (Room in the app, a list in tests). */
fun interface TranscriptSink {
    suspend fun save(meta: SegmentMeta, result: AsrResult, modelId: String)
}

data class RunReport(
    val processed: Int = 0,
    val empty: Int = 0,
    val undecryptable: Int = 0,
    val audioMs: Long = 0,
    val processingMs: Long = 0,
    val remaining: Int = 0,
    val stoppedEarly: Boolean = false,
) {
    /** Real-time factor: processing time / audio duration (lower is faster; 0.1 = 10× faster than real time). */
    val rtf: Double get() = if (audioMs == 0L) 0.0 else processingMs.toDouble() / audioMs
}

/**
 * Drains the encrypted buffer: decrypt (in memory) → recognize → save text → delete the audio file
 * immediately. Pure logic without Android dependencies; unit-tested.
 *
 * - An undecryptable/corrupt segment can never be recovered and is deleted.
 * - If the recognizer throws, the run stops and the segment stays for the next run.
 * - The text is saved before the audio is deleted, so a crash between the two can only duplicate
 *   a transcript, never lose one.
 */
class TranscriptionRunner(
    private val store: SegmentStore,
    private val recognizer: SpeechRecognizer,
    private val sink: TranscriptSink,
    private val shouldStop: () -> Boolean = { false },
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): RunReport {
        var r = RunReport()
        var done = 0
        while (true) {
            val batch = store.list()
            if (batch.isEmpty()) break
            val total = done + batch.size
            for (seg in batch) {
                if (shouldStop()) return r.copy(remaining = store.list().size, stoppedEarly = true)
                val pcm = try {
                    store.read(seg)
                } catch (e: GeneralSecurityException) {
                    store.delete(seg); r = r.copy(undecryptable = r.undecryptable + 1); continue
                } catch (e: IOException) {
                    store.delete(seg); r = r.copy(undecryptable = r.undecryptable + 1); continue
                }
                val t0 = clockMs()
                val result = try {
                    recognizer.transcribe(pcm, seg.meta.sampleRate)
                } finally {
                    pcm.fill(0)
                }
                val took = clockMs() - t0
                if (result.text.isNotBlank()) sink.save(seg.meta, result, recognizer.modelId)
                if (!store.delete(seg) && seg.file.exists()) throw IOException("не удалось удалить аудио ${seg.file.name}")
                r = r.copy(
                    processed = r.processed + 1,
                    empty = r.empty + if (result.text.isBlank()) 1 else 0,
                    audioMs = r.audioMs + seg.meta.durationMs,
                    processingMs = r.processingMs + took,
                )
                done++
                onProgress(done, total)
            }
        }
        return r
    }
}
