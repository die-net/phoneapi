package net.die.phoneapi.browser

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helperclient.HelperConnection

/**
 * Opens an abstract DevTools socket through the helper. [readTimeoutMs] closes the pipe so a read
 * cannot block past it; zero means the caller closes the pipe, which is what a long-lived session
 * needs.
 */
internal class HelperDevtoolsSocket(
    helper: HelperConnection,
    name: String,
    readTimeoutMs: Long = READ_TIMEOUT_MS,
) : DevtoolsSocket {
    private val pipe: ParcelFileDescriptor = open(helper, name)

    override val input: InputStream = FileInputStream(pipe.fileDescriptor)
    override val output: OutputStream = FileOutputStream(pipe.fileDescriptor)

    private val deadline =
        if (readTimeoutMs <= 0) {
            null
        } else {
            Thread {
                try {
                    Thread.sleep(readTimeoutMs)
                    pipe.close()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: IOException) {
                    // The request finished and already closed the socket.
                }
            }
        }

    init {
        deadline?.let {
            it.isDaemon = true
            it.start()
        }
    }

    override fun close() {
        deadline?.interrupt()
        try {
            pipe.close()
        } catch (_: IOException) {
            // The deadline thread may have closed it already.
        }
    }

    private companion object {
        const val TAG = "PhoneApiBrowser"
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
