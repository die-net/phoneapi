package net.die.phoneapi.server.routes

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.requireScope
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
fun Route.streamRoutes(graph: AppGraph) {
    get("/viewer") {
        call.requireScope(Scope.STREAM)
        val html = graph.context.assets.open("viewer.html").use { it.readBytes() }
        call.respondBytes(html, ContentType.Text.Html)
    }
    webSocket("/v1/stream/video") {
        call.requireScope(Scope.STREAM)
        graph.requirePower().wake()
        graph.video.serve(this, videoSpec(call))
    }
    webSocket("/v1/stream/audio") {
        call.requireScope(Scope.STREAM)
        graph.requirePower().wake()
        graph.audio.serve(this)
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
