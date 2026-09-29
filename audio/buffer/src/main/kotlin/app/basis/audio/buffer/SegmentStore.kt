package app.basis.audio.buffer

import app.basis.core.crypto.AeadCipher
import java.io.File

data class StoredSegment(val file: File, val meta: SegmentMeta, val sizeBytes: Long)

data class BufferStats(val count: Int = 0, val audioMs: Long = 0, val bytes: Long = 0)

data class WriteResult(val segment: StoredSegment, val droppedForCap: Int, val encryptMs: Long)

/**
 * Temporary encrypted buffer of speech segments waiting for ASR. One file per segment, written
 * atomically (tmp + rename). Decrypted audio exists only in memory. Files are deleted right after
 * recognition (stage 3) or when the size cap is exceeded (oldest first).
 * All methods are synchronized: the VAD thread writes while ASR/UI read and delete.
 */
open class SegmentStore(
    private val dir: File,
    private val cipher: AeadCipher,
    private val maxBytes: Long,
) {
    init {
        dir.mkdirs()
    }

    @Synchronized
    open fun write(meta: SegmentMeta, pcm: ShortArray): WriteResult {
        val t0 = System.nanoTime()
        val bytes = SegmentFormat.encode(meta, pcm, cipher)
        val encryptMs = (System.nanoTime() - t0) / 1_000_000
        val name = "%013d-%08d.bseg".format(meta.startMs, (System.nanoTime() % 100_000_000).coerceAtLeast(0))
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("rename failed for $name")
        }
        val dropped = enforceCap()
        return WriteResult(StoredSegment(target, meta, target.length()), dropped, encryptMs)
    }

    /** Pending segments, oldest first. Unreadable files are skipped (and reported by [sweep]). */
    @Synchronized
    open fun list(): List<StoredSegment> =
        segmentFiles().mapNotNull { f ->
            runCatching { StoredSegment(f, SegmentFormat.readMeta(f), f.length()) }.getOrNull()
        }.sortedBy { it.meta.startMs }

    @Synchronized
    open fun read(segment: StoredSegment): ShortArray =
        SegmentFormat.decode(segment.file.readBytes(), cipher).second

    @Synchronized
    open fun delete(segment: StoredSegment): Boolean = segment.file.delete()

    @Synchronized
    open fun stats(): BufferStats {
        val all = list()
        return BufferStats(all.size, all.sumOf { it.meta.durationMs }, all.sumOf { it.sizeBytes })
    }

    /** Deletes every segment. Returns the number of files removed. */
    @Synchronized
    open fun clear(): Int = dir.listFiles().orEmpty().count { it.isFile && it.delete() }

    /** Removes leftovers of interrupted writes and corrupt files. Returns names of removed files. */
    @Synchronized
    open fun sweep(): List<String> {
        val removed = mutableListOf<String>()
        dir.listFiles().orEmpty().forEach { f ->
            val bad = f.name.endsWith(".tmp") ||
                (f.name.endsWith(".bseg") && runCatching { SegmentFormat.readMeta(f) }.isFailure)
            if (bad && f.delete()) removed += f.name
        }
        return removed
    }

    /** Every file in the buffer directory (used to verify nothing is left after processing). */
    @Synchronized
    open fun allFiles(): List<File> = dir.listFiles().orEmpty().filter { it.isFile }

    private fun segmentFiles() = dir.listFiles { f -> f.isFile && f.name.endsWith(".bseg") }.orEmpty().toList()

    private fun enforceCap(): Int {
        var files = segmentFiles().sortedBy { it.name }
        var total = files.sumOf { it.length() }
        var dropped = 0
        while (total > maxBytes && files.size > 1) {
            val oldest = files.first()
            total -= oldest.length()
            if (oldest.delete()) dropped++
            files = files.drop(1)
        }
        return dropped
    }
}
