package app.basis.pipeline.search

import android.content.Context
import app.basis.core.common.AppLog
import app.basis.core.common.MemoryProbe
import app.basis.core.datastore.LlmPrefs
import app.basis.ml.llm.ChatPrompt
import app.basis.ml.llm.Embedder
import app.basis.ml.llm.GenOptions
import app.basis.ml.llm.LlamaCppEngine
import app.basis.ml.llm.LlamaEmbedder
import app.basis.ml.llm.LlmEngine
import app.basis.ml.llm.LlmModel
import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelManager
import app.basis.pipeline.TranscriptionScheduler
import app.basis.pipeline.TranscriptionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ChatEvent {
    data class Status(val text: String) : ChatEvent
    data class Sources(val sources: List<ChatSource>) : ChatEvent
    data class Token(val text: String) : ChatEvent
    data class Done(val stats: String) : ChatEvent
    data class Error(val message: String) : ChatEvent
}

/**
 * Questions about the diary: hybrid search → prompt with numbered fragments → streamed answer from the
 * local LLM. While the chat is open it owns the heavy-work lock, so a summary/index worker never loads a
 * second model next to it; models are freed after [IDLE_MS] without questions or when the screen closes.
 */
@Singleton
class DiaryChat @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
    private val llmPrefs: LlmPrefs,
    private val search: DiarySearch,
    private val indexer: DiaryIndexer,
    private val lockHolder: TranscriptionStatus,
    private val scheduler: TranscriptionScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = Mutex()
    private var holdsLock = false
    private var engine: LlmEngine? = null
    private var embedder: Embedder? = null
    private var idleJob: Job? = null

    fun ask(question: String): Flow<ChatEvent> = channelFlow {
        idleJob?.cancel()
        try {
            session.withLock {
                try {
                    answer(question)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    AppLog.e(TAG, "чат: сбой", t)
                    send(ChatEvent.Error(t.message ?: t.javaClass.simpleName))
                }
            }
        } finally {
            scheduleRelease()
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun ProducerScope<ChatEvent>.answer(question: String) {
        val settings = llmPrefs.current()
        val model = LlmModel.byId(settings.modelId)
        if (!models.isReady(model.spec)) {
            send(ChatEvent.Error("Модель ${model.spec.title} не загружена (карточка «Сводки» на главном экране)"))
            return
        }
        if (!holdsLock) {
            if (!lockHolder.lock.tryLock()) {
                send(ChatEvent.Status("Останавливаю фоновую обработку…"))
                scheduler.cancelHeavyWork()
                lockHolder.lock.lock()
            }
            holdsLock = true
        }
        send(ChatEvent.Status("Обновляю индекс…"))
        indexer.syncText()

        val bge = ModelCatalog.BGE_M3
        if (embedder == null && models.isReady(bge)) {
            send(ChatEvent.Status("Загружаю ${bge.title}…"))
            embedder = runCatching { LlamaEmbedder.load(context, models) }
                .onFailure { AppLog.w(TAG, "эмбеддер не загрузился: ${it.message}") }.getOrNull()
        }
        embedder?.let { e ->
            // Chunks added since the last index run need vectors too, or fresh speech is invisible to the vector side.
            val pending = indexer.pendingCount(e.modelId)
            if (pending in 1..MAX_INLINE_EMBED) {
                send(ChatEvent.Status("Векторизую новые фрагменты: $pending…"))
                indexer.embedPending(e, shouldStop = { !isActive })
            }
        }
        send(ChatEvent.Status("Ищу в дневнике…"))
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val found = search.search(question, today, embedder)

        if (engine == null) {
            send(ChatEvent.Status("Загружаю ${model.spec.title}…"))
            engine = LlamaCppEngine.load(context, model, models, settings.threads, settings.contextSize)
        }
        val llm = engine!!
        val maxOut = 600
        val budget = llm.contextSize - maxOut - llm.countTokens(ChatPrompts.SYSTEM) - 64
        val prompt = ChatPrompts.build(question, today, found.hits.map { it.chunk }, budget, llm::countTokens, zone)
        send(ChatEvent.Sources(prompt.sources))
        send(ChatEvent.Status("Думаю…"))
        val channel = this
        val r = llm.generate(ChatPrompt(prompt.system, prompt.user), GenOptions(maxTokens = maxOut, temperature = 0.3f)) { piece ->
            channel.isActive && channel.trySendBlocking(ChatEvent.Token(piece)).isSuccess
        }
        val stats = "найдено ${found.hits.size} (${found.tookMs} мс), в промпте ${prompt.sources.size}, " +
            "промпт ${r.promptTokens} ток. за %.1f с, ответ ${r.genTokens} ток. (%.1f ток/с)".format(r.promptMs / 1000.0, r.genTps)
        AppLog.i(TAG, "чат: $stats; ${MemoryProbe.snapshot(context)}")
        send(ChatEvent.Done(stats))
    }

    private fun scheduleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            release()
        }
    }

    /** Frees the models and gives the lock back to background workers. */
    fun release() {
        scope.launch {
            session.withLock {
                val had = engine != null || embedder != null
                engine?.close(); engine = null
                embedder?.close(); embedder = null
                if (holdsLock) {
                    holdsLock = false
                    lockHolder.lock.unlock()
                }
                if (had) AppLog.d(TAG, "чат: модели выгружены")
            }
        }
    }

    private companion object {
        const val TAG = "Chat"
        const val IDLE_MS = 120_000L
        const val MAX_INLINE_EMBED = 200
    }
}
