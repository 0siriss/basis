package app.basis.pipeline.search

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Inclusive day range. */
data class DayRange(val from: LocalDate, val to: LocalDate) {
    fun union(o: DayRange) = DayRange(minOf(from, o.from), maxOf(to, o.to))
    fun contains(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
}

/**
 * Finds a time reference in a Russian question ("вчера", "во вторник", "в прошлую пятницу",
 * "29 сентября", "29.09", "на прошлой неделе", "3 дня назад"…) relative to [today].
 * Several references → their combined span. Returns the range and the words it consumed
 * (so they are not used as search keywords).
 */
object DateHints {
    data class Result(val range: DayRange?, val dateWords: Set<String>)

    private val WEEKDAYS: List<Pair<Regex, DayOfWeek>> = listOf(
        "понедельник[аеу]?" to DayOfWeek.MONDAY,
        "вторник[аеу]?" to DayOfWeek.TUESDAY,
        "сред[аеуы]" to DayOfWeek.WEDNESDAY,
        "четверг[аеу]?" to DayOfWeek.THURSDAY,
        "пятниц[аеуы]" to DayOfWeek.FRIDAY,
        "суббот[аеуы]" to DayOfWeek.SATURDAY,
        "воскресень[еяю]" to DayOfWeek.SUNDAY,
    ).map { (re, d) -> Regex("^$re$") to d }

    private val MONTHS = listOf(
        "январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр",
    )

    private fun month(word: String): Int? {
        // Genitive/nominative/prepositional forms: января, январе, январь; мая, мае, май; марта, марте.
        if (word in setOf("мая", "мае", "май")) return 5
        MONTHS.forEachIndexed { i, stem ->
            if (i != 4 && word.startsWith(stem) && word.length <= stem.length + 2) return i + 1
        }
        return null
    }

    private val PAST = setOf("прошлый", "прошлую", "прошлое", "прошлой", "прошлом", "прошлая", "прошлого")
    private val THIS = setOf("этот", "эту", "это", "этой", "этом", "эта", "этого", "текущей", "текущем")
    private val NUMBER_WORDS = mapOf(
        "один" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
    )

    fun parse(text: String, today: LocalDate): Result {
        val words = Regex("[\\p{L}\\d.]+").findAll(text.lowercase().replace('ё', 'е')).map { it.value.trimEnd('.') }.toList()
        var range: DayRange? = null
        val used = mutableSetOf<String>()
        fun add(r: DayRange, vararg w: String) {
            range = range?.union(r) ?: r
            used += w
        }
        for ((i, w) in words.withIndex()) {
            val prev = words.getOrNull(i - 1) ?: ""
            val next = words.getOrNull(i + 1) ?: ""
            when (w) {
                "сегодня", "сегодняшний", "сегодняшнего", "сегодняшнем" -> add(DayRange(today, today), w)
                "вчера", "вчерашний", "вчерашнего", "вчерашнем" -> today.minusDays(1).let { add(DayRange(it, it), w) }
                "позавчера" -> today.minusDays(2).let { add(DayRange(it, it), w) }
                "недавно", "днях" -> add(DayRange(today.minusDays(6), today), w)
            }
            // "на (этой|прошлой) неделе", "в (этом|прошлом) месяце", "в прошлые выходные"
            if (w.startsWith("недел")) {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                when (prev) {
                    in PAST -> add(DayRange(monday.minusWeeks(1), monday.minusDays(1)), prev, w)
                    in THIS -> add(DayRange(monday, today), prev, w)
                }
            }
            if (w.startsWith("месяц")) {
                val first = today.withDayOfMonth(1)
                when (prev) {
                    in PAST -> add(DayRange(first.minusMonths(1), first.minusDays(1)), prev, w)
                    in THIS -> add(DayRange(first, today), prev, w)
                }
            }
            if (w == "выходные" || w == "выходных") {
                val weekend = today.dayOfWeek.value >= 6
                var sunday = if (weekend) today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)) else today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY))
                if (weekend && prev in PAST) sunday = sunday.minusWeeks(1)
                add(DayRange(sunday.minusDays(1), minOf(sunday, today)), w, *listOfNotNull(prev.takeIf { it in PAST }).toTypedArray())
            }
            // "3 дня назад", "два дня назад", "неделю назад", "месяц назад"
            if (next == "назад" && (w.startsWith("ден") || w.startsWith("дн") || w.startsWith("недел") || w.startsWith("месяц"))) {
                val num = prev.toIntOrNull() ?: NUMBER_WORDS[prev]
                val n = (num ?: 1).toLong()
                val r = when {
                    w.startsWith("недел") -> today.minusWeeks(n).let { DayRange(it.minusDays(2), minOf(it.plusDays(2), today)) }
                    w.startsWith("месяц") -> today.minusMonths(n).let { DayRange(it.minusDays(5), minOf(it.plusDays(5), today)) }
                    else -> today.minusDays(n).let { DayRange(it, it) }
                }
                add(r, w, "назад", *listOfNotNull(prev.takeIf { num != null }).toTypedArray())
            }
            // Weekday: the most recent one before today (said on that weekday itself: last week's or today);
            // "в прошлый вторник" = Tuesday of the previous calendar week.
            WEEKDAYS.firstOrNull { it.first.matches(w) }?.second?.let { dow ->
                val r = if (prev in PAST) {
                    val d = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1).plusDays(dow.value - 1L)
                    DayRange(d, d)
                } else {
                    val last = today.with(TemporalAdjusters.previous(dow))
                    DayRange(last, if (today.dayOfWeek == dow) today else last)
                }
                add(r, w, *listOfNotNull(prev.takeIf { it in PAST || it in THIS }).toTypedArray())
            }
            // "29 сентября" / "29-го сентября" (the hyphen splits "го" off as a word)
            val dayNum = w.toIntOrNull()
            if (dayNum != null && dayNum in 1..31) {
                val monthWord = if (next == "го") words.getOrNull(i + 2) ?: "" else next
                val m = month(monthWord)
                if (m != null) resolve(today, m, dayNum)?.let { add(DayRange(it, it), w, monthWord) }
            }
            // "29.09" / "29.09.2026"
            Regex("^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?$").find(w)?.let { mr ->
                val d = mr.groupValues[1].toInt()
                val m = mr.groupValues[2].toInt()
                val y = mr.groupValues[3].takeIf(String::isNotEmpty)?.toInt()?.let { if (it < 100) 2000 + it else it }
                val date = if (y != null) runCatching { LocalDate.of(y, m, d) }.getOrNull() else resolve(today, m, d)
                if (date != null) add(DayRange(date, date), w)
            }
        }
        return Result(range, used)
    }

    /** Day of [month] in the current year, or the previous one if that date is still ahead. */
    private fun resolve(today: LocalDate, month: Int, day: Int): LocalDate? {
        val d = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (d.isAfter(today)) runCatching { LocalDate.of(today.year - 1, month, day) }.getOrNull() else d
    }
}
