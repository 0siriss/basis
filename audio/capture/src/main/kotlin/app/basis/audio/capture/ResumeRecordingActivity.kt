package app.basis.audio.capture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.basis.core.common.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Invisible trampoline opened from the "tap to resume" notification. Being a visible activity gives
 * the app while-in-use microphone access, so the microphone FGS may start (Android 14+).
 */
@AndroidEntryPoint
class ResumeRecordingActivity : ComponentActivity() {
    @Inject lateinit var controller: RecordingController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("Resume", "продолжение записи по нажатию на уведомление")
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            controller.start()
            finish()
        }
    }
}
