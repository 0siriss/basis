package app.basis.pipeline.summary

/** Minimal LLM surface the summarizer needs (implemented over LlmEngine; faked in tests). */
interface TextLlm {
    fun countTokens(text: String): Int
    /** Returns raw model output constrained by [grammar]. */
    fun complete(system: String, user: String, grammar: String, maxTokens: Int): String
}

data class Line(val time: String, val text: String) {
    override fun toString() = "[$time] $text"
}

/**
 * Map-reduce summarization:
 * - hour: transcript lines → chunks within the input budget → one Digest per chunk (map) → merged (reduce);
 * - day: hour digests → merged; if they don't fit at once, merged hierarchically in groups.
 * Pure logic, unit-tested with a fake LLM.
 */
class Summarizer(
    private val llm: TextLlm,
    /** Tokens available for the variable part of the prompt (context − instructions − output). */
    private val inputBudget: Int,
    private val maxOutputTokens: Int = 700,
    private val onCall: (kind: String) -> Unit = {},
) {
    private val chunker = Chunker(llm::countTokens, inputBudget)

    fun summarizePeriod(periodLabel: String, lines: List<Line>): Digest {
        if (lines.isEmpty()) return Digest()
        val chunks = chunker.chunk(lines.map(Line::toString))
        val parts = chunks.mapIndexed { i, chunk ->
            val label = if (chunks.size == 1) periodLabel else "$periodLabel, часть ${i + 1} из ${chunks.size}"
            onCall("map")
            Digest.parse(llm.complete(Prompts.SYSTEM, Prompts.transcript(label, chunk), Digest.GRAMMAR, maxOutputTokens))
        }
        return reduce(periodLabel, parts, day = false)
    }

    fun summarizeDay(dayLabel: String, hours: List<Pair<String, Digest>>): Digest {
        val nonEmpty = hours.filterNot { it.second.isEmpty }
        if (nonEmpty.isEmpty()) return Digest()
        return reduceLabeled(dayLabel, nonEmpty.map { (label, d) -> "$label: ${d.toJson()}" }, day = true)
    }

    private fun reduce(label: String, parts: List<Digest>, day: Boolean): Digest {
        val meaningful = parts.filterNot { it.isEmpty }
        return when {
            meaningful.isEmpty() -> Digest()
            meaningful.size == 1 && !day -> meaningful.single()
            else -> reduceLabeled(label, meaningful.mapIndexed { i, d -> "Часть ${i + 1}: ${d.toJson()}" }, day)
        }
    }

    /** Merges labeled digests; recurses on groups when they don't fit into one prompt. */
    private fun reduceLabeled(label: String, items: List<String>, day: Boolean): Digest {
        val groups = chunker.chunk(items)
        if (groups.size == 1) {
            onCall(if (day) "day" else "reduce")
            return Digest.parse(llm.complete(Prompts.SYSTEM, Prompts.merge(label, groups.single(), day), Digest.GRAMMAR, maxOutputTokens))
        }
        val merged = groups.mapIndexed { i, g ->
            onCall("reduce")
            val d = Digest.parse(llm.complete(Prompts.SYSTEM, Prompts.merge("$label, группа ${i + 1}", g, day = false), Digest.GRAMMAR, maxOutputTokens))
            "Группа ${i + 1}: ${d.toJson()}"
        }
        require(merged.size < items.size) { "reduce does not converge: budget too small" }
        return reduceLabeled(label, merged, day)
    }
}

object Prompts {
    val SYSTEM = """
        Ты ведёшь личный дневник владельца телефона. Тебе дают автоматическую расшифровку его разговоров за период:
        текст без пунктуации, с ошибками распознавания; рядом могут говорить другие люди, телевизор или радио.
        Составь краткую точную выжимку на русском языке. Не выдумывай ничего, чего нет в тексте. Имена, числа, даты
        и сроки переноси точно. Если для категории ничего нет — пустой список. Отвечай только JSON.
    """.trimIndent()

    private val FIELDS = """
        Поля JSON:
        summary — 2–4 предложения: что происходило;
        events — события и встречи (с временем, если понятно);
        agreements — договорённости и обещания: кто, кому, что и к какому сроку;
        tasks — задачи и дела, которые нужно сделать;
        ideas — идеи и мысли, которые стоит запомнить;
        mood — настроение и тон одним-тремя словами.
    """.trimIndent()

    fun transcript(label: String, lines: List<String>) =
        "Период: $label.\nРасшифровка (время — начало фразы):\n${lines.joinToString("\n")}\n\n$FIELDS"

    fun merge(label: String, parts: List<String>, day: Boolean) =
        (if (day) "День: $label. Ниже выжимки по часам. Составь итог дня: summary — 4–6 предложений о дне в целом, " +
            "в остальных полях — объединённые списки без повторов, самое важное первым.\n"
        else "Период: $label. Ниже выжимки частей одного периода. Объедини их в одну выжимку без повторов.\n") +
            parts.joinToString("\n") + "\n\n$FIELDS"
}
