package app.basis.pipeline.search

import app.basis.core.database.ChunkEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A fragment shown to the model as [n] and to the user as a source chip. */
data class ChatSource(val n: Int, val chunk: ChunkEntity, val label: String)

data class BuiltPrompt(val system: String, val user: String, val sources: List<ChatSource>)

/** Retrieval-augmented prompt for questions about the diary. Pure; unit-tested. */
object ChatPrompts {
    val SYSTEM = """
        Ты — помощник по личному дневнику пользователя. Дневник — это автоматическая расшифровка разговоров вокруг пользователя (без пунктуации, возможны ошибки распознавания, говорящие не подписаны) и сводки по часам и дням.
        Отвечай по-русски, коротко и по делу, только на основе фрагментов из вопроса. После каждого факта ставь ссылку на фрагмент в квадратных скобках, например [2].
        Если во фрагментах нет ответа — прямо скажи, что в дневнике этого не нашлось. Ничего не выдумывай. Не пересказывай фрагменты целиком.
    """.trimIndent()

    private val RU = Locale.forLanguageTag("ru")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", RU)
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", RU)

    fun label(c: ChunkEntity, zone: ZoneId): String {
        val day = DAY.format(LocalDate.parse(c.day))
        return when (c.kind) {
            ChunkEntity.DAY -> "$day, итог дня"
            ChunkEntity.HOUR -> "$day, сводка ${hm(c.startMs, zone)}–${hm(c.endMs, zone)}"
            else -> "$day, ${hm(c.startMs, zone)}–${hm(c.endMs, zone)}, расшифровка"
        }
    }

    private fun hm(ms: Long, zone: ZoneId) = HM.format(Instant.ofEpochMilli(ms).atZone(zone))

    /**
     * Puts as many hits (in rank order) as fit into [budgetTokens], then orders them chronologically so the
     * model sees the story in sequence. A fragment that does not fit whole is cut if at least 1/3 fits.
     */
    fun build(question: String, today: LocalDate, hits: List<ChunkEntity>, budgetTokens: Int, countTokens: (String) -> Int, zone: ZoneId): BuiltPrompt {
        val header = "Сегодня ${DAY.format(today)}.\n\nФрагменты дневника:\n"
        val footer = "\n\nВопрос: ${question.trim()}"
        var left = budgetTokens - countTokens(header) - countTokens(footer)
        val picked = mutableListOf<Pair<ChunkEntity, String>>()
        for (c in hits) {
            if (left <= 50) break
            val body = c.text
            val cost = countTokens(body) + 30
            if (cost <= left) {
                picked += c to body
                left -= cost
            } else if (left >= cost / 3) {
                val ratio = (left - 30).toDouble() / (cost - 30)
                val cut = body.take((body.length * ratio).toInt().coerceAtLeast(1)).substringBeforeLast(' ') + " …"
                picked += c to cut
                left = 0
            }
        }
        val ordered = picked.sortedWith(compareBy({ it.first.day }, { it.first.startMs }, { it.first.kind != ChunkEntity.DAY }))
        val sources = ordered.mapIndexed { i, (c, _) -> ChatSource(i + 1, c, label(c, zone)) }
        val user = buildString {
            append(header)
            if (ordered.isEmpty()) append("(ничего не найдено)\n")
            ordered.forEachIndexed { i, (_, text) ->
                append('[').append(i + 1).append("] (").append(sources[i].label).append(")\n").append(text).append("\n\n")
            }
            append(footer)
        }
        return BuiltPrompt(SYSTEM, user, sources)
    }

    /** Numbers of fragments the answer actually cites, e.g. "[1], [3]" → {1, 3}. */
    fun citations(answer: String): Set<Int> =
        Regex("\\[(\\d{1,2}(?:\\s*[,;]\\s*\\d{1,2})*)]").findAll(answer)
            .flatMap { m -> m.groupValues[1].split(',', ';').mapNotNull { it.trim().toIntOrNull() } }.toSet()
}
