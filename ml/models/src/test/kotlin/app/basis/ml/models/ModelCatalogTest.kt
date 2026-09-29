package app.basis.ml.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ModelCatalogTest {
    @Test
    fun identifiesByHashThenByName() {
        val vad = ModelCatalog.SILERO_VAD.files.single()
        assertEquals(vad, ModelCatalog.identify(vad.sha256.uppercase(), "whatever.bin")?.second)
        assertEquals(vad, ModelCatalog.identify("00", "silero_vad.onnx")?.second)
        assertNull(ModelCatalog.identify("00", "other.onnx"))
    }

    @Test
    fun catalogHashesAreWellFormed() {
        ModelCatalog.all.flatMap { it.files }.forEach {
            assertEquals(64, it.sha256.length)
            org.junit.Assert.assertTrue(it.url.startsWith("https://"))
        }
    }

    @Test
    fun copyHashingMatchesDigest() {
        val data = ByteArray(1_000_000) { (it * 7).toByte() }
        val md = MessageDigest.getInstance("SHA-256")
        val out = ByteArrayOutputStream()
        val n = copyHashing(ByteArrayInputStream(data), out, md) {}
        assertEquals(data.size.toLong(), n)
        val expected = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        assertEquals(expected, md.hex())
        assertEquals(data.size, out.size())
    }
}
