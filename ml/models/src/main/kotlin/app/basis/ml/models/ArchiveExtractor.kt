package app.basis.ml.models

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Extracts selected members (matched by file name, ignoring directories) from a `.tar.bz2`. */
object ArchiveExtractor {
    /** Returns the extracted files by member name. Throws if any member is missing. */
    fun extractTarBz2(input: InputStream, members: List<String>, destDir: File, onBytes: (Long) -> Unit = {}): Map<String, File> {
        destDir.mkdirs()
        val wanted = members.toSet()
        val out = mutableMapOf<String, File>()
        var total = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(input.buffered(1 shl 20), true)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (name !in wanted || name in out) continue
                // Never trust archive paths: write only to destDir/<plain file name>.
                val target = File(destDir, name)
                val tmp = File(destDir, "$name.extracting")
                tmp.outputStream().buffered(1 shl 20).use { os ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = tar.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
                if (!tmp.renameTo(target)) throw IOException("rename failed: $name")
                out[name] = target
            }
        }
        val missing = wanted - out.keys
        if (missing.isNotEmpty()) throw IOException("в архиве нет файлов: ${missing.joinToString()}")
        return out
    }
}
