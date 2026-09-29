package app.basis.audio.capture

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.basis.core.common.AppLog

/**
 * Detects reboots between process starts (Settings.Global.BOOT_COUNT) and whether BOOT_COMPLETED
 * actually reached us. OEM "autostart" managers (Nubia, Xiaomi, Huawei…) often block it silently.
 */
object BootDiagnostics {
    private const val PREFS = "boot_diag"

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    fun markReceived(context: Context, action: String?) {
        if (action != Intent.ACTION_BOOT_COMPLETED) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("received_for_boot", bootCount(context)).apply()
    }

    /** Called on process start. BOOT_COMPLETED may still be on its way, so the check runs with a delay. */
    fun check(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = bootCount(context)
        val lastSeen = sp.getInt("last_boot", -1)
        sp.edit().putInt("last_boot", current).apply()
        if (lastSeen == -1 || lastSeen == current) return
        val sinceBootS = android.os.SystemClock.elapsedRealtime() / 1000
        AppLog.i("Boot", "с прошлого запуска телефон перезагружался (загрузка №$current, $sinceBootS с назад)")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (sp.getInt("received_for_boot", -1) != current) {
                AppLog.w(
                    "Boot",
                    "BOOT_COMPLETED не пришёл — прошивка блокирует автозапуск. Разрешите «Автозапуск» для Basis " +
                        "в настройках телефона, иначе после перезагрузки не будет напоминания продолжить запись",
                )
            } else {
                AppLog.i("Boot", "BOOT_COMPLETED получен")
            }
        }, 60_000)
    }
}
