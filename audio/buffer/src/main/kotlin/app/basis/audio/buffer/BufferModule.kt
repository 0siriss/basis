package app.basis.audio.buffer

import android.content.Context
import app.basis.core.crypto.AeadCipher
import app.basis.core.crypto.SegmentKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BufferModule {
    /** Upper bound for pending (not yet transcribed) audio: ~2 GB ≈ 17 h of speech at 16 kHz PCM16. */
    private const val MAX_BUFFER_BYTES = 2L * 1024 * 1024 * 1024

    @Provides
    @Singleton
    fun segmentStore(@ApplicationContext context: Context, @SegmentKey cipher: AeadCipher): SegmentStore =
        // noBackupFilesDir: excluded from any backup/transfer.
        SegmentStore(File(context.noBackupFilesDir, "segments"), cipher, MAX_BUFFER_BYTES)
}
