package net.die.phoneapi.helperclient

import android.util.Log
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import net.die.phoneapi.core.KeystoreAes

/**
 * Persists the ADB private key Kadb uses to pair with wireless debugging.
 *
 * The key has to be readable (ADB authentication signs with it), so it cannot live only inside the
 * Android Keystore. A non-exportable Keystore key encrypts the PEM on disk instead.
 */
internal class KeystorePrivateKeyStore(dir: File) : KadbPrivateKeyStore {
    private val file = File(dir, "adb-key.bin")
    private val paired = File(dir, "adb-paired")
    private val cipher = KeystoreAes(ALIAS)

    /** True after a pairing or a wireless shell has been accepted by this device. */
    val isPaired: Boolean
        get() = paired.isFile && file.isFile

    fun markPaired() {
        paired.writeText("1")
    }

    override fun readPrivateKeyPem(): ByteArray? {
        if (!file.isFile) return null
        return try {
            cipher.decrypt(file.readBytes())
                ?: run {
                    Log.w(TAG, "ADB key could not be decrypted; it will be created again")
                    clear()
                    null
                }
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
                cipher.encrypt(privateKeyPem)
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

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val ALIAS = "phoneapi_adb_key"
    }
}
