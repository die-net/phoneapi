package net.die.phoneapi.browser

import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import io.ktor.websocket.Frame
import io.ktor.websocket.RawWebSocket
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A DevTools socket after the HTTP upgrade. Framing is Ktor's [io.ktor.websocket.RawWebSocket]; the
 * caller owns the streams and closes them.
 */
internal class ChromeSocket(
    private val input: InputStream,
    private val output: OutputStream,
    context: CoroutineContext,
    private val newKey: () -> String = ::websocketKey,
) : Closeable {
    // Independent of the caller's job so a single command timeout does not cancel the reader.
    private val scope = CoroutineScope(context + SupervisorJob() + CoroutineName("devtools"))
    private var webSocket: WebSocketSession? = null

    fun handshake(path: String) = handshakeWebSocket(input, output, path, newKey)

    fun connect() {
        check(webSocket == null) { "DevTools WebSocket is already connected" }
        webSocket =
            RawWebSocket(
                input = input.toByteReadChannel(context = scope.coroutineContext),
                output = output.asByteWriteChannel(),
                masking = true,
                coroutineContext = scope.coroutineContext,
            )
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    suspend fun sendText(text: String) {
        socket().send(Frame.Text(text))
    }

    /** Text frames in order. Ping frames are answered and not delivered. */
    suspend fun relayText(onText: suspend (String) -> Unit) {
        val ws = socket()
        for (frame in ws.incoming) {
            when (frame) {
                is Frame.Text -> onText(frame.readText())
                is Frame.Ping -> ws.send(Frame.Pong(frame.data))
                is Frame.Close -> break
                is Frame.Binary,
                is Frame.Pong -> Unit
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private fun socket(): WebSocketSession =
        webSocket ?: error("DevTools WebSocket is not connected")
}
