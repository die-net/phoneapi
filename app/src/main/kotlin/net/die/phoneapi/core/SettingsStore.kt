package net.die.phoneapi.core

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
enum class BindMode {
    /** Bind only to the Wi-Fi/Ethernet interface address; never loopback. */
    LAN,
    /** Bind to all interfaces, including loopback (needed for `adb forward`). */
    ALL,
}

@Serializable
data class Settings(
    val port: Int,
    val bindMode: BindMode = BindMode.LAN,
    val mdnsEnabled: Boolean = true,
    val mdnsName: String = "Android device",
    val keepAwakeMs: Long = 60_000,
    val keystorePassword: String,
    /** Random id advertised over mDNS so clients can recognise this device after IP changes. */
    val instanceId: String,
)

/** Small JSON-file settings store in the app's private files directory. */
class SettingsStore(private val dir: File) {
    private val file = File(dir, "settings.json")
    private val state = MutableStateFlow(load())

    val settings: StateFlow<Settings> = state.asStateFlow()

    val current: Settings
        get() = state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        AtomicFiles.write(file, ApiJson.encodeToString(serializer<Settings>(), next))
        state.value = next
    }

    private fun load(): Settings {
        if (file.exists()) {
            return ApiJson.decodeFromString(serializer<Settings>(), file.readText())
        }
        val random = SecureRandom()
        val created =
            Settings(
                port = EPHEMERAL_MIN + random.nextInt(EPHEMERAL_MAX - EPHEMERAL_MIN),
                keystorePassword = randomToken(random, 24),
                instanceId = randomToken(random, 6),
            )
        dir.mkdirs()
        AtomicFiles.write(file, ApiJson.encodeToString(serializer<Settings>(), created))
        return created
    }

    private companion object {
        // Stay clear of well-known and commonly scanned ports.
        const val EPHEMERAL_MIN = 20_000
        const val EPHEMERAL_MAX = 60_000
    }
}

fun randomToken(random: SecureRandom, bytes: Int): String {
    val buf = ByteArray(bytes)
    random.nextBytes(buf)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(separator: String = "", upper: Boolean = false): String {
    val digits = if (upper) HEX.uppercase(Locale.ROOT) else HEX
    return joinToString(separator) { b ->
        val v = b.toInt() and 0xff
        "${digits[v ushr 4]}${digits[v and 0x0f]}"
    }
}

object AtomicFiles {
    fun write(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(file)) { "Failed to replace ${file.path}" }
    }
}
