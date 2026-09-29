package app.basis.pipeline.summary

/** Greedy packing of transcript lines into chunks that fit a token budget (map step of map-reduce). */
class Chunker(private val countTokens: (String) -> Int, private val budget: Int) {
    init {
        require(budget > 0)
    }

    fun chunk(lines: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        var curTokens = 0
        for (line in lines.flatMap(::splitOversized)) {
            val t = countTokens(line) + 1 // + newline
            if (curTokens + t > budget && cur.isNotEmpty()) {
                out += cur
                cur = mutableListOf()
                curTokens = 0
            }
            cur += line
            curTokens += t
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** A single line longer than the budget (a 28-s monologue on a tiny budget) is split by words. */
    private fun splitOversized(line: String): List<String> {
        if (countTokens(line) + 1 <= budget) return listOf(line)
        val parts = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in line.split(' ')) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isNotEmpty() && countTokens(candidate) + 1 > budget) {
                parts += cur.toString()
                cur = StringBuilder(w)
            } else {
                cur = StringBuilder(candidate)
            }
        }
        if (cur.isNotEmpty()) parts += cur.toString()
        return parts
    }
}
