package app.basis.audio.vad

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** Speech probability for one 512-sample (32 ms @ 16 kHz) window. Stateful (recurrent model). */
interface VadEngine : AutoCloseable {
    fun probability(window: FloatArray): Float
    fun reset()
}

/** Silero VAD v5 via sherpa-onnx (ONNX Runtime, CPU, 1 thread). */
class SileroVadEngine(model: File, sampleRate: Int = 16_000, windowSize: Int = 512) : VadEngine {
    // null AssetManager: load the model from a file path, not from APK assets.
    private val vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = model.absolutePath, windowSize = windowSize),
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    override fun probability(window: FloatArray): Float = vad.compute(window)

    override fun reset() = vad.reset()

    override fun close() = vad.release()
}
