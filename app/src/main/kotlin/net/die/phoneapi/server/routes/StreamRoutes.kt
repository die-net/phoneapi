package net.die.phoneapi.server.routes

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.VIEWER_PATH
import net.die.phoneapi.server.scoped
import net.die.phoneapi.stream.DEFAULT_BIT_RATE
import net.die.phoneapi.stream.DEFAULT_FPS
import net.die.phoneapi.stream.DEFAULT_MAX_SIZE
import net.die.phoneapi.stream.MAX_BIT_RATE
import net.die.phoneapi.stream.MAX_FPS
import net.die.phoneapi.stream.MAX_MAX_SIZE
import net.die.phoneapi.stream.MIN_BIT_RATE
import net.die.phoneapi.stream.MIN_FPS
import net.die.phoneapi.stream.MIN_MAX_SIZE
import net.die.phoneapi.stream.VideoSpec

/** WebCodecs viewer, plus the video and audio websockets it plays. */
fun Route.streamRoutes(services: ServerServices) {
    val viewer = CachedBytes(services.viewerHtml)
    scoped(Scope.STREAM) {
        get(VIEWER_PATH) { call.respondBytes(viewer.bytes(), ContentType.Text.Html) }
        webSocket("/v1/stream/video") {
            if (!services.device.capabilities().streamVideoMirror) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Video needs the shell helper"))
                return@webSocket
            }
            try {
                services.power.wake()
                services.video.serve(this, videoSpec(call))
            } catch (e: ApiException) {
                failVideo(this, e)
            }
        }
        webSocket("/v1/stream/audio") {
            if (!services.device.capabilities().streamAudioSubmix) {
                close(
                    CloseReason(
                        CloseReason.Codes.CANNOT_ACCEPT,
                        "Audio needs Android 11 or later and the shell helper",
                    )
                )
                return@webSocket
            }
            services.power.wake()
            services.audio.serve(this)
        }
    }
}

/** A bundled page, read once the first time a client asks for it. */
internal class CachedBytes(private val load: suspend () -> ByteArray) {
    private val gate = Mutex()

    @Volatile private var cached: ByteArray? = null

    suspend fun bytes(): ByteArray {
        cached?.let {
            return it
        }
        return gate.withLock { cached ?: load().also { cached = it } }
    }
}

private fun videoSpec(call: ApplicationCall): VideoSpec =
    VideoSpec(
        maxSize = queryInt(call, "maxSize", DEFAULT_MAX_SIZE, MIN_MAX_SIZE, MAX_MAX_SIZE),
        fps = queryInt(call, "fps", DEFAULT_FPS, MIN_FPS, MAX_FPS),
        bitRate = queryInt(call, "bitRate", DEFAULT_BIT_RATE, MIN_BIT_RATE, MAX_BIT_RATE),
    )

/**
 * Close reason text is at most 123 bytes, so a long codec message has to be cut on a UTF-8
 * boundary. Logging is best-effort: `android.util.Log` throws in JVM unit tests.
 */
private suspend fun failVideo(session: DefaultWebSocketServerSession, error: ApiException) {
    session.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, closeReasonText(error.message)))
    runCatching { Log.w(TAG, "video stream failed", error) }
}

private fun closeReasonText(message: String?): String {
    val text = message?.takeIf { it.isNotBlank() } ?: "The video encoder could not start"
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= CLOSE_REASON_BYTES) return text
    var end = CLOSE_REASON_BYTES
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.copyOf(end).toString(Charsets.UTF_8).ifBlank {
        "The video encoder could not start"
    }
}

private const val TAG = "PhoneApiStream"
private const val CLOSE_REASON_BYTES = 123

private fun queryInt(call: ApplicationCall, name: String, default: Int, min: Int, max: Int): Int {
    val raw = call.request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: throw ApiException.badRequest("$name is not an integer")
    if (value !in min..max) throw ApiException.badRequest("$name must be $min..$max")
    return value
}
