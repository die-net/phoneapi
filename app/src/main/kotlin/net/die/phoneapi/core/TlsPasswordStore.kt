package net.die.phoneapi.core

import android.util.Log
import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * PKCS#12 password for the TLS keystore, encrypted with an Android Keystore key.
 *
 * An upgrade that still has `keystorePassword` in settings.json keeps that password, so the
 * certificate and its fingerprint stay the same. A fresh install generates one. The plaintext field
 * is removed from settings once the encrypted copy is on disk.
 */
internal class TlsPasswordStore(private val dir: File) {
    private val file = AtomicFile(File(dir, FILE))
    private val settings = AtomicFile(File(dir, SETTINGS_FILE_NAME))
    private val cipher = KeystoreAes(ALIAS)

    fun chars(): CharArray = StoreIo.call { synchronized(gate) { materialize().copyOf() } }

    /** Writes the encrypted password and drops any plaintext copy. Safe to call more than once. */
    fun migrate() {
        chars()
    }

    /**
     * An undecryptable password (for example after the Keystore key is lost) is replaced, and
     * [net.die.phoneapi.server.TlsManager] then regenerates the certificate it can no longer open.
     */
    private fun materialize(): CharArray {
        if (file.baseFile.isFile) {
            decrypt()?.let { password ->
                stripLegacy()
                return password
            }
        }
        readLegacy()?.let { legacy ->
            writeEncrypted(legacy)
            stripLegacy()
            return legacy.toCharArray()
        }
        val generated = randomToken(SecureRandom(), PASSWORD_BYTES)
        writeEncrypted(generated)
        return generated.toCharArray()
    }

    private fun decrypt(): CharArray? =
        try {
            val plain = cipher.decrypt(file.readFully()) ?: return null
            val text = plain.decodeToString()
            text.takeIf { it.isNotEmpty() }?.toCharArray()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "TLS password could not be decrypted", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "TLS password could not be read", e)
            null
        }

    private fun readLegacy(): String? {
        val json = settings.readUtf8() ?: return null
        return legacyKeystorePassword(json)
    }

    private fun stripLegacy() {
        val json = settings.readUtf8() ?: return
        val stripped = stripKeystorePassword(json)
        if (stripped != json) settings.writeUtf8(stripped)
    }

    private fun writeEncrypted(password: String) {
        val blob =
            try {
                cipher.encrypt(password.toByteArray(Charsets.UTF_8))
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("Could not encrypt the TLS password", e)
            }
        try {
            dir.mkdirs()
            file.writeAll(blob)
        } catch (e: IOException) {
            throw IllegalStateException("Could not store the TLS password", e)
        }
    }

    private companion object {
        const val TAG = "PhoneApiTls"
        const val ALIAS = "phoneapi_tls_password"
        const val FILE = "tls-password.bin"
        const val PASSWORD_BYTES = 24
        val gate = Any()
    }
}

internal const val SETTINGS_FILE_NAME = "settings.json"

/** The plaintext `keystorePassword` in an old settings file, if it is a non-empty string. */
internal fun legacyKeystorePassword(json: String): String? =
    try {
        val obj = ApiJson.parseToJsonElement(json) as? JsonObject ?: return null
        val primitive = obj["keystorePassword"] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        primitive.content.takeIf { it.isNotEmpty() }
    } catch (_: SerializationException) {
        null
    }

/** Settings JSON with `keystorePassword` removed. Unchanged when the field is already absent. */
internal fun stripKeystorePassword(json: String): String {
    val obj =
        try {
            ApiJson.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return json
    if (obj["keystorePassword"] == null) return json
    val stripped = JsonObject(obj.filterKeys { it != "keystorePassword" })
    return ApiJson.encodeToString(serializer<JsonElement>(), stripped)
}
