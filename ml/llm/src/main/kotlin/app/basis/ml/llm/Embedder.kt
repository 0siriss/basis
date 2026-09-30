package app.basis.ml.llm

import android.content.Context
import app.basis.core.common.AppLog
import app.basis.core.common.MemoryProbe
import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelManager
import java.io.IOException

/** Text → L2-normalized vector (cosine similarity = dot product). The engine can be swapped. */
interface Embedder : AutoCloseable {
    val modelId: String
    val dim: Int

    /** [text] is truncated to the model's context. */
    fun embed(text: String): FloatArray
}

/** BGE-M3 (or another GGUF embedding model) on llama.cpp. Not thread-safe. */
class LlamaEmbedder private constructor(
    override val modelId: String,
    private var handle: Long,
) : Embedder {
    override val dim: Int = LlamaNative.nativeEmbedDim(handle)

    @Synchronized
    override fun embed(text: String): FloatArray {
        check(handle != 0L) { "embedder closed" }
        return LlamaNative.nativeEmbed(handle, text.toByteArray()) ?: throw IOException("llama.cpp: не удалось получить эмбеддинг")
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            LlamaNative.nativeFree(handle)
            handle = 0
        }
    }

    companion object {
        /**
         * Context 1024 tokens: our chunks are ≤ ~400 tokens, digests smaller; BGE-M3 needs the whole
         * input in one batch, so the context also bounds compute-buffer memory (~0.1 GB at 1024).
         */
        fun load(context: Context, models: ModelManager, threads: Int = 4, contextSize: Int = 1024): LlamaEmbedder {
            LlamaNative.init(context.applicationInfo.nativeLibraryDir)
            val spec = ModelCatalog.BGE_M3
            require(models.isReady(spec)) { "модель ${spec.title} не загружена" }
            val path = models.file(spec, spec.requiredFiles.single()).absolutePath
            val t0 = System.currentTimeMillis()
            val h = LlamaNative.nativeEmbedLoad(path, contextSize, threads)
            if (h == 0L) throw IOException("llama.cpp не смог загрузить ${spec.title}")
            return LlamaEmbedder(spec.id, h).also {
                AppLog.i("Embed", "${spec.title} загружена за ${System.currentTimeMillis() - t0} мс: dim ${it.dim}; ${MemoryProbe.snapshot(context)}")
            }
        }
    }
}
