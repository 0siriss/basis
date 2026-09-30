package app.basis.pipeline.summary

import android.content.Context
import app.basis.core.common.AppLog
import app.basis.core.common.MemoryProbe
import app.basis.core.datastore.LlmPrefs
import app.basis.ml.llm.ChatPrompt
import app.basis.ml.llm.GenOptions
import app.basis.ml.llm.LlamaCppEngine
import app.basis.ml.llm.LlmModel
import app.basis.ml.models.ModelManager
import app.basis.pipeline.TranscriptionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Measures prompt/generation speed of the selected LLM at 2, 4 and 6 threads. Results go to the log (tag LLM). */
@Singleton
class LlmBenchmark @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LlmPrefs,
    private val models: ModelManager,
    private val lockHolder: TranscriptionStatus,
) {
    suspend fun run(): String = withContext(Dispatchers.Default) {
        if (!lockHolder.lock.tryLock()) return@withContext "Занято другой задачей, попробуйте позже"
        try {
            val s = prefs.current()
            val model = LlmModel.byId(s.modelId)
            if (!models.isReady(model.spec)) return@withContext "Модель ${model.spec.title} не загружена"
            val engine = LlamaCppEngine.load(context, model, models, 6, s.contextSize)
            val text = (1..12).joinToString(" ") {
                "мы договорились с Сашей встретиться во вторник в кафе у метро и обсудить ремонт квартиры, он обещал прислать смету до пятницы"
            }
            val prompt = ChatPrompt("Ты помощник. Отвечай кратко по-русски.", "Перескажи в трёх предложениях: $text")
            val results = mutableListOf<String>()
            try {
                engine.generate(prompt, GenOptions(maxTokens = 8, temperature = 0f)) // warm-up (page-in weights)
                for (t in listOf(2, 4, 6)) {
                    engine.setThreads(t)
                    val r = engine.generate(prompt, GenOptions(maxTokens = 96, temperature = 0f))
                    val line = "$t потоков: промпт ${r.promptTokens} ток. %.0f ток/с, генерация ${r.genTokens} ток. %.1f ток/с".format(r.promptTps, r.genTps)
                    AppLog.i("LLM", "тест скорости ${model.spec.title}, $line; ${MemoryProbe.snapshot(context)}")
                    results += line
                }
            } finally {
                engine.close()
            }
            "${model.spec.title}: " + results.joinToString("; ")
        } catch (t: Throwable) {
            AppLog.e("LLM", "тест скорости упал", t)
            "Ошибка: ${t.message}"
        } finally {
            lockHolder.lock.unlock()
        }
    }
}
