package net.die.phoneapi.server.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped

/** `POST /v1/input/tap|swipe|gesture|key|text` and `POST /v1/ime/hide`. */
fun Route.inputRoutes(services: ServerServices) {
    scoped(Scope.CONTROL) {
        post("/v1/input/tap") { call.respond(services.input.tap(call.receive<TapRequest>())) }
        post("/v1/input/swipe") { call.respond(services.input.swipe(call.receive<SwipeRequest>())) }
        post("/v1/input/gesture") {
            call.respond(services.input.gesture(call.receive<GestureRequest>()))
        }
        post("/v1/input/key") { call.respond(services.input.key(call.receive<KeyRequest>())) }
        post("/v1/input/text") { call.respond(services.input.text(call.receive<TextRequest>())) }
        post("/v1/ime/hide") { call.respond(services.input.hideIme()) }
    }
}
