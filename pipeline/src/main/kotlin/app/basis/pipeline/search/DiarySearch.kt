package app.basis.pipeline.search

import app.basis.core.common.AppLog
import app.basis.core.database.ChunkDao
import app.basis.core.database.ChunkEntity
import app.basis.core.database.FtsIndex
import app.basis.ml.llm.Embedder
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHit(val chunk: ChunkEntity, val score: Double, val viaText: Boolean, val viaVector: Boolean)

data class SearchResult(val hits: List<SearchHit>, val range: DayRange?, val stems: List<String>, val tookMs: Long)

/**
 * Hybrid retrieval: FTS4 keywords (names, rare words) + BGE-M3 vectors (meaning, paraphrases),
 * merged with reciprocal rank fusion; a date phrase in the question narrows the day range.
 * Vector scan is brute force over int8 vectors (1 KB each) — ~50k chunks/year scan in well under a second.
 */
@Singleton
class DiarySearch @Inject constructor(
    private val chunks: ChunkDao,
    private val fts: FtsIndex,
) {
    suspend fun search(question: String, today: LocalDate, embedder: Embedder?, limit: Int = 12): SearchResult {
        val t0 = System.currentTimeMillis()
        val hints = DateHints.parse(question, today)
        val stems = FtsQuery.stems(question, hints.dateWords)
        var result = searchIn(question, stems, hints.range, embedder, limit)
        // Too narrow (wrong date guess, or the event was recorded on another day): widen.
        if (hints.range != null && result.size < 3) {
            val wide = searchIn(question, stems, null, embedder, limit)
            result = (result + wide.filter { w -> result.none { it.chunk.id == w.chunk.id } }).take(limit)
        }
        // A date question ("что было во вторник?") is answered best by that day's digest.
        if (hints.range != null) {
            val days = generateSequence(hints.range.from) { it.plusDays(1) }.takeWhile { !it.isAfter(hints.range.to) }.take(14).map(LocalDate::toString).toList()
            val digests = chunks.dayDigests(days).filter { d -> result.none { it.chunk.id == d.id } }
            result = digests.map { SearchHit(it, 1.0, viaText = false, viaVector = false) } + result
        }
        val took = System.currentTimeMillis() - t0
        AppLog.d(TAG, "поиск: ${result.size} фрагм. за $took мс, даты=${hints.range}, слова=$stems, векторы=${embedder != null}")
        return SearchResult(result, hints.range, stems, took)
    }

    private suspend fun searchIn(question: String, stems: List<String>, range: DayRange?, embedder: Embedder?, limit: Int): List<SearchHit> {
        val from = range?.from?.toString() ?: "0000-00-00"
        val to = range?.to?.toString() ?: "9999-99-99"
        val textRanked: List<Long> = FtsQuery.match(stems)?.let { m ->
            runCatching { fts.search(m, from, to, 200) }
                .onFailure { AppLog.w(TAG, "FTS: ${it.message}") }
                .getOrDefault(emptyList())
                .let { hits -> FtsQuery.rank(hits, stems) { it.text } }
                .take(30).map { it.id }
        } ?: emptyList()
        val vectorRanked: List<Long> = embedder?.let { e ->
            val q = e.embed(question)
            val top = TopK(30)
            var after = 0L
            while (true) {
                val page = chunks.vectors(e.modelId, from, to, after, 2000)
                if (page.isEmpty()) break
                for (v in page) if (VectorCodec.dim(v.vector) == q.size) top.offer(v.id, VectorCodec.dot(q, v.vector))
                after = page.last().id
            }
            top.result().map { it.first }
        } ?: emptyList()
        val fused = Rrf.fuse(listOf(textRanked, vectorRanked)).take(limit)
        val byId = chunks.byIds(fused.map { it.first }).associateBy { it.id }
        val textSet = textRanked.toSet()
        val vecSet = vectorRanked.toSet()
        return fused.mapNotNull { (id, score) -> byId[id]?.let { SearchHit(it, score, id in textSet, id in vecSet) } }
    }

    private companion object {
        const val TAG = "Search"
    }
}
