package app.basis.pipeline.summary

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model answer is cut at the token limit (seen on device: day digest at exactly 700 tokens). */
class DigestRepairTest {
    private val full = """{"summary":"Обсуждали отпуск","events":["встреча в 10","обед"],"agreements":["позвонить Саше"],"tasks":[],"ideas":["купить палатку"],"mood":"бодрое"}"""

    @Test fun completeJsonStillParses() {
        val d = Digest.parse("текст $full ещё текст")
        assertEquals("Обсуждали отпуск", d.summary)
        assertEquals(listOf("встреча в 10", "обед"), d.events)
    }

    @Test fun everyTruncationPointGivesValidDigestOrClearError() {
        // Cut the answer at every position: it must either parse (keeping what was complete) or throw JSONException.
        for (cut in 1 until full.length) {
            val raw = full.substring(0, cut)
            try {
                val d = Digest.parse(raw)
                if (cut > full.indexOf("\"events\"")) assertTrue("cut=$cut", d.summary.startsWith("Обсуждали отпуск"))
            } catch (e: JSONException) {
                assertTrue("cut=$cut should have parsed", cut < full.indexOf("\",\"events\"") - 5)
            }
        }
    }

    @Test fun cutInsideListKeepsFinishedItemsAndPartialOne() {
        val raw = full.substring(0, full.indexOf("обед") + 2)
        val d = Digest.parse(raw)
        assertEquals(listOf("встреча в 10", "об…"), d.events)
        assertTrue(d.agreements.isEmpty())
    }

    @Test fun cutInsideKeyDropsIt() {
        val raw = full.substring(0, full.indexOf("agreements") + 4)
        val d = Digest.parse(raw)
        assertEquals(listOf("встреча в 10", "обед"), d.events)
        assertTrue(d.agreements.isEmpty())
    }

    @Test fun cutInsideSummaryKeepsTextSoFar() {
        val d = Digest.parse("""{"summary":"Обсуждали от""")
        assertEquals("Обсуждали от…", d.summary)
    }

    @Test(expected = JSONException::class)
    fun garbageThrows() {
        Digest.parse("нет тут никакого джейсона")
    }

    @Test fun fallbackDayMergesHours() {
        val s = Summarizer(object : TextLlm {
            override fun countTokens(text: String) = text.length
            override fun complete(system: String, user: String, grammar: String, maxTokens: Int) = error("not used")
        }, 1000)
        val d = s.fallbackDay(
            listOf(
                "10:00–11:00, среда" to Digest(summary = "утро", tasks = listOf("a", "b")),
                "11:00–12:00, среда" to Digest(),
                "12:00–13:00, среда" to Digest(summary = "обед", tasks = listOf("b", "c"), mood = "ок"),
            ),
        )
        assertEquals("10:00–11:00: утро 12:00–13:00: обед", d.summary)
        assertEquals(listOf("a", "b", "c"), d.tasks)
        assertEquals("ок", d.mood)
    }
}
