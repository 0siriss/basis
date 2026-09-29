package app.basis.audio.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.basis.core.common.AppLog
import app.basis.core.datastore.RecordingPrefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * After reboot or app update the process is gone. Android 14+ forbids starting a microphone FGS from
 * BOOT_COMPLETED, so we only ask the user to resume with a notification.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var prefs: RecordingPrefs
    @Inject lateinit var controller: RecordingController

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = prefs.current()
                val mode = controller.status.value.mode
                AppLog.i("Boot", "${intent.action}: запись включена=${s.enabled}, режим=$mode")
                // BOOT_COMPLETED can arrive late (or after a fresh install) while the service already runs.
                val serviceAlive = mode != RecorderMode.STOPPED && mode != RecorderMode.BLOCKED
                if (s.enabled && !serviceAlive) {
                    val reason = if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "Приложение обновлено" else "Телефон перезагружен"
                    RecordingNotifications.showResumeRequest(context, reason)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
