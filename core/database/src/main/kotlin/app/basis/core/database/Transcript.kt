package app.basis.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Recognized text of one speech segment. The audio itself is deleted right after recognition. */
@Entity(tableName = "transcripts", indices = [Index("day"), Index("start_ms")])
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    /** Local date `yyyy-MM-dd` for grouping by day. */
    val day: String,
    val text: String,
    /** ASR model id, e.g. `gigaam-v3-rnnt`. */
    val model: String,
    /** Detected/forced language (Whisper) or empty. */
    val lang: String = "",
    @ColumnInfo(name = "created_ms") val createdMs: Long = System.currentTimeMillis(),
)

data class DaySummaryRow(
    val day: String,
    val segments: Int,
    @ColumnInfo(name = "speech_ms") val speechMs: Long,
    @ColumnInfo(name = "first_ms") val firstMs: Long,
    @ColumnInfo(name = "last_ms") val lastMs: Long,
)

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insert(t: TranscriptEntity): Long

    @Query("SELECT day, COUNT(*) AS segments, SUM(end_ms - start_ms) AS speech_ms, MIN(start_ms) AS first_ms, MAX(end_ms) AS last_ms FROM transcripts GROUP BY day ORDER BY day DESC")
    fun observeDays(): Flow<List<DaySummaryRow>>

    @Query("SELECT * FROM transcripts WHERE day = :day ORDER BY start_ms")
    fun observeDay(day: String): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE day = :day ORDER BY start_ms")
    suspend fun byDay(day: String): List<TranscriptEntity>

    @Query("SELECT COUNT(*) FROM transcripts")
    suspend fun count(): Int

    @Query("DELETE FROM transcripts WHERE day < :day")
    suspend fun deleteBefore(day: String): Int
}
