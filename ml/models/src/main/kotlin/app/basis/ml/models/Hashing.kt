package app.basis.ml.models

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

internal fun sha256Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> copyHashing(input, null, md) {} }
    return md.hex()
}

/** Copies [input] to [output] (if not null) while hashing; [onBytes] gets the running total. */
internal fun copyHashing(input: InputStream, output: OutputStream?, md: MessageDigest, onBytes: (Long) -> Unit): Long {
    val buf = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
        output?.write(buf, 0, n)
        total += n
        onBytes(total)
    }
    return total
}
