package app.basis.pipeline.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class SearchLogicTest {
    private fun normalized(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun randomUnit(rnd: Random, dim: Int) = normalized(FloatArray(dim) { rnd.nextFloat() * 2 - 1 })

    @Test fun vectorCodecRoundTripIsClose() {
        val rnd = Random(1)
        repeat(20) {
            val v = randomUnit(rnd, 1024)
            val blob = VectorCodec.encode(v)
            assertEquals(1028, blob.size)
            val back = VectorCodec.decode(blob)
            val maxErr = v.indices.maxOf { abs(v[it] - back[it]) }
            assertTrue("max error $maxErr", maxErr < v.maxOf { abs(it) } / 127f)
        }
    }

    @Test fun quantizedDotMatchesFloatDot() {
        val rnd = Random(2)
        repeat(50) {
            val a = randomUnit(rnd, 1024)
            val b = randomUnit(rnd, 1024)
            val exact = a.indices.sumOf { (a[it] * b[it]).toDouble() }
            val approx = VectorCodec.dot(a, VectorCodec.encode(b))
            assertEquals(exact, approx.toDouble(), 0.01)
        }
        val v = randomUnit(rnd, 64)
        assertEquals(1.0, VectorCodec.dot(v, VectorCodec.encode(v)).toDouble(), 0.01)
    }

    @Test fun zeroVectorDoesNotBreak() {
        val blob = VectorCodec.encode(FloatArray(8))
        assertArrayEquals(FloatArray(8), VectorCodec.decode(blob), 0f)
    }

    @Test fun cosineTopKFindsNearest() {
        val rnd = Random(3)
        val corpus = (1L..500L).associateWith { randomUnit(rnd, 256) }
        val target = 137L
        // Query = target plus small noise.
        val q = normalized(FloatArray(256) { corpus.getValue(target)[it] + (rnd.nextFloat() - 0.5f) * 0.02f })
        val top = TopK(5)
        corpus.forEach { (id, v) -> top.offer(id, VectorCodec.dot(q, VectorCodec.encode(v))) }
        val result = top.result()
        assertEquals(5, result.size)
        assertEquals(target, result.first().first)
        assertTrue(result.zipWithNext().all { (a, b) -> a.second >= b.second })
    }

    @Test fun rrfPrefersItemsInBothLists() {
        val fused = Rrf.fuse(listOf(listOf(1L, 2L, 3L), listOf(3L, 4L, 1L)))
        assertEquals(listOf(1L, 3L), fused.take(2).map { it.first })
        assertEquals(setOf(1L, 2L, 3L, 4L), fused.map { it.first }.toSet())
        assertEquals(1.0 / 61 + 1.0 / 63, fused.first().second, 1e-9)
    }

    @Test fun rrfHandlesEmptyLists() {
        assertEquals(listOf(5L, 6L), Rrf.fuse(listOf(emptyList(), listOf(5L, 6L))).map { it.first })
        assertTrue(Rrf.fuse(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test fun ftsStemsDropStopAndDateWords() {
        val hints = DateHints.parse("Что я обещал Саше во вторник?", java.time.LocalDate.of(2026, 9, 30))
        val stems = FtsQuery.stems("Что я обещал Саше во вторник?", hints.dateWords)
        assertEquals(listOf("обещ", "саш"), stems)
        assertEquals("обещ* OR саш*", FtsQuery.match(stems))
        assertNull(FtsQuery.match(FtsQuery.stems("что я где")))
    }

    @Test fun ftsStemmingIsPrefixOfInflections() {
        for ((a, b) in listOf("саше" to "сашей", "проекта" to "проектом", "встречу" to "встреча", "обещал" to "обещание")) {
            assertTrue("$a vs $b", b.startsWith(FtsQuery.stem(a)))
        }
        assertEquals("2026", FtsQuery.stem("2026"))
        assertEquals(listOf("елк"), FtsQuery.stems("ёлка")) // ё folded like in the index
    }

    @Test fun ftsRankByMatchedStems() {
        val hits = listOf("купить хлеб", "саша обещал позвонить", "обещал маме")
        val ranked = FtsQuery.rank(hits, listOf("обещ", "саш")) { it }
        assertEquals("саша обещал позвонить", ranked.first())
        assertEquals("обещал маме", ranked[1])
    }
}
