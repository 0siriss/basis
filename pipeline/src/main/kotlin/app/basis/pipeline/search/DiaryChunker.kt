package app.basis.pipeline.search

import app.basis.core.database.ChunkEntity
import app.basis.pipeline.summary.Digest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Segment(val startMs: Long, val endMs: Long, val text: String)

data class HourDigest(val hourStartMs: Long, val digest: Digest)

/** What gets indexed; kind is ChunkEntity.TRANSCRIPT / HOUR / DAY. */
data class ChunkDraft(val kind: String, val startMs: Long, val endMs: Long, val text: String) {
    val hash: String get() = chunkHash(kind, text)
}

/**
 * Splits a day into search chunks:
 * - transcript: consecutive segments of one clock hour packed to ~[targetWords] words (a few minutes of
 *   speech) — small enough for a precise vector, big enough to keep the context of a conversation;
 * - one chunk per hour digest and one for the day digest, rendered as plain text.
 */
class DiaryChunker(
    private val zone: ZoneId,
    private val targetWords: Int = 180,
    private val maxWords: Int = 280,
) {
    fun chunk(segments: List<Segment>, hours: List<HourDigest>, day: Digest?, dayStartMs: Long, dayEndMs: Long): List<ChunkDraft> {
        val out = mutableListOf<ChunkDraft>()
        segments.sortedBy { it.startMs }.groupBy { hourStart(it.startMs) }.forEach { (_, inHour) -> out += packHour(inHour) }
        for (h in hours.sortedBy { it.hourStartMs }) {
            render("Сводка за ${hm(h.hourStartMs)}–${hm(h.hourStartMs + HOUR_MS)}", h.digest)?.let {
                out += ChunkDraft(ChunkEntity.HOUR, h.hourStartMs, h.hourStartMs + HOUR_MS, it)
            }
        }
        day?.let { d -> render("Итог дня", d)?.let { out += ChunkDraft(ChunkEntity.DAY, dayStartMs, dayEndMs, it) } }
        return out.distinctBy { it.hash }
    }

    private fun packHour(segs: List<Segment>): List<ChunkDraft> {
        val out = mutableListOf<ChunkDraft>()
        val lines = mutableListOf<String>()
        var words = 0
        var start = 0L
        var end = 0L
        fun flush() {
            if (lines.isNotEmpty()) out += ChunkDraft(ChunkEntity.TRANSCRIPT, start, end, lines.joinToString("\n"))
            lines.clear(); words = 0
        }
        for (s in segs) {
            val w = s.text.split(WS).filter(String::isNotEmpty)
            if (w.isEmpty()) continue
            // One very long segment (a 28 s monologue can be ~90 words; merged ones more): split by words.
            val parts = w.chunked(maxWords)
            for (p in parts) {
                if (words > 0 && words + p.size > maxWords) flush()
                if (lines.isEmpty()) start = s.startMs
                lines += "[${hm(s.startMs)}] ${p.joinToString(" ")}"
                words += p.size
                end = s.endMs
                if (words >= targetWords) flush()
            }
        }
        flush()
        return out
    }

    private fun hourStart(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(zone).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()

    private fun hm(ms: Long) = HM.format(Instant.ofEpochMilli(ms).atZone(zone))

    companion object {
        private const val HOUR_MS = 3_600_000L
        private val WS = Regex("\\s+")
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ru"))

        /** Plain-text rendering of a digest for search and for the chat prompt; null if empty. */
        fun render(title: String, d: Digest): String? {
            if (d.isEmpty) return null
            return buildString {
                append(title).append(": ").append(d.summary.ifBlank { "—" })
                fun list(name: String, items: List<String>) {
                    if (items.isNotEmpty()) append('\n').append(name).append(": ").append(items.joinToString("; "))
                }
                list("События", d.events)
                list("Договорённости и обещания", d.agreements)
                list("Задачи", d.tasks)
                list("Идеи", d.ideas)
                if (d.mood.isNotBlank()) append("\nНастроение: ").append(d.mood)
            }
        }
    }
}
