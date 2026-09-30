package app.basis.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [TranscriptEntity::class, SummaryEntity::class, ChunkEntity::class, IndexDayEntity::class], version = 3, exportSchema = true)
abstract class BasisDatabase : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao
    abstract fun summaries(): SummaryDao
    abstract fun chunks(): ChunkDao

    companion object {
        /** v2 (stage 4): LLM summaries. Existing transcripts are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `summaries` (`day` TEXT NOT NULL, `period_start_ms` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `json` TEXT NOT NULL, `model` TEXT NOT NULL, `source_count` INTEGER NOT NULL, " +
                        "`source_max_created_ms` INTEGER NOT NULL, `created_ms` INTEGER NOT NULL, `gen_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`day`, `period_start_ms`))",
                )
            }
        }

        /** v3 (stage 5): search index — chunks with vectors, per-day index markers, FTS4 mirror. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `start_ms` INTEGER NOT NULL, `end_ms` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                        "`hash` TEXT NOT NULL, `vector` BLOB, `model` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunks_day` ON `chunks` (`day`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `index_days` (`day` TEXT NOT NULL, `signature` TEXT NOT NULL, " +
                        "`indexed_ms` INTEGER NOT NULL, `chunks` INTEGER NOT NULL, PRIMARY KEY(`day`))",
                )
                createFts(db)
            }
        }

        /**
         * Full-text mirror of `chunks.text`, maintained by triggers (not a Room entity, so Room never
         * touches it). unicode61 folds case for Cyrillic; ё is folded to е on the way in (and in queries).
         */
        fun createFts(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts4(text, tokenize=unicode61)")
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS chunks_fts_ai AFTER INSERT ON chunks BEGIN " +
                    "INSERT INTO chunks_fts(docid, text) VALUES (new.id, replace(replace(new.text, 'ё', 'е'), 'Ё', 'Е')); END",
            )
            db.execSQL("CREATE TRIGGER IF NOT EXISTS chunks_fts_ad AFTER DELETE ON chunks BEGIN DELETE FROM chunks_fts WHERE docid = old.id; END")
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BasisDatabase =
        // Stored in the app's private, file-based-encrypted storage; excluded from backups (see app manifest).
        Room.databaseBuilder(context, BasisDatabase::class.java, "basis.db")
            .addMigrations(BasisDatabase.MIGRATION_1_2, BasisDatabase.MIGRATION_2_3)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = BasisDatabase.createFts(db)
                    override fun onOpen(db: SupportSQLiteDatabase) = BasisDatabase.createFts(db)
                },
            )
            .build()

    @Provides
    fun transcripts(db: BasisDatabase): TranscriptDao = db.transcripts()

    @Provides
    fun summaries(db: BasisDatabase): SummaryDao = db.summaries()

    @Provides
    fun chunks(db: BasisDatabase): ChunkDao = db.chunks()
}
