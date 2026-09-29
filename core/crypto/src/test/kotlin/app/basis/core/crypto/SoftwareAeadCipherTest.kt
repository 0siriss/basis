package app.basis.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.AEADBadTagException

class SoftwareAeadCipherTest {
    private val cipher = SoftwareAeadCipher()
    private val aad = "header".toByteArray()

    @Test
    fun roundTrip() {
        val plain = ByteArray(10_000) { (it % 251).toByte() }
        val s = cipher.encrypt(plain, aad)
        assertFalse(s.ciphertext.contentEquals(plain))
        assertArrayEquals(plain, cipher.decrypt(s.iv, s.ciphertext, aad))
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedCiphertextIsRejected() {
        val s = cipher.encrypt(ByteArray(100) { 1 }, aad)
        s.ciphertext[5] = (s.ciphertext[5] + 1).toByte()
        cipher.decrypt(s.iv, s.ciphertext, aad)
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedAadIsRejected() {
        val s = cipher.encrypt(ByteArray(100) { 1 }, aad)
        cipher.decrypt(s.iv, s.ciphertext, "other".toByteArray())
    }

    @Test
    fun ivIsFreshEachTime() {
        val a = cipher.encrypt(ByteArray(10), aad)
        val b = cipher.encrypt(ByteArray(10), aad)
        assertFalse(a.iv.contentEquals(b.iv))
    }
}
