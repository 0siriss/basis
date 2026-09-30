package app.basis.pipeline.search

import app.basis.core.common.AppLog
import app.basis.core.database.ChunkDao
import app.basis.core.database.ChunkEntity
import app.basis.core.database.IndexDayEntity
import app.basis.core.database.SummaryDao
import app.basis.core.database.SummaryEntity
import app.basis.core.database.TranscriptDao
import app.basis.ml.llm.Embedder
import app.basis.pipeline.summary.Digest
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the search index in step with transcripts and summaries. Two phases:
 * 1. [syncText] — cheap, no model: re-chunks the days whose sources changed (FTS works right away);
 * 2. [embedPending] — computes vectors for new chunks (needs the embedding model).
 * Callers hold [app.basis.pipeline.TranscriptionStatus.lock].
 */
@Singleton
class DiaryIndexer @Inject constructor(
    private val transcripts: TranscriptDao,
    private val summaries: SummaryDao,
    private val chunks: ChunkDao,
) {
    data class SyncReport(val days: Int, val added: Int, val removed: Int)

    suspend fun syncText(zone: ZoneId = ZoneId.systemDefault()): SyncReport {
        val t = summaries.transcriptDayStats().associateBy { it.day }
        val s = summaries.summaryDayStats().associateBy { it.day }
        val allDays = (t.keys + s.keys).sorted()
        var days = 0
        var added = 0
        var removed = 0
        for (day in allDays) {
            val signature = "t${t[day]?.count ?: 0}:${t[day]?.maxCreatedMs ?: 0}|s${s[day]?.count ?: 0}:${s[day]?.maxCreatedMs ?: 0}"
            if (chunks.indexDay(day)?.signature == signature) continue
            val date = LocalDate.parse(day)
            val sums = summaries.byDay(day)
            val drafts = DiaryChunker(zone).chunk(
                segments = transcripts.byDay(day).map { Segment(it.startMs, it.endMs, it.text) },
                hours = sums.filter { it.kind == SummaryEntity.HOUR }.mapNotNull { e -> parse(e.json)?.let { HourDigest(e.periodStartMs, it) } },
                day = sums.firstOrNull { it.kind == SummaryEntity.DAY }?.let { parse(it.json) },
                dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            val existing = chunks.headersByDay(day).map { it.hash }.toSet()
            val keep = drafts.map { it.hash }.toSet()
            val insert = drafts.filter { it.hash !in existing }.map { d ->
                ChunkEntity(day = day, kind = d.kind, startMs = d.startMs, endMs = d.endMs, text = d.text, hash = d.hash)
            }
            chunks.replaceDay(day, keep, insert, IndexDayEntity(day, signature, System.currentTimeMillis(), drafts.size))
            days++
            added += insert.size
            removed += existing.count { it !in keep }
        }
        // Days whose sources are gone entirely (retention, manual deletion).
        for (day in chunks.chunkDaysExcept(allDays)) chunks.deleteDay(day)
        chunks.deleteIndexDaysExcept(allDays)
        if (days > 0) AppLog.i(TAG, "индекс: обновлено дней $days, фрагментов +$added −$removed")
        return SyncReport(days, added, removed)
    }

    suspend fun pendingCount(modelId: String): Int = chunks.pendingCount(modelId)

    /** Embeds chunks without a vector from [embedder]'s model. Returns how many were embedded. */
    suspend fun embedPending(embedder: Embedder, shouldStop: suspend () -> Boolean, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val total = chunks.pendingCount(embedder.modelId)
        var done = 0
        var ms = 0L
        while (!shouldStop()) {
            val batch = chunks.pendingEmbeddings(embedder.modelId, 32)
            if (batch.isEmpty()) break
            for (c in batch) {
                if (shouldStop()) break
                val t0 = System.currentTimeMillis()
                val v = embedder.embed(embeddingText(c))
                ms += System.currentTimeMillis() - t0
                chunks.setVector(c.id, VectorCodec.encode(v), embedder.modelId)
                done++
                progress(done, total)
            }
        }
        if (done > 0) AppLog.i(TAG, "эмбеддинги: $done фрагм. за ${ms / 1000} с (%.0f мс/фрагм.)".format(ms.toDouble() / done))
        return done
    }

    private fun parse(json: String): Digest? = runCatching { Digest.parse(json) }.getOrNull()

    companion object {
        private const val TAG = "Index"

        /** Timestamps like "[14:05]" carry no meaning for the vector; strip them. */
        fun embeddingText(c: ChunkEntity): String = c.text.replace(Regex("(?m)^\\[\\d{2}:\\d{2}] "), "")
    }
}
