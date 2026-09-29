package app.basis.ml.llm

import app.basis.ml.models.ModelCatalog
import app.basis.ml.models.ModelSpec

/** LLMs the user can pick for summaries and chat. */
enum class LlmModel(val spec: ModelSpec, val format: PromptFormat) {
    QWEN35_4B(ModelCatalog.QWEN35_4B, PromptFormat.QWEN_CHATML_NO_THINK),
    QWEN35_2B(ModelCatalog.QWEN35_2B, PromptFormat.QWEN_CHATML_NO_THINK),
    QWEN35_08B(ModelCatalog.QWEN35_08B, PromptFormat.QWEN_CHATML_NO_THINK),
    ;

    companion object {
        fun byId(id: String?): LlmModel = entries.firstOrNull { it.spec.id == id } ?: QWEN35_4B
    }
}
