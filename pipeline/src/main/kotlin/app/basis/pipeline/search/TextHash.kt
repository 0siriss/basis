package app.basis.pipeline.search

import java.security.MessageDigest

internal fun chunkHash(kind: String, text: String): String =
    MessageDigest.getInstance("SHA-1").digest("$kind\n$text".toByteArray()).joinToString("") { "%02x".format(it) }
