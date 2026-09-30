package app.basis.pipeline.search

import app.basis.core.database.ChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChatPromptsTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 30)
    private fun ms(day: String, h: Int) = LocalDate.parse(day).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun chunk(id: Long, day: String, h: Int, text: String, kind: String = ChunkEntity.TRANSCRIPT) =
        ChunkEntity(id = id, day = day, kind = kind, startMs = ms(day, h), endMs = ms(day, h) + 600_000, text = text, hash = "$id")

    /** 1 token per word, roughly. */
    private val tokens: (String) -> Int = { s -> s.split(Regex("\\s+")).count { it.isNotEmpty() } }

    @Test fun fragmentsAreNumberedChronologically() {
        val hits = listOf(chunk(2, "2026-09-29", 15, "позвоню тебе в четверг"), chunk(1, "2026-09-28", 10, "привет саша"))
        val p = ChatPrompts.build("что я обещал Саше?", today, hits, 1000, tokens, zone)
        assertEquals(listOf(1L, 2L), p.sources.map { it.chunk.id })
        assertTrue(p.user.startsWith("Сегодня среда, 30 сентября 2026."))
        assertTrue(p.user.contains("[1] (понедельник, 28 сентября 2026, 10:00–10:10, расшифровка)\nпривет саша"))
        assertTrue(p.user.contains("[2] (вторник, 29 сентября 2026, 15:00–15:10, расшифровка)\nпозвоню тебе в четверг"))
        assertTrue(p.user.trimEnd().endsWith("Вопрос: что я обещал Саше?"))
    }

    @Test fun budgetDropsLowRankedFragments() {
        val big = List(80) { "слово" }.joinToString(" ")
        val hits = (1L..10L).map { chunk(it, "2026-09-29", it.toInt(), big) }
        val p = ChatPrompts.build("вопрос", today, hits, 400, tokens, zone)
        // Each fragment costs 80 + 30 tokens → 3 whole ones (plus maybe one cut) fit in ~380.
        assertTrue(p.sources.size in 3..4)
        // Highest-ranked (first) hits are kept.
        assertTrue(p.sources.map { it.chunk.id }.containsAll(listOf(1L, 2L, 3L)))
    }

    @Test fun emptyResultIsExplicit() {
        val p = ChatPrompts.build("вопрос", today, emptyList(), 1000, tokens, zone)
        assertTrue(p.user.contains("(ничего не найдено)"))
        assertTrue(p.sources.isEmpty())
    }

    @Test fun dayDigestLabel() {
        val p = ChatPrompts.build("q", today, listOf(chunk(5, "2026-09-29", 0, "Итог дня: x", ChunkEntity.DAY)), 1000, tokens, zone)
        assertEquals("вторник, 29 сентября 2026, итог дня", p.sources.single().label)
    }

    @Test fun sameMinuteLabelIsCollapsed() {
        val c = ChunkEntity(id = 1, day = "2026-09-29", kind = ChunkEntity.TRANSCRIPT, startMs = ms("2026-09-29", 5), endMs = ms("2026-09-29", 5) + 20_000, text = "x", hash = "1")
        assertEquals("вторник, 29 сентября 2026, 05:00, расшифровка", ChatPrompts.label(c, zone))
    }

    @Test fun citationsParsed() {
        assertEquals(setOf(1, 3, 4), ChatPrompts.citations("Ты обещал позвонить [1]. Ещё [3, 4]."))
        assertEquals(emptySet<Int>(), ChatPrompts.citations("нет ссылок"))
    }
}
