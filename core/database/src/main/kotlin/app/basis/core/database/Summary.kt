package app.basis.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** LLM digest of an hour (period_start_ms = hour start) or a whole day (period_start_ms = 0). */
@Entity(tableName = "summaries", primaryKeys = ["day", "period_start_ms"])
data class SummaryEntity(
    val day: String,
    @ColumnInfo(name = "period_start_ms") val periodStartMs: Long,
    /** "HOUR" or "DAY". */
    val kind: String,
    /** Digest JSON: summary, events, agreements, tasks, ideas, mood. */
    val json: String,
    val model: String,
    /** Transcripts the digest was built from — to detect when it is stale. */
    @ColumnInfo(name = "source_count") val sourceCount: Int,
    @ColumnInfo(name = "source_max_created_ms") val sourceMaxCreatedMs: Long,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "gen_ms") val genMs: Long,
) {
    companion object {
        const val HOUR = "HOUR"
        const val DAY = "DAY"
    }
}

data class DayStat(
    val day: String,
    val count: Int,
    @ColumnInfo(name = "max_created_ms") val maxCreatedMs: Long,
)

@Dao
interface SummaryDao {
    @Upsert
    suspend fun upsert(s: SummaryEntity)

    @Query("SELECT * FROM summaries WHERE day = :day ORDER BY period_start_ms")
    suspend fun byDay(day: String): List<SummaryEntity>

    @Query("SELECT * FROM summaries WHERE day = :day ORDER BY period_start_ms")
    fun observeDay(day: String): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries WHERE kind = 'DAY' ORDER BY day DESC")
    fun observeDaySummaries(): Flow<List<SummaryEntity>>

    @Query("SELECT day, COUNT(*) AS count, MAX(created_ms) AS max_created_ms FROM transcripts GROUP BY day ORDER BY day")
    suspend fun transcriptDayStats(): List<DayStat>

    @Query("DELETE FROM summaries WHERE day = :day AND kind = 'HOUR' AND period_start_ms NOT IN (:keep)")
    suspend fun deleteStaleHours(day: String, keep: List<Long>)
}
