package net.die.phoneapi.server.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped

/**
 * `POST /v1/input/tap|swipe|gesture|key|text`, `WS /v1/input/pointer`, and `POST
 * /v1/ime/hide|show`.
 */
fun Route.inputRoutes(services: ServerServices) {
    scoped(Scope.CONTROL) {
        post("/v1/input/tap") { call.respond(services.input.tap(call.receive<TapRequest>())) }
        post("/v1/input/swipe") { call.respond(services.input.swipe(call.receive<SwipeRequest>())) }
        post("/v1/input/gesture") {
            call.respond(services.input.gesture(call.receive<GestureRequest>()))
        }
        webSocket("/v1/input/pointer") { servePointer(services, incoming) }
        post("/v1/input/key") { call.respond(services.input.key(call.receive<KeyRequest>())) }
        post("/v1/input/text") { call.respond(services.input.text(call.receive<TextRequest>())) }
        post("/v1/ime/hide") { call.respond(services.input.hideIme()) }
        post("/v1/ime/show") {
            call.respond(services.input.showIme(call.receive<ImeShowRequest>()))
        }
    }
}

/**
 * Forwards JSON text frames to [ServerServices.input]. A bad frame or the socket closing closes the
 * channel. The service cancels any contact that is still down, which releases the touch.
 */
private suspend fun servePointer(
    services: ServerServices,
    incoming: ReceiveChannel<Frame>,
) {
    val frames = Channel<PointerFrame>(Channel.UNLIMITED)
    try {
        coroutineScope {
            launch {
                try {
                    readPointerFrames(incoming, frames)
                } finally {
                    frames.close()
                }
            }
            services.input.pointer(frames)
        }
    } finally {
        frames.close()
    }
}

private suspend fun readPointerFrames(
    incoming: ReceiveChannel<Frame>,
    frames: SendChannel<PointerFrame>,
) {
    for (frame in incoming) {
        if (frame !is Frame.Text) continue
        val parsed =
            try {
                ApiJson.decodeFromString(serializer<PointerFrame>(), frame.readText())
            } catch (_: SerializationException) {
                return
            }
        frames.send(parsed)
    }
}
