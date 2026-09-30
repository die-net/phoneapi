package net.die.phoneapi.power

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.die.phoneapi.core.ApiException

/** A lock-screen PIN this app can type: 4 to 16 digits, which is what Android accepts. */
internal fun normalizePin(pin: String): String {
    val trimmed = pin.trim()
    val digits = trimmed.length in PIN_LENGTH && trimmed.all { it.isDigit() }
    if (!digits) {
        throw ApiException.badRequest(
            "pin must be ${PIN_LENGTH.first} to ${PIN_LENGTH.last} digits"
        )
    }
    return trimmed
}

private val PIN_LENGTH = 4..16

/**
 * The device's lock-screen PIN, encrypted with an AES key that never leaves the Android Keystore.
 * It is write-only as far as the API is concerned: `PUT /v1/device/pin` and the ADB `SET_PIN`
 * broadcast store it, and only [PowerServiceImpl] ever reads it back.
 *
 * The key deliberately doesn't require an unlocked device, since the whole point is to use it while
 * the keyguard is up. It is still bound to this app and to the device's hardware-backed keystore.
 */
class PinStore(dir: File) {
    private val file = File(dir, "lockpin.bin")

    val isSet: Boolean
        get() = file.exists()

    /** The stored PIN, or null when none is set or it can no longer be decrypted. */
    fun read(): String? =
        try {
            decrypt()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Stored PIN could not be decrypted", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "Stored PIN could not be read", e)
            null
        }

    private fun decrypt(): String? {
        if (!file.exists()) return null
        val blob = file.readBytes()
        val secret = key(create = false) ?: return null
        if (blob.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    }

    /** Stores [pin], or clears it when [pin] is null or blank. A non-blank value must be a PIN. */
    fun write(pin: String?) {
        if (pin.isNullOrBlank()) {
            file.delete()
            return
        }
        val normalized = normalizePin(pin)
        try {
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, checkNotNull(key(create = true)))
                }
            file.parentFile?.mkdirs()
            file.writeBytes(cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8)))
        } catch (e: GeneralSecurityException) {
            throw ApiException(500, "pin_store_failed", "Could not encrypt the PIN", cause = e)
        } catch (e: IOException) {
            throw ApiException(500, "pin_store_failed", "Could not write the PIN", cause = e)
        }
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
        const val TAG = "PhoneApiPower"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "phoneapi_lock_pin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
