package net.die.phoneapi.server.routes

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
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
    val viewer = ViewerHtml(services.viewerHtml)
    scoped(Scope.STREAM) {
        get(VIEWER_PATH) { call.respondBytes(viewer.bytes(), ContentType.Text.Html) }
        webSocket("/v1/stream/video") {
            services.power().wake()
            services.video.serve(this, videoSpec(call))
        }
        webSocket("/v1/stream/audio") {
            services.power().wake()
            services.audio.serve(this)
        }
    }
}

/** The viewer page, read once the first time a client asks for it. */
private class ViewerHtml(private val load: suspend () -> ByteArray) {
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

private fun queryInt(call: ApplicationCall, name: String, default: Int, min: Int, max: Int): Int {
    val raw = call.request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: throw ApiException.badRequest("$name is not an integer")
    if (value !in min..max) throw ApiException.badRequest("$name must be $min..$max")
    return value
}
