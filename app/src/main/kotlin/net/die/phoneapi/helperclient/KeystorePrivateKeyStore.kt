package net.die.phoneapi.helperclient

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the ADB private key Kadb uses to pair with wireless debugging.
 *
 * The key has to be readable (ADB authentication signs with it), so it cannot live only inside the
 * Android Keystore. A non-exportable Keystore key encrypts the PEM on disk instead.
 */
internal class KeystorePrivateKeyStore(dir: File) : KadbPrivateKeyStore {
    private val file = File(dir, "adb-key.bin")
    private val paired = File(dir, "adb-paired")

    /** True after a pairing or a wireless shell has been accepted by this device. */
    val isPaired: Boolean
        get() = paired.isFile && file.isFile

    fun markPaired() {
        paired.writeText("1")
    }

    override fun readPrivateKeyPem(): ByteArray? {
        if (!file.isFile) return null
        return try {
            decrypt(file.readBytes())
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "ADB key could not be decrypted; it will be created again", e)
            clear()
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "ADB key could not be decrypted; it will be created again", e)
            clear()
            null
        } catch (e: IOException) {
            Log.w(TAG, "ADB key could not be read", e)
            null
        }
    }

    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        val blob =
            try {
                encrypt(privateKeyPem)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("Could not encrypt the ADB key", e)
            }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeBytes(blob)
            if (!tmp.renameTo(file)) {
                file.writeBytes(blob)
                tmp.delete()
            }
        } catch (e: IOException) {
            throw IllegalStateException("Could not store the ADB key", e)
        }
    }

    override fun clear() {
        file.delete()
        paired.delete()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(create = false) ?: error("ADB keystore key is missing"),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
        )
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "phoneapi_adb_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
