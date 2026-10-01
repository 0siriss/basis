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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
 * to hourly files in `filesDir/logs`. Rotation: only the last [KEEP_HOURS] hours are kept (checked
 * whenever a new hour's file starts, not just at process start — the process lives for days), and
 * the folder never exceeds [MAX_BYTES]. Must never contain speech content.
 */
object AppLog {
    private const val CAPACITY = 3000
    private const val KEEP_HOURS = 24L
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val KEEP_MS = KEEP_HOURS * 3_600_000L

    private val buffer = RingBuffer<LogEntry>(CAPACITY)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var seq = 0L
    private var logDir: File? = null
    private var currentHour: LocalDateTime? = null
    private val fileQueue = Channel<LogEntry>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (logDir != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logDir = dir
        scope.launch {
            rotate(dir)
            restoreRecent(dir)
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

    /** The last 24 hours of log files, for sharing. */
    fun exportText(): String {
        val dir = logDir ?: return entries.value.joinToString("\n") { it.format() }
        rotate(dir)
        return logFiles(dir).joinToString("") { it.readText() }
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
        _entries.update { synchronized(buffer) { recent(buffer.toList()) } }
        if (logDir != null) fileQueue.trySend(entry)
    }

    /** The screen shows the same window as the files: entries older than 24 h drop out. */
    private fun recent(all: List<LogEntry>): List<LogEntry> {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        // Restored lines (timeMs = 0) come from the newest files only, so they are within the window.
        return all.filter { it.timeMs == 0L || it.timeMs >= cutoff }
    }

    /** Level char of a line written by [LogEntry.format] ("MM-dd HH:mm:ss.SSS L/tag: …"). */
    private fun levelOf(line: String): LogLevel =
        LogLevel.entries.firstOrNull { it.short == line.getOrNull(19) } ?: LogLevel.DEBUG

    private fun String.withThrowable(t: Throwable?) =
        if (t == null) this else "$this\n${Log.getStackTraceString(t)}"

    private val HOUR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH")

    private fun fileFor(dir: File, hour: LocalDateTime) = File(dir, "basis-${HOUR_FMT.format(hour)}.log")

    private fun hourOf(f: File): LocalDateTime? =
        runCatching { LocalDateTime.parse(f.name.removePrefix("basis-").removeSuffix(".log") + ":00") }.getOrNull()

    /** Log files of the kept window, oldest first. */
    private fun logFiles(dir: File): List<File> =
        dir.listFiles().orEmpty().filter { hourOf(it) != null }.sortedBy { it.name }

    @Synchronized
    private fun appendToFile(dir: File, entry: LogEntry) {
        val hour = Instant.ofEpochMilli(entry.timeMs).atZone(ZoneId.systemDefault()).toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        if (hour != currentHour) {
            currentHour = hour
            rotate(dir)
        }
        runCatching { fileFor(dir, hour).appendText(entry.format() + "\n") }
    }

    /** Deletes files older than [KEEP_HOURS] (and old-format daily files), then the oldest beyond [MAX_BYTES]. */
    @Synchronized
    private fun rotate(dir: File) {
        val cutoff = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(KEEP_HOURS)
        dir.listFiles().orEmpty().forEach { f ->
            val h = hourOf(f)
            if (h == null || h.isBefore(cutoff)) f.delete()
        }
        var total = logFiles(dir).sumOf { it.length() }
        for (f in logFiles(dir).dropLast(1)) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** Shows the tail of the last files (previous process runs) above the new entries. */
    private fun restoreRecent(dir: File) {
        val lines = runCatching { logFiles(dir).takeLast(3).flatMap { it.readLines() }.takeLast(500) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return
        // Negative seq marks restored lines (already formatted); keys stay unique for the UI list.
        val restored = lines.mapIndexed { i, line -> LogEntry(-(i + 2L), 0, levelOf(line), "prev", line) } +
            LogEntry(-1, System.currentTimeMillis(), LogLevel.INFO, "AppLog", "──── новый запуск процесса ────")
        synchronized(buffer) {
            val current = buffer.toList()
            buffer.clear()
            (restored + current).forEach { buffer.add(it) }
        }
        _entries.update { synchronized(buffer) { recent(buffer.toList()) } }
    }
}
