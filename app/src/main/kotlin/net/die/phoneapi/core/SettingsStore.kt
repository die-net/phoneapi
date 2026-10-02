package net.die.phoneapi.core

import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
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
    /** Random id advertised over mDNS so clients can recognise this device after IP changes. */
    val instanceId: String,
    /** The pairing screen shows the IP address instead of the `.local` name. */
    val pairingShowsIp: Boolean = false,
    /** When on, an HTTPS listener splices onto the abstract socket and mDNS is advertised. */
    val tlsEnabled: Boolean = false,
)

/**
 * Disk reads and writes for the JSON stores. Callers stay synchronous; work that would otherwise
 * run on the main thread is moved to a single background thread.
 */
internal object StoreIo {
    @Volatile private var main: Thread? = null

    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phoneapi-store").apply { isDaemon = true }
        }
    }

    fun markMain(thread: Thread) {
        main = thread
    }

    fun <T> call(block: () -> T): T {
        if (Thread.currentThread() !== main) return block()
        return CompletableFuture.supplyAsync(block, executor).join()
    }
}

/** Small JSON-file settings store in the app's private files directory. */
class SettingsStore(private val dir: File) {
    private val atomic = AtomicFile(File(dir, SETTINGS_FILE_NAME))
    private val state: MutableStateFlow<Settings> by lazy { MutableStateFlow(load()) }

    val settings: StateFlow<Settings> by lazy { state.asStateFlow() }

    val current: Settings
        get() = state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(current)
        StoreIo.call { atomic.writeUtf8(ApiJson.encodeToString(serializer<Settings>(), next)) }
        state.value = next
    }

    private fun load(): Settings = StoreIo.call {
        // Before this file can be rewritten, move a legacy plaintext password into the keystore.
        TlsPasswordStore(dir).migrate()
        atomic.readUtf8()?.let { ApiJson.decodeFromString(serializer<Settings>(), it) }
            ?: run {
                val random = SecureRandom()
                val created =
                    Settings(
                        port = EPHEMERAL_MIN + random.nextInt(EPHEMERAL_MAX - EPHEMERAL_MIN),
                        instanceId = randomToken(random, 6),
                    )
                dir.mkdirs()
                atomic.writeUtf8(ApiJson.encodeToString(serializer<Settings>(), created))
                created
            }
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

internal fun AtomicFile.readUtf8(): String? {
    if (!baseFile.exists()) return null
    return readFully().decodeToString()
}

internal fun AtomicFile.writeUtf8(text: String) = writeAll(text.toByteArray(Charsets.UTF_8))

@Suppress("MissingUseCall") // finishWrite and failWrite close the stream.
internal fun AtomicFile.writeAll(bytes: ByteArray) {
    val out = startWrite()
    try {
        out.write(bytes)
    } catch (e: IOException) {
        failWrite(out)
        throw e
    }
    finishWrite(out)
}
