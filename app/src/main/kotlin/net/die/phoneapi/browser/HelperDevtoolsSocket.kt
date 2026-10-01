package net.die.phoneapi.browser

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helperclient.HelperConnection

/**
 * Opens an abstract DevTools socket through the helper. [readTimeoutMs] is how long a blocked read
 * may sit before the pipe is closed. The helper owns the socket, and this process is not allowed to
 * `setsockopt` it, so the deadline closes the pipe from a coroutine. Zero waits until the caller
 * closes it, which a long-lived session needs.
 */
internal class HelperDevtoolsSocket(
    helper: HelperConnection,
    name: String,
    readTimeoutMs: Long = READ_TIMEOUT_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DevtoolsSocket {
    private val pipe: ParcelFileDescriptor = open(helper, name)

    // Local scope so closing this socket does not cancel the caller's scope.
    private val watchdog = CoroutineScope(SupervisorJob() + dispatcher)
    private val watchdogJob: Job? =
        if (readTimeoutMs > 0) {
            watchdog.launch {
                delay(readTimeoutMs)
                closePipe()
            }
        } else {
            null
        }

    override val input: InputStream = FileInputStream(pipe.fileDescriptor)
    override val output: OutputStream = FileOutputStream(pipe.fileDescriptor)

    override fun close() {
        watchdogJob?.cancel()
        watchdog.cancel()
        closePipe()
    }

    private fun closePipe() {
        try {
            pipe.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 15_000L

        private fun open(helper: HelperConnection, name: String): ParcelFileDescriptor {
            val opened =
                try {
                    helper.require().openAbstractSocket(name)
                } catch (e: RemoteException) {
                    throw connectFailed(name, e)
                } catch (e: IllegalStateException) {
                    throw connectFailed(name, e)
                }
            return opened ?: throw ApiException(502, "cdp_error", "Could not connect to $name")
        }

        private fun connectFailed(name: String, error: Exception): ApiException =
            ApiException(
                502,
                "cdp_error",
                "Could not connect to $name: ${error.message.orEmpty()}",
                cause = error,
            )
    }
}
