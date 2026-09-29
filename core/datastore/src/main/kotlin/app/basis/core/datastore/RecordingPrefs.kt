package app.basis.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recordingStore: DataStore<Preferences> by preferencesDataStore(name = "recording")

data class RecordingSettings(
    /** User wants recording on (survives process death and reboot). */
    val enabled: Boolean = false,
    /** Paused by the user until they resume manually. */
    val paused: Boolean = false,
    /** Private mode: no recording until this epoch-ms; 0 = off. */
    val privateUntilMs: Long = 0L,
    /** Hold a partial wake lock while recording (diagnostics; off by default). */
    val holdWakeLock: Boolean = false,
)

@Singleton
class RecordingPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val paused = booleanPreferencesKey("paused")
        val privateUntil = longPreferencesKey("private_until")
        val wakeLock = booleanPreferencesKey("hold_wake_lock")
    }

    val settings: Flow<RecordingSettings> = context.recordingStore.data.map { p ->
        RecordingSettings(
            enabled = p[Keys.enabled] ?: false,
            paused = p[Keys.paused] ?: false,
            privateUntilMs = p[Keys.privateUntil] ?: 0L,
            holdWakeLock = p[Keys.wakeLock] ?: false,
        )
    }

    suspend fun current(): RecordingSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean) = context.recordingStore.edit {
        it[Keys.enabled] = enabled
        if (!enabled) {
            it[Keys.paused] = false
            it[Keys.privateUntil] = 0L
        }
    }

    suspend fun setPaused(paused: Boolean) = context.recordingStore.edit {
        it[Keys.paused] = paused
        if (!paused) it[Keys.privateUntil] = 0L
    }

    suspend fun setPrivateUntil(epochMs: Long) = context.recordingStore.edit { it[Keys.privateUntil] = epochMs }

    suspend fun setHoldWakeLock(hold: Boolean) = context.recordingStore.edit { it[Keys.wakeLock] = hold }
}
