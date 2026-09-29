package app.basis.core.crypto

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** Key for the temporary encrypted audio segment buffer. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SegmentKey

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {
    @Provides
    @Singleton
    @SegmentKey
    fun segmentCipher(): AeadCipher = KeystoreAeadCipher("basis.segments.v1")
}
