package app.basis.pipeline.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONException

class SummarizerTest {
    /** ~1 token per word; records every prompt; answers with a digest naming the call number. */
    private class FakeLlm : TextLlm {
        val prompts = mutableListOf<String>()
        override fun countTokens(text: String) = text.split(' ', '\n').count { it.isNotBlank() }
        override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
            prompts += user
            val n = prompts.size
            return "мусор до {\"summary\":\"s$n\",\"events\":[\"e$n\"],\"agreements\":[],\"tasks\":[\"t$n\"],\"ideas\":[],\"mood\":\"ok\"} и после"
        }
    }

    private fun lines(n: Int, words: Int = 10) = (1..n).map { i -> Line("10:%02d".format(i % 60), (1..words).joinToString(" ") { "слово$it" }) }

    @Test
    fun smallHourIsOneCallWithoutReduce() {
        val llm = FakeLlm()
        val d = Summarizer(llm, inputBudget = 1000).summarizePeriod("10:00–11:00", lines(5))
        assertEquals(1, llm.prompts.size)
        assertEquals("s1", d.summary)
        assertTrue(llm.prompts[0].contains("[10:01] слово1"))
    }

    @Test
    fun longHourIsMappedThenReduced() {
        val llm = FakeLlm()
        val calls = mutableListOf<String>()
        // 30 lines × 12 tokens = 360 tokens; budget 100 → 4 chunks → 4 map + 1 reduce.
        val d = Summarizer(llm, inputBudget = 100, onCall = { calls += it }).summarizePeriod("10:00–11:00", lines(30))
        assertEquals(listOf("map", "map", "map", "map", "reduce"), calls)
        assertEquals("s5", d.summary)
        assertTrue(llm.prompts.last().contains("Часть 4:"))
        llm.prompts.dropLast(1).forEach { assertTrue(llm.countTokens(it) < 100 + 120) }
    }

    @Test
    fun dayMergesHourDigestsHierarchicallyWhenTooBig() {
        val llm = FakeLlm()
        val calls = mutableListOf<String>()
        val hour = Digest(summary = (1..30).joinToString(" ") { "w$it" }, events = listOf("встреча"))
        val hours = (8..20).map { "%02d:00".format(it) to hour }
        Summarizer(llm, inputBudget = 120, onCall = { calls += it }).summarizeDay("29 сентября", hours)
        assertTrue(calls.count { it == "reduce" } >= 2)
        assertEquals("day", calls.last())
    }

    @Test
    fun emptyInputsProduceEmptyDigestWithoutCalls() {
        val llm = FakeLlm()
        val s = Summarizer(llm, 100)
        assertTrue(s.summarizePeriod("x", emptyList()).isEmpty)
        assertTrue(s.summarizeDay("x", listOf("10:00" to Digest())).isEmpty)
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun chunkerRespectsBudgetAndSplitsOversizedLines() {
        val count = { s: String -> s.split(' ').size }
        val chunks = Chunker(count, 10).chunk(listOf("a b c", "d e f g h i j k l m n o p", "q"))
        chunks.forEach { c -> assertTrue(c.sumOf { count(it) + 1 } <= 10 || c.size == 1) }
        assertEquals("a b c d e f g h i j k l m n o p q".split(' '), chunks.flatten().flatMap { it.split(' ') })
    }

    @Test
    fun digestJsonRoundTripAndDedup() {
        val d = Digest("итог", listOf("a", "a", "b"), listOf("Саша обещал"), emptyList(), listOf("идея"), "бодрое")
        val back = Digest.parse(d.toJson())
        assertEquals(listOf("a", "b"), back.events)
        assertEquals("бодрое", back.mood)
    }

    @Test(expected = JSONException::class)
    fun garbageIsRejected() {
        Digest.parse("no json here")
    }
}
