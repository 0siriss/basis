package app.basis.ml.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.basis.core.common.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Owns `filesDir/models/<id>/`. A model is Ready when every file exists and a `.verified` marker lists
 * the SHA-256 of each file (written only after verification). The network is used only here.
 */
@Singleton
class ModelManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val root = File(context.filesDir, "models")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ModelCatalog.all.associate { it.id to MutableStateFlow(initialState(it)) }
    private val jobs = mutableMapOf<String, Job>()

    fun state(spec: ModelSpec): StateFlow<ModelState> = states.getValue(spec.id).asStateFlow()

    fun isReady(spec: ModelSpec): Boolean = state(spec).value == ModelState.Ready

    fun file(spec: ModelSpec, name: String): File = File(dir(spec), name)

    private fun dir(spec: ModelSpec) = File(root, spec.id)
    private fun marker(spec: ModelSpec) = File(dir(spec), ".verified")

    private fun initialState(spec: ModelSpec): ModelState {
        val m = marker(spec)
        if (!m.exists()) return ModelState.Missing
        val verified = m.readLines().toSet()
        val ok = spec.files.all { f -> file(spec, f.name).exists() && verified.any { it.startsWith(f.name + " ") } }
        return if (ok) ModelState.Ready else ModelState.Missing
    }

    @Synchronized
    fun download(spec: ModelSpec) {
        if (jobs[spec.id]?.isActive == true) return
        jobs[spec.id] = scope.launch {
            val st = states.getValue(spec.id)
            try {
                val dir = dir(spec).apply { mkdirs() }
                var doneBefore = 0L
                val lines = mutableListOf<String>()
                for (f in spec.files) {
                    val target = File(dir, f.name)
                    if (target.exists() && target.length() == f.sizeBytes && sha256Of(target) == f.sha256) {
                        AppLog.i(TAG, "${f.name}: уже есть, хэш совпадает")
                    } else {
                        downloadFile(f, target) { bytes -> st.value = ModelState.Downloading(doneBefore + bytes, spec.totalBytes) }
                    }
                    doneBefore += f.sizeBytes
                    lines += "${f.name} ${f.sha256}"
                }
                marker(spec).writeText(lines.joinToString("\n"))
                st.value = ModelState.Ready
                AppLog.i(TAG, "${spec.title}: готова")
            } catch (t: Throwable) {
                AppLog.e(TAG, "${spec.title}: ошибка загрузки", t)
                st.value = ModelState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** HTTP download with resume (Range) into `<name>.part`, verified before the final rename. */
    private fun downloadFile(f: ModelFile, target: File, progress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.exists()) part.length() else 0L
        if (existing > f.sizeBytes) { part.delete(); existing = 0 }
        AppLog.i(TAG, "загрузка ${f.name} (${f.sizeBytes / 1024} КБ) с ${URL(f.url).host}${if (existing > 0) ", докачка с ${existing / 1024} КБ" else ""}")
        val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Basis-diary")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
            if (code != HttpURLConnection.HTTP_OK && !append) throw IOException("HTTP $code")
            if (!append) existing = 0
            val md = MessageDigest.getInstance("SHA-256")
            if (append) part.inputStream().use { copyHashing(it, null, md) {} }
            FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input -> copyHashing(input, out, md) { n -> progress(existing + n) } }
            }
            val hash = md.hex()
            if (hash != f.sha256) {
                part.delete()
                throw IOException("SHA-256 не совпадает для ${f.name}: $hash")
            }
            if (!part.renameTo(target)) throw IOException("не удалось переименовать ${part.name}")
            AppLog.i(TAG, "${f.name}: загружено, SHA-256 OK")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Imports a model file chosen via the system picker. The file is identified by SHA-256 (or by name,
     * in which case a different version is accepted with a warning). Returns a user-facing message.
     */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val staging = File(root, ".import-${System.currentTimeMillis()}").apply { parentFile?.mkdirs() }
        try {
            val md = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staging).use { out -> copyHashing(input, out, md) {} }
            } ?: throw IOException("не удалось открыть файл")
            val hash = md.hex()
            val (spec, f) = ModelCatalog.identify(hash, displayName)
                ?: return@withContext "Файл «${displayName ?: "?"}» не похож ни на одну известную модель"
            val dir = dir(spec).apply { mkdirs() }
            val target = File(dir, f.name)
            target.delete()
            if (!staging.renameTo(target)) throw IOException("не удалось переместить файл")
            val exact = hash == f.sha256
            val m = marker(spec)
            val lines = (if (m.exists()) m.readLines() else emptyList()).filterNot { it.startsWith(f.name + " ") } + "${f.name} $hash"
            m.writeText(lines.joinToString("\n"))
            states.getValue(spec.id).update { initialState(spec) }
            val msg = if (exact) "${spec.title}: ${f.name} импортирован, SHA-256 совпадает"
            else "${spec.title}: ${f.name} импортирован, но это другая версия (SHA-256 $hash)"
            AppLog.i(TAG, msg)
            msg
        } catch (t: Throwable) {
            AppLog.e(TAG, "импорт не удался", t)
            "Импорт не удался: ${t.message}"
        } finally {
            staging.delete()
        }
    }

    fun delete(spec: ModelSpec) {
        dir(spec).deleteRecursively()
        states.getValue(spec.id).value = ModelState.Missing
        AppLog.i(TAG, "${spec.title}: удалена")
    }

    private companion object {
        const val TAG = "Models"
    }
}
