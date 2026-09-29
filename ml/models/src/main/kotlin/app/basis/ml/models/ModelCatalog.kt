package app.basis.ml.models

data class ModelFile(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class ModelSpec(
    val id: String,
    val title: String,
    val files: List<ModelFile>,
    val license: String,
    val source: String,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/**
 * Models are downloaded from their original publishers (never from this repository) and verified
 * by SHA-256. Alternatively the same files can be imported with the system file picker.
 */
object ModelCatalog {
    val SILERO_VAD = ModelSpec(
        id = "silero-vad-v5",
        title = "Silero VAD v5",
        files = listOf(
            ModelFile(
                name = "silero_vad.onnx",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                sizeBytes = 643_854,
            ),
        ),
        license = "MIT",
        source = "github.com/snakers4/silero-vad через релизы k2-fsa/sherpa-onnx",
    )

    val all: List<ModelSpec> = listOf(SILERO_VAD)

    /** Finds which catalog file a blob is, by hash first, then by file name. */
    fun identify(sha256: String, displayName: String?): Pair<ModelSpec, ModelFile>? {
        for (spec in all) spec.files.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }?.let { return spec to it }
        if (displayName != null) {
            for (spec in all) spec.files.firstOrNull { it.name == displayName }?.let { return spec to it }
        }
        return null
    }
}
