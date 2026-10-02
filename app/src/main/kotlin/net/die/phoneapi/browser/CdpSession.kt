package net.die.phoneapi.browser

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson

internal data class CdpEvent(val method: String, val params: JsonObject)

/**
 * One DevTools WebSocket. Commands are matched by id; everything without an id is an [events] item.
 * The reader runs on [dispatcher].
 */
internal class CdpSession(
    input: InputStream,
    output: OutputStream,
    dispatcher: CoroutineContext,
    newKey: () -> String = ::websocketKey,
) : Closeable {
    private val socket = ChromeSocket(input, output, dispatcher, newKey)
    private val pending = HashMap<Int, CompletableDeferred<JsonObject>>()
    private val early = HashMap<Int, Result<JsonObject>>()
    private val gate = Any()
    private val nextId = AtomicInteger(0)
    private val eventsFlow =
        MutableSharedFlow<CdpEvent>(
            replay = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: Flow<CdpEvent> = eventsFlow.asSharedFlow()

    fun open(path: String) {
        socket.handshake(path)
        socket.connect()
        socket.launch { readLoop() }
    }

    suspend fun call(method: String, params: JsonObject? = null): JsonObject =
        try {
            withTimeout(CALL_TIMEOUT_MS) { roundTrip(method, params) }
        } catch (e: TimeoutCancellationException) {
            throw ApiException(503, "cdp_timeout", "DevTools did not answer in time", cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException(
                503,
                "cdp_timeout",
                e.message ?: "DevTools did not answer in time",
                cause = e,
            )
        } catch (e: IOException) {
            throw ApiException(
                502,
                "cdp_error",
                e.message?.takeIf { it.isNotBlank() } ?: DEVTOOLS_CLOSED,
                cause = e,
            )
        }

    override fun close() {
        socket.close()
    }

    private suspend fun roundTrip(method: String, params: JsonObject?): JsonObject {
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        val ready =
            synchronized(gate) {
                early.remove(id)
                    ?: run {
                        pending[id] = deferred
                        null
                    }
            }
        try {
            socket.sendText(command(id, method, params))
            return ready?.getOrThrow() ?: deferred.await()
        } finally {
            synchronized(gate) { pending.remove(id) }
        }
    }

    private suspend fun readLoop() {
        var failure: IOException? = null
        try {
            socket.relayText(::dispatch)
        } catch (e: IOException) {
            failure = e
        } finally {
            failAll(failure ?: IOException("DevTools closed the WebSocket"))
        }
    }

    private suspend fun dispatch(text: String) {
        val message =
            try {
                ApiJson.decodeFromString<Envelope>(text)
            } catch (e: SerializationException) {
                throw IOException("DevTools sent malformed JSON", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("DevTools sent malformed JSON", e)
            }
        val id = message.id
        if (id == null) {
            val method = message.method ?: return
            eventsFlow.emit(CdpEvent(method, message.params ?: JsonObject(emptyMap())))
            return
        }
        val error = message.error
        if (error != null) {
            val reason = error.message ?: "DevTools rejected the command"
            deliver(id, Result.failure(ApiException(502, "cdp_error", reason)))
            return
        }
        deliver(id, Result.success(message.result ?: JsonObject(emptyMap())))
    }

    private fun deliver(id: Int, result: Result<JsonObject>) {
        val waiter =
            synchronized(gate) {
                pending.remove(id)
                    ?: run {
                        early[id] = result
                        null
                    }
            } ?: return
        result.fold(onSuccess = waiter::complete, onFailure = waiter::completeExceptionally)
    }

    private fun failAll(error: IOException) {
        val waiting =
            synchronized(gate) {
                val values = pending.values.toList()
                pending.clear()
                values
            }
        waiting.forEach { it.completeExceptionally(error) }
    }

    private fun command(id: Int, method: String, params: JsonObject?): String = buildJsonObject {
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }
        .toString()

    @Serializable
    private data class Envelope(
        val id: Int? = null,
        val method: String? = null,
        val params: JsonObject? = null,
        val result: JsonObject? = null,
        val error: Rejected? = null,
    )

    @Serializable private data class Rejected(val message: String? = null)

    private companion object {
        const val CALL_TIMEOUT_MS = 15_000L
        const val EVENT_BUFFER = 64
    }
}
