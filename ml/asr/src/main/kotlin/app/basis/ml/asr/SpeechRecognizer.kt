package app.basis.ml.asr

import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelManager
import app.basis.ml.models.ModelSpec
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

data class AsrResult(val text: String, val lang: String)

/** Common interface for all offline ASR engines; swap the engine without touching the pipeline. */
interface SpeechRecognizer : AutoCloseable {
    val modelId: String
    fun transcribe(pcm: ShortArray, sampleRate: Int): AsrResult
}

/** ASR models the user can choose in settings. */
enum class AsrModel(val spec: ModelSpec, val multilingual: Boolean) {
    GIGAAM_V3(ModelCatalog.GIGAAM_V3, multilingual = false),
    WHISPER_BASE(ModelCatalog.WHISPER_BASE, multilingual = true),
    WHISPER_SMALL(ModelCatalog.WHISPER_SMALL, multilingual = true),
    WHISPER_TURBO(ModelCatalog.WHISPER_TURBO, multilingual = true),
    ;

    companion object {
        fun byId(id: String?): AsrModel = entries.firstOrNull { it.spec.id == id } ?: GIGAAM_V3
    }
}

data class AsrOptions(
    val threads: Int = 4,
    /** Whisper only: "" = auto-detect per segment (good for ru/en mix), or "ru", "en"… */
    val whisperLanguage: String = "",
)

object SpeechRecognizers {
    fun create(model: AsrModel, models: ModelManager, options: AsrOptions): SpeechRecognizer {
        val spec = model.spec
        require(models.isReady(spec)) { "модель ${spec.title} не загружена" }
        fun f(name: String) = models.file(spec, name).absolutePath
        val modelConfig = when (model) {
            AsrModel.GIGAAM_V3 -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = f("encoder.int8.onnx"), decoder = f("decoder.onnx"), joiner = f("joiner.onnx")),
                tokens = f("tokens.txt"),
                modelType = "nemo_transducer",
                numThreads = options.threads,
            )
            else -> {
                val p = when (model) {
                    AsrModel.WHISPER_BASE -> "base"
                    AsrModel.WHISPER_SMALL -> "small"
                    else -> "turbo"
                }
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = f("$p-encoder.int8.onnx"),
                        decoder = f("$p-decoder.int8.onnx"),
                        language = options.whisperLanguage,
                        task = "transcribe",
                    ),
                    tokens = f("$p-tokens.txt"),
                    modelType = "whisper",
                    numThreads = options.threads,
                )
            }
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = if (model == AsrModel.GIGAAM_V3) 64 else 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        return SherpaRecognizer(spec.id, OfflineRecognizer(null, config))
    }
}

private class SherpaRecognizer(override val modelId: String, private val recognizer: OfflineRecognizer) : SpeechRecognizer {
    override fun transcribe(pcm: ShortArray, sampleRate: Int): AsrResult {
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            return AsrResult(r.text.trim(), r.lang.trim('<', '>', '|', ' '))
        } finally {
            stream.release()
            samples.fill(0f)
        }
    }

    override fun close() = recognizer.release()
}
