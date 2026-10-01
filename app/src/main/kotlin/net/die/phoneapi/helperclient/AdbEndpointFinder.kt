package net.die.phoneapi.helperclient

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Finds this device's wireless-debugging ports via `getprop`, then mDNS. */
internal class AdbEndpointFinder(
    private val context: Context,
    private val io: CoroutineDispatcher,
) {
    suspend fun connectPort(timeoutMs: Long = CONNECT_WAIT_MS): Int? =
        wait(timeoutMs, pairing = false)

    suspend fun pairingPort(timeoutMs: Long = PAIR_WAIT_MS): Int? = wait(timeoutMs, pairing = true)

    private suspend fun wait(timeoutMs: Long, pairing: Boolean): Int? =
        withContext(io) {
            KadbMdnsAndroid(context).use { mdns ->
                mdns.start()
                poll(mdns, timeoutMs, pairing)
            }
        }

    private suspend fun poll(mdns: KadbMdnsAndroid, timeoutMs: Long, pairing: Boolean): Int? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!pairing)
                tlsPort()?.let {
                    return it
                }
            val discovered =
                if (pairing) mdns.state.value.pairDevices else mdns.state.value.connectDevices
            discovered.firstOrNull()?.port?.let {
                return it
            }
            delay(POLL_MS)
        }
        return null
    }

    private fun tlsPort(): Int? = parseAdbPort(getprop(TLS_PORT))

    private fun getprop(name: String): String? =
        try {
            ProcessBuilder("getprop", name)
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { it.readText() }
        } catch (e: IOException) {
            Log.w(TAG, "getprop $name failed", e)
            null
        }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val TLS_PORT = "service.adb.tls.port"
        const val CONNECT_WAIT_MS = 15_000L
        const val PAIR_WAIT_MS = 8_000L
        const val POLL_MS = 300L
    }
}
