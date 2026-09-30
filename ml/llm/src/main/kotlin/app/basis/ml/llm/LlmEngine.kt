package app.basis.ml.llm

data class GenOptions(
    val maxTokens: Int = 1024,
    /** 0 = greedy. Low temperature: summaries should be faithful, not creative. */
    val temperature: Float = 0.2f,
    /** GBNF grammar constraining the output (e.g. to a JSON schema), or null. */
    val grammar: String? = null,
    val seed: Int = 42,
)

data class GenResult(
    val text: String,
    val promptTokens: Int,
    val promptMs: Long,
    val genTokens: Int,
    val genMs: Long,
    /** Times the stall watchdog had to cut threads during this generation. */
    val stalls: Int = 0,
    val threadsAtEnd: Int = 0,
) {
    val promptTps: Double get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
    val genTps: Double get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
}

/** A chat turn sequence rendered with the model's prompt format. */
data class ChatPrompt(val system: String, val user: String)

/** Local LLM; the engine (llama.cpp today) can be swapped without touching summaries or chat. */
interface LlmEngine : AutoCloseable {
    val modelId: String
    val contextSize: Int
    fun countTokens(text: String): Int
    fun format(prompt: ChatPrompt): String

    /** [onText] receives decoded text pieces; return false to stop early. */
    fun generate(prompt: ChatPrompt, options: GenOptions = GenOptions(), onText: (String) -> Boolean = { true }): GenResult
}
