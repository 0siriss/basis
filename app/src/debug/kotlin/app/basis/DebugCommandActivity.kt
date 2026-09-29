package app.basis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.basis.audio.buffer.SegmentFormat
import app.basis.audio.buffer.SegmentMeta
import app.basis.audio.buffer.SegmentStore
import app.basis.audio.capture.RecordingController
import app.basis.core.common.AppLog
import app.basis.core.database.TranscriptDao
import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelManager
import app.basis.pipeline.TranscriptionScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * Debug builds only; lets CI drive the app via `am start -n …/app.basis.DebugCommandActivity --es cmd …`.
 * cmd = start | pause | resume | stop | private:<min> | download-models | download:<model id> |
 *       inject-wav:<path> (16 kHz mono PCM16 WAV → encrypted buffer) | transcribe | dump-transcripts
 */
@AndroidEntryPoint
class DebugCommandActivity : ComponentActivity() {
    @Inject lateinit var controller: RecordingController
    @Inject lateinit var models: ModelManager
    @Inject lateinit var scheduler: TranscriptionScheduler
    @Inject lateinit var store: SegmentStore
    @Inject lateinit var dao: TranscriptDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmd = intent.getStringExtra("cmd").orEmpty()
        AppLog.i("Debug", "команда из adb: $cmd")
        lifecycleScope.launch {
            try {
                when {
                    cmd == "start" -> controller.start()
                    cmd == "pause" -> controller.pause()
                    cmd == "resume" -> controller.resume()
                    cmd == "stop" -> controller.stop()
                    cmd.startsWith("private:") -> controller.privateFor(cmd.substringAfter(':').toInt())
                    cmd == "download-models" -> models.download(ModelCatalog.SILERO_VAD)
                    cmd.startsWith("download:") -> ModelCatalog.byId(cmd.substringAfter(':'))?.let(models::download)
                    cmd.startsWith("inject-wav:") -> withContext(Dispatchers.IO) { injectWav(File(cmd.substringAfter(':'))) }
                    cmd == "transcribe" -> scheduler.runNow()
                    // Debug only: speech text never goes to logs in normal operation.
                    cmd == "dump-transcripts" -> withContext(Dispatchers.IO) {
                        val today = java.time.LocalDate.now().toString()
                        dao.byDay(today).takeLast(5).forEach { AppLog.i("Debug", "расшифровка [${it.model}]: ${it.text}") }
                    }
                    else -> AppLog.w("Debug", "неизвестная команда")
                }
            } catch (t: Throwable) {
                AppLog.e("Debug", "команда $cmd упала", t)
            }
            finish()
        }
    }

    private fun injectWav(file: File) {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Minimal RIFF parser: find the "data" chunk.
        var pos = 12
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "data") { dataStart = pos + 8; dataLen = minOf(len, bytes.size - dataStart); break }
            pos += 8 + len + (len and 1)
        }
        require(dataStart > 0) { "в WAV нет блока data" }
        val pcm = SegmentFormat.bytesToPcm(bytes.copyOfRange(dataStart, dataStart + dataLen))
        store.write(SegmentMeta(System.currentTimeMillis(), 16_000, pcm.size), pcm)
        file.delete() // the plain WAV must not stay on disk
        AppLog.i("Debug", "WAV ${file.name} (${pcm.size / 16} мс) записан в зашифрованный буфер, исходник удалён")
    }
}
