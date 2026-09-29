package app.basis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.basis.audio.capture.RecordingController
import app.basis.core.common.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** cmd = start | pause | resume | stop | private:<minutes> | download-models */
@AndroidEntryPoint
class DebugCommandActivity : ComponentActivity() {
    @Inject lateinit var controller: RecordingController
    @Inject lateinit var models: app.basis.ml.models.ModelManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmd = intent.getStringExtra("cmd").orEmpty()
        AppLog.i("Debug", "команда из adb: $cmd")
        lifecycleScope.launch {
            when {
                cmd == "start" -> controller.start()
                cmd == "pause" -> controller.pause()
                cmd == "resume" -> controller.resume()
                cmd == "stop" -> controller.stop()
                cmd == "download-models" -> models.download(app.basis.ml.models.ModelCatalog.SILERO_VAD)
                cmd.startsWith("private:") -> controller.privateFor(cmd.substringAfter(':').toInt())
                else -> AppLog.w("Debug", "неизвестная команда")
            }
            finish()
        }
    }
}
