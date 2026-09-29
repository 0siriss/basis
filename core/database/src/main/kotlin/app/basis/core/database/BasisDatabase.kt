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

@Database(entities = [TranscriptEntity::class, SummaryEntity::class], version = 2, exportSchema = true)
abstract class BasisDatabase : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao
    abstract fun summaries(): SummaryDao

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
            .addMigrations(BasisDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun transcripts(db: BasisDatabase): TranscriptDao = db.transcripts()

    @Provides
    fun summaries(db: BasisDatabase): SummaryDao = db.summaries()
}
