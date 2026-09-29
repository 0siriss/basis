package app.basis.pipeline

import android.content.Context
import android.os.BatteryManager
import kotlin.math.abs

/**
 * Integrates the battery's instantaneous current (sampled once a second) over a run.
 * The charge counter is too coarse on many phones to see a one-minute job. The value is the whole
 * phone's draw (recording, screen, …), not ASR alone — compare runs under similar conditions.
 */
class CurrentMeter(context: Context) {
    data class Energy(val avgMa: Double, val mah: Double, val samples: Int)

    private val bm = context.getSystemService(BatteryManager::class.java)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var sumMa = 0.0
    private var n = 0
    private var startMs = 0L
    private var endMs = 0L

    fun start() {
        running = true
        startMs = System.currentTimeMillis()
        thread = Thread({
            while (running) {
                // µA on most devices; sign convention differs between vendors (discharge may be + or −).
                val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (ua != Int.MIN_VALUE && ua != 0) {
                    val ma = abs(ua) / 1000.0
                    // Some devices report mA instead of µA.
                    sumMa += if (ma < 1.0) abs(ua).toDouble() else ma
                    n++
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "basis-current").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        endMs = System.currentTimeMillis()
        thread?.interrupt()
        thread = null
    }

    fun result(): Energy? {
        if (n == 0) return null
        val avg = sumMa / n
        val hours = (endMs - startMs) / 3_600_000.0
        return Energy(avg, avg * hours, n)
    }
}
