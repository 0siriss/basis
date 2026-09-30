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

    /** Called after a recognition run: builds summaries for hours/days that became complete. */
    suspend fun enqueueSummaries() {
        val s = prefs.current()
        val req = OneTimeWorkRequestBuilder<app.basis.pipeline.summary.SummarizeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(s.mode == ProcessingMode.CHARGING_ONLY)
                    .build(),
            )
            .build()
        wm.enqueueUniqueWork(SUMMARY, ExistingWorkPolicy.KEEP, req)
    }

    /** Builds summaries now, including the current hour and day (for testing / on demand). */
    fun summarizeNow() {
        app.basis.ml.llm.LlmGuard.grantManual(context)
        val req = OneTimeWorkRequestBuilder<app.basis.pipeline.summary.SummarizeWorker>()
            .setInputData(workDataOf(app.basis.pipeline.summary.SummarizeWorker.KEY_MANUAL to true))
            .build()
        wm.enqueueUniqueWork(SUMMARY_NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Updates the search index (after summaries); manual = embed now regardless of charging. */
    fun enqueueIndex(manual: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<app.basis.pipeline.search.IndexWorker>()
            .setInputData(workDataOf(app.basis.pipeline.search.IndexWorker.KEY_MANUAL to manual))
            .build()
        wm.enqueueUniqueWork(if (manual) INDEX_NOW else INDEX, ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Stops LLM/embedding work so the chat can load its models without two LLMs in memory at once.
     * They are re-enqueued after the next recognition run.
     */
    fun cancelHeavyWork() {
        listOf(SUMMARY, SUMMARY_NOW, INDEX, INDEX_NOW).forEach { wm.cancelUniqueWork(it) }
    }

    private companion object {
        const val PERIODIC = "transcribe-periodic"
        const val NOW = "transcribe-now"
        const val SUMMARY = "summarize"
        const val SUMMARY_NOW = "summarize-now"
        const val INDEX = "index"
        const val INDEX_NOW = "index-now"
    }
}
