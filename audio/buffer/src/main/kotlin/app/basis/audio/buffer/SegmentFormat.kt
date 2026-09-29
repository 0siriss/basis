package app.basis.audio.buffer

import app.basis.core.crypto.AeadCipher
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Metadata of one speech segment. Stored in the clear (authenticated) so the buffer can be listed without decrypting. */
data class SegmentMeta(
    val startMs: Long,
    val sampleRate: Int,
    val numSamples: Int,
) {
    val durationMs: Long get() = numSamples * 1000L / sampleRate
    val endMs: Long get() = startMs + durationMs
}

/**
 * Encrypted segment file:
 * ```
 * "BSEG" | ver:u8 | sampleRate:i32 | startMs:i64 | numSamples:i32 | ivLen:u8 | iv | AES-GCM(PCM16LE) + tag
 * ```
 * The fixed header (everything before ivLen) is the AEAD associated data, so it can't be altered.
 */
object SegmentFormat {
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte())
    private const val VERSION: Byte = 1
    const val HEADER_SIZE = 4 + 1 + 4 + 8 + 4

    fun header(meta: SegmentMeta): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).put(VERSION).putInt(meta.sampleRate).putLong(meta.startMs).putInt(meta.numSamples)
            .array()

    fun parseHeader(h: ByteArray): SegmentMeta {
        if (h.size < HEADER_SIZE) throw IOException("short header")
        val b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("bad magic")
        val ver = b.get()
        if (ver != VERSION) throw IOException("unsupported version $ver")
        return SegmentMeta(sampleRate = b.int, startMs = b.long, numSamples = b.int)
    }

    fun encode(meta: SegmentMeta, pcm: ShortArray, cipher: AeadCipher): ByteArray {
        require(pcm.size == meta.numSamples) { "numSamples mismatch" }
        val header = header(meta)
        val plain = pcmToBytes(pcm)
        val sealed = cipher.encrypt(plain, header)
        plain.fill(0)
        return ByteBuffer.allocate(header.size + 1 + sealed.iv.size + sealed.ciphertext.size)
            .put(header).put(sealed.iv.size.toByte()).put(sealed.iv).put(sealed.ciphertext)
            .array()
    }

    fun decode(bytes: ByteArray, cipher: AeadCipher): Pair<SegmentMeta, ShortArray> {
        val header = bytes.copyOfRange(0, HEADER_SIZE)
        val meta = parseHeader(header)
        val ivLen = bytes[HEADER_SIZE].toInt() and 0xFF
        val ivStart = HEADER_SIZE + 1
        val iv = bytes.copyOfRange(ivStart, ivStart + ivLen)
        val ct = bytes.copyOfRange(ivStart + ivLen, bytes.size)
        val plain = cipher.decrypt(iv, ct, header)
        val pcm = bytesToPcm(plain)
        plain.fill(0)
        if (pcm.size != meta.numSamples) throw IOException("sample count mismatch")
        return meta to pcm
    }

    fun readMeta(file: File): SegmentMeta = DataInputStream(file.inputStream().buffered()).use { input ->
        val h = ByteArray(HEADER_SIZE)
        input.readFully(h)
        parseHeader(h)
    }

    fun pcmToBytes(pcm: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        return bb.array()
    }

    fun bytesToPcm(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }
}
