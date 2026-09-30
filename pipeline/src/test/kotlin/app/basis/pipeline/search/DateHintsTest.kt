package app.basis.pipeline.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DateHintsTest {
    private val wed = LocalDate.of(2026, 9, 30) // Wednesday

    private fun range(q: String, today: LocalDate = wed) = DateHints.parse(q, today).range

    private fun day(d: Int, m: Int = 9, y: Int = 2026) = LocalDate.of(y, m, d)

    @Test fun relativeDays() {
        assertEquals(DayRange(day(29), day(29)), range("Что было вчера?"))
        assertEquals(DayRange(day(28), day(28)), range("позавчера утром"))
        assertEquals(DayRange(wed, wed), range("что я делал сегодня"))
    }

    @Test fun weekdayMeansMostRecent() {
        assertEquals(DayRange(day(29), day(29)), range("что я обещал Саше во вторник?"))
        assertEquals(DayRange(day(28), day(28)), range("в понедельник"))
        // Said on Wednesday: last Wednesday or today.
        assertEquals(DayRange(day(23), day(30)), range("в среду"))
        assertEquals(DayRange(day(25), day(25)), range("в пятницу"))
    }

    @Test fun pastWeekday() {
        // "прошлый вторник" = Tuesday of the previous calendar week.
        assertEquals(DayRange(day(22), day(22)), range("в прошлый вторник"))
        assertEquals(DayRange(day(25), day(25)), range("в прошлую пятницу"))
    }

    @Test fun explicitDates() {
        assertEquals(DayRange(day(29), day(29)), range("что было 29 сентября"))
        assertEquals(DayRange(day(3), day(3)), range("3 сентября"))
        // In the future → last year.
        assertEquals(DayRange(day(5, 12, 2025), day(5, 12, 2025)), range("5 декабря"))
        assertEquals(DayRange(day(1, 5), day(1, 5)), range("1 мая"))
        assertEquals(DayRange(day(29), day(29)), range("что было 29.09"))
        assertEquals(DayRange(day(1, 1, 2025), day(1, 1, 2025)), range("01.01.2025"))
    }

    @Test fun weeksAndMonths() {
        assertEquals(DayRange(day(21), day(27)), range("на прошлой неделе"))
        assertEquals(DayRange(day(28), day(30)), range("на этой неделе"))
        assertEquals(DayRange(day(1, 8), day(31, 8)), range("в прошлом месяце"))
        assertEquals(DayRange(day(26), day(27)), range("в выходные"))
    }

    @Test fun agoForms() {
        assertEquals(DayRange(day(27), day(27)), range("3 дня назад"))
        assertEquals(DayRange(day(28), day(28)), range("два дня назад"))
        assertEquals(DayRange(day(21), day(25)), range("неделю назад"))
    }

    @Test fun unionOfSeveral() {
        assertEquals(DayRange(day(28), day(29)), range("в понедельник или во вторник"))
    }

    @Test fun noDate() {
        assertNull(range("что я обещал Саше"))
        assertNull(range("какие идеи были про проект"))
    }

    @Test fun dateWordsAreReported() {
        val r = DateHints.parse("что я обещал Саше во вторник", wed)
        assertTrue("вторник" in r.dateWords)
        val r2 = DateHints.parse("что было 29 сентября", wed)
        assertTrue("29" in r2.dateWords && "сентября" in r2.dateWords)
    }
}
