package app.basis.core.crypto

import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Result of an AEAD encryption: a fresh nonce and ciphertext with the GCM tag appended. */
class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

/** Authenticated encryption; [aad] is authenticated but not encrypted (e.g. a file header). */
interface AeadCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): Sealed

    /** Throws [javax.crypto.AEADBadTagException] if data, IV or AAD were tampered with. */
    fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val TAG_BITS = 128

/**
 * AES-256-GCM with a non-exportable key in Android Keystore (TEE/StrongBox-backed where available).
 * The key never leaves secure hardware; the app only sees ciphertext on disk.
 */
class KeystoreAeadCipher(private val alias: String) : AeadCipher {
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override fun encrypt(plain: ByteArray, aad: ByteArray): Sealed {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key) // Keystore generates a random IV (randomized encryption required).
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return Sealed(c.iv, ct)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        return c.doFinal(ciphertext)
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** Same format with an in-memory key; used in JVM unit tests. */
class SoftwareAeadCipher(private val key: SecretKey = newKey()) : AeadCipher {
    private val random = SecureRandom()

    override fun encrypt(plain: ByteArray, aad: ByteArray): Sealed {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        return Sealed(iv, c.doFinal(plain))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        return c.doFinal(ciphertext)
    }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
