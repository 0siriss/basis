package app.basis.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class FtsHit(val id: Long, val day: String, val text: String)

/** Queries the trigger-maintained FTS4 table `chunks_fts` (raw SQL: it is not a Room entity). */
@Singleton
class FtsIndex @Inject constructor(private val db: BasisDatabase) {
    /** [match] is an FTS4 MATCH expression (e.g. `саш* OR обещ*`); days are inclusive `yyyy-MM-dd`. */
    suspend fun search(match: String, fromDay: String, toDay: String, limit: Int): List<FtsHit> = withContext(Dispatchers.IO) {
        val q = SimpleSQLiteQuery(
            "SELECT chunks.id, chunks.day, chunks.text FROM chunks_fts JOIN chunks ON chunks.id = chunks_fts.docid " +
                "WHERE chunks_fts MATCH ? AND chunks.day BETWEEN ? AND ? ORDER BY chunks.start_ms DESC LIMIT ?",
            arrayOf<Any>(match, fromDay, toDay, limit),
        )
        db.openHelper.readableDatabase.query(q).use { c ->
            buildList { while (c.moveToNext()) add(FtsHit(c.getLong(0), c.getString(1), c.getString(2))) }
        }
    }
}
