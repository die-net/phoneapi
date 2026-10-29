package net.die.phoneapi.server.routes

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.LeaseState
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.PointerOp
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.SessionsKey

private val viewers = AtomicLong()

/**
 * `WS /v1/input/pointer`, the viewer's input socket, which holds the screen for the person using
 * it. The first input takes a free screen; while someone else has it, input is dropped and the page
 * is told, and `{"op":"take"}` takes it over. The socket lets the screen go after [VIEWER_IDLE_MS]
 * without input, and when it closes. The server sends `{"op":"lease",...}` when the holder changes.
 */
internal suspend fun serveViewer(services: ServerServices, session: DefaultWebSocketServerSession) {
    val viewer = Holder.Viewer(viewers.incrementAndGet())
    val socket = ViewerSocket(services, session, viewer)
    try {
        socket.run()
    } finally {
        withContext(NonCancellable) { services.lease.forget(viewer) }
    }
}

private class ViewerSocket(
    private val services: ServerServices,
    private val session: DefaultWebSocketServerSession,
    private val viewer: Holder.Viewer,
) {
    private val lease = services.lease
    private val sessions = session.call.application.attributes[SessionsKey]
    private val frames = Channel<PointerFrame>(Channel.UNLIMITED)
    private val down = HashSet<Int>()

    @Volatile private var lastInputMs = System.currentTimeMillis()

    suspend fun run() = coroutineScope {
        val status = launch { lease.state.collect { onLease(it) } }
        val idle = launch { releaseWhenIdle() }
        launch {
            try {
                read()
            } finally {
                frames.close()
            }
        }
        // The service cancels any contact still down when the channel closes.
        services.input.pointer(frames)
        status.cancel()
        idle.cancel()
    }

    private suspend fun read() {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Binary -> pasteImage(frame.readBytes())
                is Frame.Text -> if (!handle(parse(frame) ?: return)) return
                is Frame.Close,
                is Frame.Ping,
                is Frame.Pong -> Unit
            }
        }
    }

    /** The frame's JSON object, or null for a bad frame, which ends the socket. */
    private fun parse(frame: Frame.Text): JsonObject? =
        try {
            ApiJson.parseToJsonElement(frame.readText()).jsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Handles one message. False for a bad frame, which ends the socket. */
    private suspend fun handle(message: JsonObject): Boolean {
        val op = (message["op"] as? JsonPrimitive)?.contentOrNull
        when (op) {
            "take" -> {
                touched()
                lease.takeOver(viewer)
            }
            "key" -> act(message) { services.input.key(decode<KeyRequest>(message)) }
            "text" -> act(message) { services.input.text(decode<TextRequest>(message)) }
            "orientation" ->
                act(message) { services.power.orientation(decode<OrientationRequest>(message)) }
            "paste" ->
                act(message) {
                    services.clipboard.write(
                        decode<ClipboardWriteRequest>(message).copy(paste = true)
                    )
                }
            "copy" -> act(message) { copy(message) }
            else -> {
                val frame =
                    try {
                        ApiJson.decodeFromJsonElement(serializer<PointerFrame>(), message)
                    } catch (_: SerializationException) {
                        return false
                    } catch (_: IllegalArgumentException) {
                        return false
                    }
                pointer(frame)
            }
        }
        return true
    }

    /**
     * Forwards a contact while this viewer holds the screen, and drops a whole gesture otherwise.
     */
    private suspend fun pointer(frame: PointerFrame) {
        touched()
        val forward =
            when (frame.op) {
                PointerOp.DOWN -> {
                    val ok = lease.viewerInput(viewer)
                    if (ok) synchronized(down) { down += frame.id }
                    ok
                }
                PointerOp.MOVE -> synchronized(down) { frame.id in down }
                PointerOp.UP -> synchronized(down) { down.remove(frame.id) }
                PointerOp.CANCEL -> synchronized(down) { down.isNotEmpty().also { down.clear() } }
            }
        if (forward) frames.send(frame)
    }

    private suspend fun act(message: JsonObject, block: suspend () -> Any) {
        touched()
        if (!lease.viewerInput(viewer)) return
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            send(
                buildJsonObject {
                    put("op", "error")
                    message["id"]?.let { put("id", it) }
                    put("message", e.message ?: e.error)
                }
            )
        } catch (e: SerializationException) {
            send(
                buildJsonObject {
                    put("op", "error")
                    message["id"]?.let { put("id", it) }
                    put(
                        "message",
                        "Bad ${(message["op"] as? JsonPrimitive)?.contentOrNull.orEmpty()}: " +
                            e.message.orEmpty(),
                    )
                }
            )
        }
    }

    /** Copies the phone's selection, or reads its clipboard when nothing is selected. */
    private suspend fun copy(message: JsonObject) {
        val clip = services.clipboard.read(ClipboardReadRequest(copy = true))
        val image =
            if (clip.text == null && clip.mimeTypes.any { it.startsWith("image/") }) {
                services.clipboard.image()
            } else {
                null
            }
        send(
            buildJsonObject {
                put("op", "clipboard")
                message["id"]?.let { put("id", it) }
                clip.text?.let { put("text", it) }
                image?.let { put("image", it.mimeType) }
                put("changed", clip.changed == true)
            }
        )
        // The only binary frame this socket sends, so the page takes the next one as the image.
        if (image != null) session.outgoing.send(Frame.Binary(true, image.bytes))
    }

    /** A binary frame is an image to paste. */
    private suspend fun pasteImage(bytes: ByteArray) {
        act(PASTE) { services.clipboard.writeImage(bytes, paste = true) }
    }

    private suspend fun onLease(state: LeaseState) {
        // Another viewer took the screen mid-gesture: release the finger.
        val lost =
            state.holder != viewer && synchronized(down) { down.isNotEmpty().also { down.clear() } }
        if (lost) frames.send(PointerFrame(PointerOp.CANCEL))
        send(
            buildJsonObject {
                put("op", "lease")
                put("holder", kind(state.holder))
                put(
                    "heldMs",
                    if (state.holder == null) 0 else System.currentTimeMillis() - state.sinceMs,
                )
                if (state.takenFrom != null) put("takenFrom", "agent")
            }
        )
    }

    private suspend fun releaseWhenIdle() {
        while (true) {
            delay(IDLE_CHECK_MS)
            val idle = System.currentTimeMillis() - lastInputMs > VIEWER_IDLE_MS
            if (idle && lease.holds(viewer) && synchronized(down) { down.isEmpty() }) {
                lease.release(viewer)
            }
        }
    }

    private fun kind(holder: Holder?): String =
        when (holder) {
            null -> "none"
            viewer -> "you"
            is Holder.Viewer -> "person"
            is Holder.Session -> if (sessions.isRest(holder.id)) "script" else "agent"
        }

    private fun touched() {
        lastInputMs = System.currentTimeMillis()
    }

    private suspend fun send(message: JsonObject) {
        session.outgoing.send(Frame.Text(message.toString()))
    }

    private inline fun <reified T> decode(message: JsonObject): T =
        ApiJson.decodeFromJsonElement(serializer<T>(), JsonObject(message - "op"))
}

private val PASTE = buildJsonObject { put("op", "paste") }

/** How long a viewer keeps the screen without input. */
internal const val VIEWER_IDLE_MS = 30_000L
private const val IDLE_CHECK_MS = 1_000L
