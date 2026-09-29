package app.basis

import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.PowerManager
import app.basis.core.common.AppLog
import dagger.hilt.android.HiltAndroidApp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@HiltAndroidApp
class BasisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.crashSync(t.name, e)
            previous?.uncaughtException(t, e)
        }
        logStartup()
        logPreviousExits()
        app.basis.audio.capture.BootDiagnostics.check(this)
    }

    private fun logStartup() {
        val power = getSystemService(PowerManager::class.java)
        val bucket = getSystemService(UsageStatsManager::class.java).appStandbyBucket
        AppLog.i(
            TAG,
            "старт процесса: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "оптимизация батареи отключена=${power.isIgnoringBatteryOptimizations(packageName)}, " +
                "standby bucket=${bucketName(bucket)}, " +
                "фоновые ограничения=${getSystemService(ActivityManager::class.java).isBackgroundRestricted}",
        )
    }

    /** Why the previous process died (killed by OEM, low memory, crash, update…). */
    private fun logPreviousExits() {
        val am = getSystemService(ActivityManager::class.java)
        val sp = getSharedPreferences("diag", MODE_PRIVATE)
        val lastSeen = sp.getLong("last_exit_ts", 0)
        val all = runCatching { am.getHistoricalProcessExitReasons(packageName, 0, 10) }.getOrDefault(emptyList())
        val exits = all.filter { it.timestamp > lastSeen }
        if (exits.isEmpty()) {
            val last = all.firstOrNull()
            AppLog.i(
                "Exit",
                "новых записей о завершении прошлого процесса нет" + (last?.let {
                    " (последняя известная: ${FMT.format(Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()))}, ${exitReasonName(it.reason)})"
                } ?: ""),
            )
        }
        exits.reversed().forEach {
            val at = FMT.format(Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()))
            AppLog.w(
                "Exit",
                "прошлый процесс завершён $at: ${exitReasonName(it.reason)}" +
                    (it.description?.let { d -> " ($d)" } ?: "") +
                    ", importance=${it.importance}, pss=${it.pss / 1024} МБ",
            )
        }
        exits.maxOfOrNull { it.timestamp }?.let { sp.edit().putLong("last_exit_ts", it).apply() }
    }

    private companion object {
        const val TAG = "App"
        val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

        fun exitReasonName(r: Int) = when (r) {
            1 -> "EXIT_SELF"
            2 -> "SIGNALED (убит сигналом — часто оболочка производителя)"
            3 -> "LOW_MEMORY"
            4 -> "CRASH"
            5 -> "CRASH_NATIVE"
            6 -> "ANR"
            7 -> "INITIALIZATION_FAILURE"
            8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE_USAGE"
            10 -> "USER_REQUESTED (принудительная остановка)"
            11 -> "USER_STOPPED"
            12 -> "DEPENDENCY_DIED"
            13 -> "OTHER"
            14 -> "FREEZER"
            15 -> "PACKAGE_STATE_CHANGE"
            16 -> "PACKAGE_UPDATED"
            else -> "UNKNOWN($r)"
        }

        fun bucketName(b: Int) = when (b) {
            5 -> "EXEMPTED"
            10 -> "ACTIVE"
            20 -> "WORKING_SET"
            30 -> "FREQUENT"
            40 -> "RARE"
            45 -> "RESTRICTED"
            50 -> "NEVER"
            else -> b.toString()
        }
    }
}
