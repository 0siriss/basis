package app.basis.ml.llm

import android.content.Context
import app.basis.core.common.AppLog
import app.basis.core.common.MemoryProbe
import app.basis.ml.models.ModelManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** llama.cpp (GGUF, CPU) behind [LlmEngine]. Not thread-safe: one generation at a time. */
class LlamaCppEngine private constructor(
    private val context: Context,
    override val modelId: String,
    private val format: PromptFormat,
    private var handle: Long,
) : LlmEngine {

    override val contextSize: Int get() = LlamaNative.nativeContextSize(handle)

    override fun countTokens(text: String): Int = LlamaNative.nativeCountTokens(handle, text.toByteArray())

    override fun format(prompt: ChatPrompt): String = format.render(prompt)

    @Synchronized
    override fun generate(prompt: ChatPrompt, options: GenOptions, onText: (String) -> Boolean): GenResult {
        check(handle != 0L) { "engine closed" }
        val decoder = Utf8Stream()
        val bytes = LlamaNative.nativeGenerate(
            handle, format.render(prompt).toByteArray(), options.grammar, options.maxTokens, options.temperature, options.seed,
        ) { piece -> decoder.push(piece)?.let(onText) ?: true } ?: throw IOException("llama.cpp: генерация не удалась (см. logcat BasisLlm)")
        val s = LlamaNative.nativeLastStats(handle)
        return GenResult(String(bytes, Charsets.UTF_8), s[0].toInt(), s[1], s[2].toInt(), s[3])
    }

    /** Changes the thread count without reloading (used by the speed test). */
    @Synchronized
    fun setThreads(n: Int) = LlamaNative.nativeSetThreads(handle, n)

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            LlamaNative.nativeFree(handle)
            handle = 0
            LlmGuard.end(context)
        }
    }

    /** Decodes a UTF-8 byte stream whose pieces may split multi-byte characters. */
    private class Utf8Stream {
        private val pending = ByteArrayOutputStream()
        private val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        fun push(b: ByteArray): String? {
            pending.write(b)
            val all = pending.toByteArray()
            // Try the longest prefix that ends on a character boundary (≤ 3 bytes of an incomplete char).
            for (cut in 0..minOf(3, all.size)) {
                try {
                    val s = dec.decode(ByteBuffer.wrap(all, 0, all.size - cut)).toString()
                    pending.reset()
                    pending.write(all, all.size - cut, cut)
                    return s.ifEmpty { null }
                } catch (_: CharacterCodingException) {
                }
            }
            return null
        }
    }

    companion object {
        /** Loads [model] (must be downloaded). Loading a 4B Q4 model takes a few seconds (mmap). */
        fun load(
            context: Context,
            model: LlmModel,
            models: ModelManager,
            threads: Int,
            contextSize: Int = 4096,
            repack: Boolean = true,
        ): LlamaCppEngine {
            val info = LlamaNative.init(context.applicationInfo.nativeLibraryDir)
            val spec = model.spec
            require(models.isReady(spec)) { "модель ${spec.title} не загружена" }
            val path = models.file(spec, spec.requiredFiles.single()).absolutePath
            AppLog.i("LLM", "загрузка ${spec.title}: контекст $contextSize, потоков $threads, переупаковка весов=$repack; до: ${MemoryProbe.snapshot(context)}")
            LlmGuard.begin(context, spec.id)
            val t0 = System.currentTimeMillis()
            val h = LlamaNative.nativeLoad(path, contextSize, threads, repack)
            if (h == 0L) {
                LlmGuard.end(context)
                throw IOException("llama.cpp не смог загрузить ${spec.title}")
            }
            AppLog.i("LLM", "${spec.title} загружена за ${System.currentTimeMillis() - t0} мс: ${LlamaNative.nativeDescribe(h)}; после: ${MemoryProbe.snapshot(context)}")
            AppLog.d("LLM", info.take(500))
            return LlamaCppEngine(context.applicationContext, spec.id, model.format, h)
        }
    }
}
