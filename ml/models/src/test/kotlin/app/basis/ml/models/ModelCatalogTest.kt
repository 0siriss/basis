package app.basis.ml.models

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ModelCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun identifiesByHashThenByName() {
        val vad = ModelCatalog.SILERO_VAD.files.single()
        assertEquals(ModelCatalog.Match.File(ModelCatalog.SILERO_VAD, vad), ModelCatalog.identify(vad.sha256.uppercase(), "whatever.bin"))
        assertEquals(ModelCatalog.Match.File(ModelCatalog.SILERO_VAD, vad), ModelCatalog.identify("00", "silero_vad.onnx"))
        val giga = ModelCatalog.GIGAAM_V3
        assertEquals(ModelCatalog.Match.Archive(giga), ModelCatalog.identify(giga.archive!!.sha256, null))
        assertEquals(ModelCatalog.Match.Archive(giga), ModelCatalog.identify("00", "sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16.tar.bz2"))
        assertNull(ModelCatalog.identify("00", "other.onnx"))
    }

    @Test
    fun catalogIsWellFormed() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ModelCatalog.all.forEach { spec ->
            (spec.files.map { it.sha256 to it.url } + listOfNotNull(spec.archive?.let { it.sha256 to it.url })).forEach { (sha, url) ->
                assertEquals(64, sha.length)
                assertTrue(url.startsWith("https://"))
            }
            assertTrue(spec.requiredFiles.isNotEmpty())
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

    private fun tarBz2(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bos)).use { tar ->
            entries.forEach { (name, data) ->
                val e = TarArchiveEntry(name).apply { size = data.size.toLong() }
                tar.putArchiveEntry(e)
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun extractsOnlyWantedMembersFlat() {
        val archive = tarBz2(
            mapOf(
                "model-dir/encoder.int8.onnx" to ByteArray(5000) { 1 },
                "model-dir/encoder.onnx" to ByteArray(9000) { 2 },
                "model-dir/tokens.txt" to "a 0\n".toByteArray(),
                "model-dir/test_wavs/0.wav" to ByteArray(100),
            ),
        )
        val dest = tmp.newFolder("out")
        val got = ArchiveExtractor.extractTarBz2(ByteArrayInputStream(archive), listOf("encoder.int8.onnx", "tokens.txt"), dest)
        assertEquals(setOf("encoder.int8.onnx", "tokens.txt"), got.keys)
        assertEquals(5000L, File(dest, "encoder.int8.onnx").length())
        assertFalse(File(dest, "encoder.onnx").exists())
        assertEquals(setOf("encoder.int8.onnx", "tokens.txt"), dest.list()!!.toSet())
    }

    @Test(expected = IOException::class)
    fun missingMemberFails() {
        val archive = tarBz2(mapOf("x/tokens.txt" to ByteArray(3)))
        ArchiveExtractor.extractTarBz2(ByteArrayInputStream(archive), listOf("tokens.txt", "joiner.onnx"), tmp.newFolder("o"))
    }

    @Test
    fun pathTraversalIsNeutralized() {
        val archive = tarBz2(mapOf("../../evil/tokens.txt" to ByteArray(3)))
        val dest = tmp.newFolder("safe")
        ArchiveExtractor.extractTarBz2(ByteArrayInputStream(archive), listOf("tokens.txt"), dest)
        assertTrue(File(dest, "tokens.txt").exists())
        assertFalse(File(dest.parentFile.parentFile, "evil").exists())
    }
}
