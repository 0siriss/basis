package app.basis.ml.llm

/** Chat formats of supported model families. */
enum class PromptFormat {
    /** Qwen3.x ChatML; an empty think block switches "thinking" off (faster, shorter output). */
    QWEN_CHATML_NO_THINK,
    /** Plain ChatML (instruct models without a thinking mode, e.g. Qwen3-2507-Instruct). */
    QWEN_CHATML,
    ;

    fun render(p: ChatPrompt): String = when (this) {
        QWEN_CHATML_NO_THINK ->
            "<|im_start|>system\n${p.system}<|im_end|>\n" +
                "<|im_start|>user\n${p.user}<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        QWEN_CHATML ->
            "<|im_start|>system\n${p.system}<|im_end|>\n" +
                "<|im_start|>user\n${p.user}<|im_end|>\n" +
                "<|im_start|>assistant\n"
    }
}
