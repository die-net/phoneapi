package net.die.phoneapi.browser

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson

/**
 * One DevTools WebSocket with a reader thread. Commands wait for their id; events are delivered to
 * [onEvent] on the reader thread.
 */
internal class CdpSession(private val socket: WebSocketClient) {
    private val pending = ConcurrentHashMap<Int, CancellableContinuation<JsonObject>>()
    private val nextId = AtomicInteger(0)
    private var onEvent: (String, JsonObject) -> Unit = { _, _ -> }
    private var reader: Thread? = null

    fun onEvent(handler: (String, JsonObject) -> Unit) {
        onEvent = handler
    }

    fun start() {
        val thread = Thread(::readLoop)
        thread.isDaemon = true
        thread.name = "cdp"
        reader = thread
        thread.start()
    }

    suspend fun call(method: String, params: JsonObject? = null): JsonObject =
        try {
            withTimeout(CALL_TIMEOUT_MS) { send(method, params) }
        } catch (e: TimeoutCancellationException) {
            throw ApiException(503, "cdp_timeout", "DevTools did not answer in time", cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(
                502,
                "cdp_error",
                e.message ?: "DevTools connection failed",
                cause = e,
            )
        }

    private suspend fun send(method: String, params: JsonObject?): JsonObject =
        suspendCancellableCoroutine { cont ->
            val id = nextId.incrementAndGet()
            pending[id] = cont
            cont.invokeOnCancellation { pending.remove(id) }
            try {
                socket.sendText(command(id, method, params))
            } catch (e: IOException) {
                pending.remove(id)
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    private fun readLoop() {
        try {
            while (true) dispatch(message())
        } catch (e: IOException) {
            failAll(e)
        }
    }

    private fun dispatch(message: JsonObject) {
        val id = (message["id"] as? JsonPrimitive)?.intOrNull
        if (id == null) {
            val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return
            val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
            onEvent(method, params)
            return
        }
        val cont = pending.remove(id) ?: return
        if (!cont.isActive) return
        val error = message["error"]?.jsonObject
        if (error != null) {
            val text = error["message"]?.jsonPrimitive?.content ?: "DevTools rejected the command"
            cont.resumeWithException(ApiException(502, "cdp_error", text))
            return
        }
        cont.resumeWith(Result.success(message["result"] as? JsonObject ?: JsonObject(emptyMap())))
    }

    private fun failAll(error: IOException) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { cont ->
            if (cont.isActive) cont.resumeWithException(error)
        }
    }

    private fun message(): JsonObject =
        try {
            ApiJson.parseToJsonElement(socket.nextText()).jsonObject
        } catch (e: SerializationException) {
            throw IOException("DevTools sent malformed JSON", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("DevTools sent malformed JSON", e)
        }

    private fun command(id: Int, method: String, params: JsonObject?): String = buildJsonObject {
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }
        .toString()

    private companion object {
        const val CALL_TIMEOUT_MS = 15_000L
    }
}
