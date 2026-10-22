package net.die.phoneapi.server.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped
import net.die.phoneapi.server.withScreen

/**
 * `POST /v1/input/tap|swipe|gesture|key|text`, `WS /v1/input/pointer`, and `POST
 * /v1/ime/hide|show`.
 */
fun Route.inputRoutes(services: ServerServices) {
    scoped(Scope.CONTROL) {
        post("/v1/input/tap") {
            val request = call.receive<TapRequest>()
            call.respond(withScreen(call, services) { services.input.tap(request) })
        }
        post("/v1/input/swipe") {
            val request = call.receive<SwipeRequest>()
            call.respond(withScreen(call, services) { services.input.swipe(request) })
        }
        post("/v1/input/gesture") {
            val request = call.receive<GestureRequest>()
            call.respond(withScreen(call, services) { services.input.gesture(request) })
        }
        webSocket("/v1/input/pointer") { serveViewer(services, this) }
        post("/v1/input/key") {
            val request = call.receive<KeyRequest>()
            call.respond(withScreen(call, services) { services.input.key(request) })
        }
        post("/v1/input/text") {
            val request = call.receive<TextRequest>()
            call.respond(withScreen(call, services) { services.input.text(request) })
        }
        post("/v1/ime/hide") {
            call.respond(withScreen(call, services) { services.input.hideIme() })
        }
        post("/v1/ime/show") {
            val request = call.receive<ImeShowRequest>()
            call.respond(withScreen(call, services) { services.input.showIme(request) })
        }
    }
}
