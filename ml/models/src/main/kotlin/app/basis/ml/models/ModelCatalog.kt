package app.basis.ml.models

/** A single file downloaded as-is. */
data class ModelFile(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * A `.tar.bz2` published by the model author; only [members] are extracted (by file name, any
 * directory inside the archive), everything else (fp32 variants, test wavs) is skipped.
 */
data class ModelArchive(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val members: List<String>,
)

enum class ModelRole { VAD, ASR }

data class ModelSpec(
    val id: String,
    val title: String,
    val role: ModelRole,
    val license: String,
    val source: String,
    val files: List<ModelFile> = emptyList(),
    val archive: ModelArchive? = null,
    /** Approximate size on device after extraction. */
    val installedBytes: Long = files.sumOf { it.sizeBytes },
) {
    init {
        require(files.isNotEmpty() != (archive != null)) { "either files or archive" }
    }

    val downloadBytes: Long get() = archive?.sizeBytes ?: files.sumOf { it.sizeBytes }

    /** Names of the files that must be present for the model to be usable. */
    val requiredFiles: List<String> get() = archive?.members ?: files.map { it.name }
}

/**
 * Models are downloaded from their original publishers (never from this repository) and verified
 * by SHA-256. Alternatively the same file/archive can be imported with the system file picker.
 */
object ModelCatalog {
    private const val SHERPA_ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val SILERO_VAD = ModelSpec(
        id = "silero-vad-v5",
        title = "Silero VAD v5",
        role = ModelRole.VAD,
        license = "MIT",
        source = "snakers4/silero-vad через релизы k2-fsa/sherpa-onnx",
        files = listOf(
            ModelFile(
                name = "silero_vad.onnx",
                url = "$SHERPA_ASR/silero_vad.onnx",
                sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                sizeBytes = 643_854,
            ),
        ),
    )

    /** GigaAM v3 RNN-T (Sber, MIT): best Russian WER; lowercase, no punctuation. */
    val GIGAAM_V3 = ModelSpec(
        id = "gigaam-v3-rnnt",
        title = "GigaAM v3 (русский)",
        role = ModelRole.ASR,
        license = "MIT",
        source = "salute-developers/GigaAM через релизы k2-fsa/sherpa-onnx",
        archive = ModelArchive(
            url = "$SHERPA_ASR/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16.tar.bz2",
            sha256 = "20a439491904b839f35eb8efaa3d99cbfaaad0c6dcba22d06ff218bb0056772d",
            sizeBytes = 167_388_020,
            members = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "LICENSE"),
        ),
        installedBytes = 229_345_000,
    )

    val WHISPER_BASE = ModelSpec(
        id = "whisper-base-int8",
        title = "Whisper base (мультиязычная)",
        role = ModelRole.ASR,
        license = "MIT",
        source = "openai/whisper через релизы k2-fsa/sherpa-onnx",
        archive = ModelArchive(
            url = "$SHERPA_ASR/sherpa-onnx-whisper-base.tar.bz2",
            sha256 = "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de",
            sizeBytes = 207_557_382,
            members = listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"),
        ),
        installedBytes = 160_610_000,
    )

    val WHISPER_SMALL = ModelSpec(
        id = "whisper-small-int8",
        title = "Whisper small (мультиязычная)",
        role = ModelRole.ASR,
        license = "MIT",
        source = "openai/whisper через релизы k2-fsa/sherpa-onnx",
        archive = ModelArchive(
            url = "$SHERPA_ASR/sherpa-onnx-whisper-small.tar.bz2",
            sha256 = "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6",
            sizeBytes = 639_387_718,
            members = listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"),
        ),
        installedBytes = 375_485_000,
    )

    val WHISPER_TURBO = ModelSpec(
        id = "whisper-turbo-int8",
        title = "Whisper large-v3-turbo (мультиязычная, тяжёлая)",
        role = ModelRole.ASR,
        license = "MIT",
        source = "openai/whisper через релизы k2-fsa/sherpa-onnx",
        archive = ModelArchive(
            url = "$SHERPA_ASR/sherpa-onnx-whisper-turbo.tar.bz2",
            sha256 = "b11acbbcd660b44a8e0df33724feb5aaa709cf65668f2823d59f656312544f22",
            sizeBytes = 563_790_207,
            members = listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"),
        ),
        installedBytes = 1_036_614_000,
    )

    val all: List<ModelSpec> = listOf(SILERO_VAD, GIGAAM_V3, WHISPER_BASE, WHISPER_SMALL, WHISPER_TURBO)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    sealed interface Match {
        data class File(val spec: ModelSpec, val file: ModelFile) : Match
        data class Archive(val spec: ModelSpec) : Match
    }

    /** Finds which catalog file or archive a blob is: by hash first, then by file name. */
    fun identify(sha256: String, displayName: String?): Match? {
        for (spec in all) {
            spec.files.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }?.let { return Match.File(spec, it) }
            if (spec.archive?.sha256.equals(sha256, ignoreCase = true)) return Match.Archive(spec)
        }
        if (displayName != null) {
            for (spec in all) {
                spec.files.firstOrNull { it.name == displayName }?.let { return Match.File(spec, it) }
                if (spec.archive != null && spec.archive.url.substringAfterLast('/') == displayName) return Match.Archive(spec)
            }
        }
        return null
    }
}
