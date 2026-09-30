package app.basis.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A searchable piece of the diary: a few minutes of transcript, or an hour/day digest rendered as text.
 * Mirrored into the FTS4 table `chunks_fts` by triggers (see [BasisDatabase.createFts]).
 */
@Entity(tableName = "chunks", indices = [Index("day")])
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    /** [TRANSCRIPT], [HOUR] or [DAY]. */
    val kind: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    val text: String,
    /** Hash of kind + text: unchanged chunks keep their row and vector when a day is re-indexed. */
    val hash: String,
    /** Quantized L2-normalized embedding (see VectorCodec) or null until embedded. */
    val vector: ByteArray? = null,
    /** Embedding model id, empty while [vector] is null. */
    val model: String = "",
) {
    companion object {
        const val TRANSCRIPT = "TRANSCRIPT"
        const val HOUR = "HOUR"
        const val DAY = "DAY"
    }
}

/** Per-day marker: the source signature (transcripts + summaries) the day's chunks were built from. */
@Entity(tableName = "index_days")
data class IndexDayEntity(
    @PrimaryKey val day: String,
    val signature: String,
    @ColumnInfo(name = "indexed_ms") val indexedMs: Long,
    val chunks: Int,
)

data class ChunkVector(val id: Long, val day: String, val vector: ByteArray)

data class ChunkHeader(val id: Long, val hash: String)

data class IndexStats(
    val chunks: Int,
    val embedded: Int,
    val days: Int,
)

@Dao
interface ChunkDao {
    @Query("SELECT id, hash FROM chunks WHERE day = :day")
    suspend fun headersByDay(day: String): List<ChunkHeader>

    @Insert
    suspend fun insert(chunks: List<ChunkEntity>): List<Long>

    @Query("DELETE FROM chunks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM chunks WHERE day = :day")
    suspend fun deleteDay(day: String)

    @Query("SELECT * FROM chunks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE day IN (:days) AND kind = 'DAY'")
    suspend fun dayDigests(days: List<String>): List<ChunkEntity>

    /** Rows still to embed with [model] (never embedded, or embedded by another model). */
    @Query("SELECT * FROM chunks WHERE vector IS NULL OR model != :model ORDER BY day DESC, start_ms LIMIT :limit")
    suspend fun pendingEmbeddings(model: String, limit: Int): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks WHERE vector IS NULL OR model != :model")
    suspend fun pendingCount(model: String): Int

    @Query("UPDATE chunks SET vector = :vector, model = :model WHERE id = :id")
    suspend fun setVector(id: Long, vector: ByteArray, model: String)

    /** Keyset pagination over vectors of [model] within [fromDay]..[toDay] (inclusive, yyyy-MM-dd). */
    @Query(
        "SELECT id, day, vector FROM chunks WHERE vector IS NOT NULL AND model = :model AND day BETWEEN :fromDay AND :toDay " +
            "AND id > :afterId ORDER BY id LIMIT :limit",
    )
    suspend fun vectors(model: String, fromDay: String, toDay: String, afterId: Long, limit: Int): List<ChunkVector>

    @Query("SELECT COUNT(*) AS chunks, COALESCE(SUM(vector IS NOT NULL), 0) AS embedded, COUNT(DISTINCT day) AS days FROM chunks")
    fun observeStats(): Flow<IndexStats>

    @Query("SELECT * FROM index_days WHERE day = :day")
    suspend fun indexDay(day: String): IndexDayEntity?

    @Upsert
    suspend fun upsertIndexDay(d: IndexDayEntity)

    @Query("DELETE FROM index_days WHERE day NOT IN (:days)")
    suspend fun deleteIndexDaysExcept(days: List<String>)

    @Query("SELECT DISTINCT day FROM chunks WHERE day NOT IN (:days)")
    suspend fun chunkDaysExcept(days: List<String>): List<String>

    /** Replaces the day's chunk set: keeps rows whose hash survives, deletes the rest, inserts new ones. */
    @Transaction
    suspend fun replaceDay(day: String, keepHashes: Set<String>, insert: List<ChunkEntity>, marker: IndexDayEntity) {
        val stale = headersByDay(day).filter { it.hash !in keepHashes }.map { it.id }
        stale.chunked(500).forEach { delete(it) }
        if (insert.isNotEmpty()) insert(insert)
        upsertIndexDay(marker)
    }
}
