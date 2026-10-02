package net.die.phoneapi.browser

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A DevTools connection whose peer is adbd. [readTimeoutMs] closes the pipe when a read sits too
 * long. Zero waits until the caller closes it.
 */
internal class AdbDevtoolsSocket(
    inputStream: InputStream,
    outputStream: OutputStream,
    private val release: () -> Unit,
    readTimeoutMs: Long,
    dispatcher: CoroutineDispatcher,
) : DevtoolsSocket {
    private val closed = AtomicBoolean(false)
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

    override val input: InputStream = inputStream
    override val output: OutputStream = outputStream

    override fun close() {
        watchdogJob?.cancel()
        watchdog.cancel()
        closePipe()
    }

    private fun closePipe() {
        if (!closed.compareAndSet(false, true)) return
        try {
            release()
        } catch (_: IOException) {
            // Already closed.
        }
    }
}
