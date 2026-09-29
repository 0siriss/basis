package app.basis.core.common

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel(val short: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

data class LogEntry(
    val seq: Long,
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun format(zone: ZoneId = ZoneId.systemDefault()): String =
        "${TIME_FMT.format(Instant.ofEpochMilli(timeMs).atZone(zone))} ${level.short}/$tag: $message"

    private companion object {
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}

/**
 * In-app log. Never leaves the device: kept in memory (for the Logs screen), mirrored to logcat and
 * to daily files in `filesDir/logs` (kept for [KEEP_DAYS] days). Must never contain speech content.
 */
object AppLog {
    private const val CAPACITY = 3000
    private const val KEEP_DAYS = 3L

    private val buffer = RingBuffer<LogEntry>(CAPACITY)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var seq = 0L
    private var logDir: File? = null
    private val fileQueue = Channel<LogEntry>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (logDir != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logDir = dir
        scope.launch {
            pruneOldFiles(dir)
            loadToday(dir)
            for (entry in fileQueue) appendToFile(dir, entry)
        }
    }

    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN, tag, msg.withThrowable(t))
    fun e(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, msg.withThrowable(t))

    /** Synchronous write for the uncaught-exception handler (the async queue won't survive the crash). */
    fun crashSync(thread: String, t: Throwable) {
        val entry = LogEntry(seq++, System.currentTimeMillis(), LogLevel.ERROR, "Crash", "необработанное исключение в потоке $thread\n${Log.getStackTraceString(t)}")
        Log.e("Basis/Crash", entry.message)
        logDir?.let { appendToFile(it, entry) }
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _entries.value = emptyList()
    }

    /** Full text of today's and previous days' log files, for sharing. */
    fun exportText(): String {
        val dir = logDir ?: return entries.value.joinToString("\n") { it.format() }
        return dir.listFiles().orEmpty().sortedBy { it.name }.joinToString("\n") { it.readText() }
    }

    private fun log(level: LogLevel, tag: String, msg: String) {
        val entry = synchronized(buffer) {
            LogEntry(seq++, System.currentTimeMillis(), level, tag, msg).also { buffer.add(it) }
        }
        when (level) {
            LogLevel.DEBUG -> Log.d("Basis/$tag", msg)
            LogLevel.INFO -> Log.i("Basis/$tag", msg)
            LogLevel.WARN -> Log.w("Basis/$tag", msg)
            LogLevel.ERROR -> Log.e("Basis/$tag", msg)
        }
        _entries.update { synchronized(buffer) { buffer.toList() } }
        if (logDir != null) fileQueue.trySend(entry)
    }

    /** Level char of a line written by [LogEntry.format] ("MM-dd HH:mm:ss.SSS L/tag: …"). */
    private fun levelOf(line: String): LogLevel =
        LogLevel.entries.firstOrNull { it.short == line.getOrNull(19) } ?: LogLevel.DEBUG

    private fun String.withThrowable(t: Throwable?) =
        if (t == null) this else "$this\n${Log.getStackTraceString(t)}"

    private fun fileFor(dir: File, day: LocalDate) = File(dir, "basis-$day.log")

    private fun appendToFile(dir: File, entry: LogEntry) {
        val day = Instant.ofEpochMilli(entry.timeMs).atZone(ZoneId.systemDefault()).toLocalDate()
        runCatching { fileFor(dir, day).appendText(entry.format() + "\n") }
    }

    private fun pruneOldFiles(dir: File) {
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS - 1)
        dir.listFiles().orEmpty().forEach { f ->
            val day = f.name.removePrefix("basis-").removeSuffix(".log")
            val date = runCatching { LocalDate.parse(day) }.getOrNull()
            if (date == null || date.isBefore(cutoff)) f.delete()
        }
    }

    /** Shows the tail of today's file (previous process runs) above the new entries. */
    private fun loadToday(dir: File) {
        val f = fileFor(dir, LocalDate.now())
        if (!f.exists()) return
        val lines = runCatching { f.readLines().takeLast(500) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return
        // Negative seq marks restored lines (already formatted); keys stay unique for the UI list.
        val restored = lines.mapIndexed { i, line -> LogEntry(-(i + 2L), 0, levelOf(line), "prev", line) } +
            LogEntry(-1, System.currentTimeMillis(), LogLevel.INFO, "AppLog", "──── новый запуск процесса ────")
        synchronized(buffer) {
            val current = buffer.toList()
            buffer.clear()
            (restored + current).forEach { buffer.add(it) }
        }
        _entries.update { synchronized(buffer) { buffer.toList() } }
    }
}
