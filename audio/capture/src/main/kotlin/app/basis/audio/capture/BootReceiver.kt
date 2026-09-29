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

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = prefs.current()
                AppLog.i("Boot", "${intent.action}: запись включена=${s.enabled}")
                if (s.enabled) {
                    val reason = if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "Приложение обновлено" else "Телефон перезагружен"
                    RecordingNotifications.showResumeRequest(context, reason)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
