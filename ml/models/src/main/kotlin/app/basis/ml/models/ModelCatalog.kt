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

enum class ModelRole { VAD, ASR, LLM, EMBEDDING }

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

    private const val HF = "https://huggingface.co"

    private fun gguf(id: String, title: String, repo: String, file: String, sha256: String, size: Long) = ModelSpec(
        id = id,
        title = title,
        role = ModelRole.LLM,
        license = "Apache-2.0",
        source = "Qwen (Alibaba), GGUF-квантизация unsloth: huggingface.co/$repo",
        files = listOf(ModelFile(name = file, url = "$HF/$repo/resolve/main/$file", sha256 = sha256, sizeBytes = size)),
    )

    /** Default: best quality/speed balance for Russian summaries on a flagship phone. */
    val QWEN35_4B = gguf(
        "qwen3.5-4b-q4km", "Qwen3.5 4B (Q4_K_M)", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf",
        "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", 2_740_937_888,
    )
    val QWEN35_2B = gguf(
        "qwen3.5-2b-q4km", "Qwen3.5 2B (Q4_K_M, быстрее)", "unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_K_M.gguf",
        "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", 1_280_835_840,
    )
    val QWEN35_08B = gguf(
        "qwen3.5-0.8b-q4km", "Qwen3.5 0.8B (Q4_K_M, для проверки)", "unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf",
        "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", 532_517_120,
    )

    /** Q4_0: the format best served by the ARM-optimized kernels (KleidiAI/repack) — possibly faster prompt processing. */
    val QWEN35_4B_Q40 = gguf(
        "qwen3.5-4b-q40", "Qwen3.5 4B (Q4_0, быстрые ARM-ядра)", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_0.gguf",
        "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb", 2_583_221_408,
    )

    /** Classic transformer (no hybrid DeltaNet layers): well-optimized CPU kernels in llama.cpp. */
    val QWEN3_4B_2507 = gguf(
        "qwen3-4b-2507-q4km", "Qwen3 4B Instruct 2507 (Q4_K_M)", "unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597", 2_497_281_120,
    )

    val all: List<ModelSpec> = listOf(SILERO_VAD, GIGAAM_V3, WHISPER_BASE, WHISPER_SMALL, WHISPER_TURBO, QWEN35_4B, QWEN35_4B_Q40, QWEN35_2B, QWEN35_08B, QWEN3_4B_2507)

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
