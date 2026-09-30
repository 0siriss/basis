package app.basis.pipeline.search

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.basis.core.common.AppLog
import app.basis.core.datastore.AsrPrefs
import app.basis.ml.llm.LlamaEmbedder
import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelManager
import app.basis.pipeline.TranscriptionStatus
import app.basis.pipeline.heavyWorkAllowed
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class IndexState(val running: Boolean = false, val step: String = "", val last: String? = null)

@Singleton
class IndexStatus @Inject constructor() {
    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state.asStateFlow()
    fun update(f: (IndexState) -> IndexState) { _state.value = f(_state.value) }
}

/** Updates the search index after recognition/summaries: text immediately, vectors under the power rule. */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val indexer: DiaryIndexer,
    private val models: ModelManager,
    private val asrPrefs: AsrPrefs,
    private val lockHolder: TranscriptionStatus,
    private val status: IndexStatus,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!lockHolder.lock.tryLock()) {
            AppLog.d(TAG, "идёт другая тяжёлая задача — индекс позже")
            return@withContext Result.retry()
        }
        try {
            status.update { it.copy(running = true, step = "фрагменты") }
            val sync = indexer.syncText()
            val spec = ModelCatalog.BGE_M3
            if (!models.isReady(spec)) {
                status.update { it.copy(last = "текст обновлён (+${sync.added}); модель ${spec.title} не загружена — только поиск по словам") }
                return@withContext Result.success()
            }
            val pending = indexer.pendingCount(spec.id)
            if (pending == 0) {
                if (manual) status.update { it.copy(last = "индекс актуален") }
                return@withContext Result.success()
            }
            if (!manual && !heavyWorkAllowed(applicationContext, asrPrefs)) return@withContext Result.success()
            runCatching { setForeground(foregroundInfo()) }
                .onFailure { AppLog.w(TAG, "не удалось перейти в foreground (${it.javaClass.simpleName})") }
            status.update { it.copy(step = "загрузка ${spec.title}") }
            val t0 = System.currentTimeMillis()
            val done = LlamaEmbedder.load(applicationContext, models).use { e ->
                indexer.embedPending(e, shouldStop = { isStopped || (!manual && !heavyWorkAllowed(applicationContext, asrPrefs)) }) { d, total ->
                    if (d % 8 == 0 || d == total) status.update { it.copy(step = "векторы $d / $total") }
                }
            }
            status.update { it.copy(last = "векторы: $done фрагм. за ${(System.currentTimeMillis() - t0) / 1000} с") }
            Result.success()
        } catch (t: Throwable) {
            AppLog.e(TAG, "сбой индексации", t)
            status.update { it.copy(last = "Сбой: ${t.message}") }
            Result.retry()
        } finally {
            lockHolder.lock.unlock()
            status.update { it.copy(running = false, step = "") }
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("processing", "Обработка", android.app.NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "processing")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Индекс дневника")
            .setContentText("Подготовка поиска")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(22, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_MANUAL = "manual"
        private const val TAG = "Index"
    }
}
