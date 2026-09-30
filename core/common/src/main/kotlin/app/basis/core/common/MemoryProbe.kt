package app.basis.core.common

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** One-line memory/scheduling snapshot for logs (why a process gets killed, why it's slow). */
object MemoryProbe {
    fun snapshot(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val status = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.removeSuffix(" kB")?.trim()?.toLongOrNull()
        val rss = field("VmRSS")?.let { "${it / 1024} МБ" } ?: "?"
        val swap = field("VmSwap")?.let { "${it / 1024} МБ" } ?: "?"
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("?")
        val importance = am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.importance
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
        val thermal = when (power.currentThermalStatus) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "норма"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "лёгкий нагрев"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "нагрев"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "сильный нагрев"
            else -> "троттлинг (${power.currentThermalStatus})"
        }
        val headroom = runCatching { power.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        return "батарея ${battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" +
            (if (battery.isCharging) " (заряжается)" else "") +
            ", энергосбережение=${power.isPowerSaveMode}, температура: $thermal" +
            (if (!headroom.isNaN()) " (запас %.2f)".format(headroom) else "") + ", " +
            "RAM свободно ${mi.availMem / 1048576}/${mi.totalMem / 1048576} МБ (порог ${mi.threshold / 1048576}, low=${mi.lowMemory}), " +
            "процесс RSS $rss, swap $swap, cpuset $cpuset, importance ${importance ?: "?"}"
    }
}
