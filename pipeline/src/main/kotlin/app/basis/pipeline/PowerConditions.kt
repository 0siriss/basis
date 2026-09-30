package app.basis.pipeline

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.basis.core.datastore.AsrPrefs
import app.basis.core.datastore.ProcessingMode

/** Heavy background work (LLM, embeddings) runs under the same charging/battery rule as ASR. */
suspend fun heavyWorkAllowed(context: Context, prefs: AsrPrefs): Boolean {
    val s = prefs.current()
    val bm = context.getSystemService(BatteryManager::class.java)
    val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val charging = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 || bm.isCharging
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return charging || (s.mode == ProcessingMode.CHARGING_OR_BATTERY_ABOVE && level >= s.batteryThreshold)
}
