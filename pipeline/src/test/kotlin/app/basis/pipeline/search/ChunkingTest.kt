package app.basis.pipeline.search

import app.basis.core.database.ChunkEntity
import app.basis.pipeline.summary.Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChunkingTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 9, 29)
    private fun at(h: Int, m: Int, s: Int = 0) = day.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()
    private fun words(n: Int, w: String = "слово") = List(n) { w }.joinToString(" ")
    private val chunker = DiaryChunker(zone, targetWords = 100, maxWords = 150)

    private fun chunk(segments: List<Segment>, hours: List<HourDigest> = emptyList(), dayDigest: Digest? = null) =
        chunker.chunk(segments, hours, dayDigest, at(0, 0), at(23, 59))

    @Test fun packsSegmentsUpToTarget() {
        val segs = (0 until 10).map { Segment(at(10, it), at(10, it, 20), words(30)) }
        val out = chunk(segs)
        // 30+30+30+30 = 120 ≥ 100 → flush; 10 segments → 4+4+2.
        assertEquals(listOf(4, 4, 2), out.map { it.text.lines().size })
        assertTrue(out.all { it.kind == ChunkEntity.TRANSCRIPT })
        assertEquals(at(10, 0), out[0].startMs)
        assertEquals(at(10, 3, 20), out[0].endMs)
        assertTrue(out[0].text.startsWith("[10:00] слово"))
    }

    @Test fun neverCrossesClockHour() {
        val segs = listOf(Segment(at(10, 58), at(10, 59), "до часа"), Segment(at(11, 1), at(11, 2), "после часа"))
        val out = chunk(segs)
        assertEquals(2, out.size)
        assertEquals("[10:58] до часа", out[0].text)
        assertEquals("[11:01] после часа", out[1].text)
    }

    @Test fun longSegmentIsSplit() {
        val out = chunk(listOf(Segment(at(12, 0), at(12, 1), words(400))))
        assertEquals(listOf(150, 150, 100), out.map { it.text.removePrefix("[12:00] ").split(" ").size })
        assertTrue(out.all { it.text.split(" ").size - 1 <= 150 })
    }

    @Test fun doesNotExceedMaxWhenAdding() {
        val segs = listOf(Segment(at(9, 0), at(9, 1), words(90)), Segment(at(9, 2), at(9, 3), words(90)))
        val out = chunk(segs)
        // 90 < 100, but 90 + 90 > 150 → two chunks.
        assertEquals(2, out.size)
    }

    @Test fun blankSegmentsAreSkipped() {
        assertTrue(chunk(listOf(Segment(at(9, 0), at(9, 1), "   "))).isEmpty())
    }

    @Test fun digestsBecomeChunks() {
        val hour = HourDigest(at(14, 0), Digest(summary = "обсуждали отпуск", agreements = listOf("Саше: позвонить в четверг")))
        val out = chunk(emptyList(), listOf(hour, HourDigest(at(15, 0), Digest())), Digest(summary = "спокойный день", mood = "ровное"))
        assertEquals(listOf(ChunkEntity.HOUR, ChunkEntity.DAY), out.map { it.kind })
        assertEquals("Сводка за 14:00–15:00: обсуждали отпуск\nДоговорённости и обещания: Саше: позвонить в четверг", out[0].text)
        assertEquals("Итог дня: спокойный день\nНастроение: ровное", out[1].text)
        assertEquals(at(0, 0), out[1].startMs)
    }

    @Test fun hashIsStableAndKindSensitive() {
        val a = ChunkDraft(ChunkEntity.TRANSCRIPT, 1, 2, "текст")
        assertEquals(a.hash, ChunkDraft(ChunkEntity.TRANSCRIPT, 5, 6, "текст").hash)
        assertNotEquals(a.hash, ChunkDraft(ChunkEntity.HOUR, 1, 2, "текст").hash)
        assertEquals(40, a.hash.length)
    }

    @Test fun reindexingSameDayGivesSameHashes() {
        val segs = (0 until 7).map { Segment(at(16, it * 5), at(16, it * 5, 30), words(25, "тест$it")) }
        assertEquals(chunk(segs).map { it.hash }, chunk(segs.shuffled()).map { it.hash })
        // Appending speech changes only the last chunk of that hour.
        val more = chunk(segs + Segment(at(16, 50), at(16, 51), "ещё")).map { it.hash }
        val before = chunk(segs).map { it.hash }
        assertEquals(before.dropLast(1), more.dropLast(1))
    }

    @Test fun embeddingTextDropsTimestamps() {
        val c = ChunkEntity(day = "2026-09-29", kind = ChunkEntity.TRANSCRIPT, startMs = 0, endMs = 0, text = "[10:00] привет\n[10:01] как дела", hash = "")
        assertEquals("привет\nкак дела", DiaryIndexer.embeddingText(c))
    }
}
