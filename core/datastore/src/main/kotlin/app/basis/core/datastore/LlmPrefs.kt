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

private val Context.llmStore: DataStore<Preferences> by preferencesDataStore(name = "llm")

data class LlmSettings(
    val modelId: String = "qwen3.5-4b-q4km",
    val threads: Int = 6,
    /** 4096 is enough for hour chunks (map-reduce) and halves KV/compute memory vs 8192. */
    val contextSize: Int = 4096,
    /** Low-memory mode: don't repack weights (slower, but ~half the RAM). */
    val lowMemory: Boolean = false,
)

@Singleton
class LlmPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val model = stringPreferencesKey("model")
        val threads = intPreferencesKey("threads")
        val ctx = intPreferencesKey("context")
        val lowMem = androidx.datastore.preferences.core.booleanPreferencesKey("low_memory")
    }

    val settings: Flow<LlmSettings> = context.llmStore.data.map { p ->
        val d = LlmSettings()
        LlmSettings(p[Keys.model] ?: d.modelId, p[Keys.threads] ?: d.threads, p[Keys.ctx] ?: d.contextSize, p[Keys.lowMem] ?: d.lowMemory)
    }

    suspend fun current(): LlmSettings = settings.first()
    suspend fun setModel(id: String) { context.llmStore.edit { it[Keys.model] = id } }
    suspend fun setThreads(n: Int) { context.llmStore.edit { it[Keys.threads] = n.coerceIn(1, 8) } }
    suspend fun setContextSize(n: Int) { context.llmStore.edit { it[Keys.ctx] = n.coerceIn(2048, 32768) } }
    suspend fun setLowMemory(on: Boolean) { context.llmStore.edit { it[Keys.lowMem] = on } }
}
