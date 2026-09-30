package app.basis.pipeline.search

/**
 * Keyword side of the hybrid search. SQLite FTS4 has no Russian stemmer, so each query word is cut to a
 * crude stem and searched as a prefix: "Саше" → `саш*` (Саша, Сашей), "обещал" → `обещ*` (обещать, обещание).
 */
object FtsQuery {
    private val STOP = setOf(
        "а", "в", "во", "и", "к", "ко", "о", "об", "обо", "с", "со", "у", "на", "по", "за", "из", "от", "до", "для", "при", "про", "под", "над",
        "не", "ни", "но", "же", "ли", "бы", "то", "что", "чем", "как", "где", "куда", "когда", "кто", "кого", "кому", "какой", "какая", "какие",
        "какое", "каких", "какую", "сколько", "почему", "зачем", "это", "этот", "эта", "эти", "тот", "та", "те", "там", "тут", "здесь",
        "я", "мне", "меня", "мной", "мы", "нас", "нам", "ты", "тебе", "тебя", "вы", "вам", "вас", "он", "она", "оно", "они", "его", "ее", "их",
        "мой", "моя", "мои", "мое", "свой", "своей", "был", "была", "было", "были", "есть", "быть", "будет", "еще", "уже", "все", "всё", "всех",
        "или", "если", "так", "также", "только", "очень", "может", "можно", "нужно", "надо", "день", "дня", "дне",
        "расскажи", "скажи", "напомни", "покажи", "найди", "говорил", "говорила", "говорили", "сказал", "сказала", "обсуждали",
        "the", "a", "an", "of", "to", "in", "on", "and", "or", "is", "was", "what", "when", "who", "did", "i", "my",
    )

    /** Distinct stems of the meaningful words, without [exclude] (e.g. date words). */
    fun stems(question: String, exclude: Set<String> = emptySet()): List<String> =
        Regex("[\\p{L}\\d]+").findAll(question.lowercase().replace('ё', 'е')).map { it.value }
            .filter { it !in STOP && it !in exclude && (it.length >= 3 || it.any(Char::isDigit)) }
            .map(::stem)
            .distinct()
            .toList()

    fun stem(w: String): String = when {
        w.any(Char::isDigit) -> w
        w.length >= 7 -> w.dropLast(2)
        w.length >= 5 -> w.dropLast(2).takeIf { it.length >= 4 } ?: w.dropLast(1)
        w.length == 4 -> w.dropLast(1)
        else -> w
    }

    /** FTS4 MATCH expression (`a* OR b*`), or null if nothing to search for. */
    fun match(stems: List<String>): String? = stems.takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "$it*" }

    /** Orders hits by how many distinct stems they contain; ties keep their order (newest first). */
    fun <T> rank(hits: List<T>, stems: List<String>, text: (T) -> String): List<T> =
        hits.withIndex().sortedWith(
            compareByDescending<IndexedValue<T>> { h ->
                val words = Regex("[\\p{L}\\d]+").findAll(text(h.value).lowercase().replace('ё', 'е')).map { it.value }.toSet()
                stems.count { s -> words.any { it.startsWith(s) } }
            }.thenBy { it.index },
        ).map { it.value }
}
