package app.basis.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [TranscriptEntity::class], version = 1, exportSchema = true)
abstract class BasisDatabase : RoomDatabase() {
    abstract fun transcripts(): TranscriptDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BasisDatabase =
        // Stored in the app's private, file-based-encrypted storage; excluded from backups (see app manifest).
        Room.databaseBuilder(context, BasisDatabase::class.java, "basis.db").build()

    @Provides
    fun transcripts(db: BasisDatabase): TranscriptDao = db.transcripts()
}
