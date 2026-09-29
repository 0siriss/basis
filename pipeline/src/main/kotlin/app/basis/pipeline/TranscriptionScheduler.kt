package app.basis.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.basis.core.common.AppLog
import app.basis.core.datastore.AsrPrefs
import app.basis.core.datastore.ProcessingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AsrPrefs,
) {
    private val wm get() = WorkManager.getInstance(context)

    /**
     * Periodic check every 15 min (the WorkManager minimum). CHARGING_ONLY sets the charging constraint,
     * so the system wakes us only on the charger; the battery-threshold mode is checked inside the worker.
     */
    suspend fun scheduleFromSettings() {
        val s = prefs.current()
        val constraints = Constraints.Builder()
            .setRequiresCharging(s.mode == ProcessingMode.CHARGING_ONLY)
            .setRequiresStorageNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<TranscribeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        AppLog.d("ASR", "расписание: каждые 15 мин, ${if (s.mode == ProcessingMode.CHARGING_ONLY) "только на зарядке" else "на зарядке или при батарее ≥ ${s.batteryThreshold}%"}")
    }

    /** Runs now regardless of charging (for testing / measuring RTF). */
    fun runNow() {
        val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setInputData(workDataOf(TranscribeWorker.KEY_MANUAL to true))
            .build()
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    private companion object {
        const val PERIODIC = "transcribe-periodic"
        const val NOW = "transcribe-now"
    }
}
