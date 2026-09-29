package app.basis.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.asrStore: DataStore<Preferences> by preferencesDataStore(name = "asr")

enum class ProcessingMode {
    /** Heavy work only while charging (default). */
    CHARGING_ONLY,
    /** While charging, or right away if the battery level is at least the threshold. */
    CHARGING_OR_BATTERY_ABOVE,
}

data class AsrSettings(
    val modelId: String = "gigaam-v3-rnnt",
    val mode: ProcessingMode = ProcessingMode.CHARGING_ONLY,
    val batteryThreshold: Int = 60,
    /** Whisper: "" = auto-detect, or a language code. */
    val whisperLanguage: String = "",
    val threads: Int = 4,
)

@Singleton
class AsrPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val model = stringPreferencesKey("model")
        val mode = stringPreferencesKey("mode")
        val threshold = intPreferencesKey("battery_threshold")
        val lang = stringPreferencesKey("whisper_lang")
        val threads = intPreferencesKey("threads")
    }

    val settings: Flow<AsrSettings> = context.asrStore.data.map { p ->
        val d = AsrSettings()
        AsrSettings(
            modelId = p[Keys.model] ?: d.modelId,
            mode = p[Keys.mode]?.let { runCatching { ProcessingMode.valueOf(it) }.getOrNull() } ?: d.mode,
            batteryThreshold = p[Keys.threshold] ?: d.batteryThreshold,
            whisperLanguage = p[Keys.lang] ?: d.whisperLanguage,
            threads = p[Keys.threads] ?: d.threads,
        )
    }

    suspend fun current(): AsrSettings = settings.first()

    suspend fun setModel(id: String) { context.asrStore.edit { it[Keys.model] = id } }
    suspend fun setMode(mode: ProcessingMode) { context.asrStore.edit { it[Keys.mode] = mode.name } }
    suspend fun setBatteryThreshold(pct: Int) { context.asrStore.edit { it[Keys.threshold] = pct.coerceIn(10, 100) } }
    suspend fun setWhisperLanguage(lang: String) { context.asrStore.edit { it[Keys.lang] = lang } }
    suspend fun setThreads(n: Int) { context.asrStore.edit { it[Keys.threads] = n.coerceIn(1, 8) } }
}
