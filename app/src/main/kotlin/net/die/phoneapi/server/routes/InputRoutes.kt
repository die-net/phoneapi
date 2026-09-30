package net.die.phoneapi.server.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.die.phoneapi.AppGraph
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.server.requireScope

/** `POST /v1/input/tap|swipe|gesture|key|text` and `POST /v1/ime/hide`. */
fun Route.inputRoutes(graph: AppGraph) {
    post("/v1/input/tap") {
        call.requireScope(Scope.CONTROL)
        call.respond(graph.input.tap(call.receive<TapRequest>()))
    }
    post("/v1/input/swipe") {
        call.requireScope(Scope.CONTROL)
        call.respond(graph.input.swipe(call.receive<SwipeRequest>()))
    }
    post("/v1/input/gesture") {
        call.requireScope(Scope.CONTROL)
        call.respond(graph.input.gesture(call.receive<GestureRequest>()))
    }
    post("/v1/input/key") {
        call.requireScope(Scope.CONTROL)
        call.respond(graph.input.key(call.receive<KeyRequest>()))
    }
    post("/v1/input/text") {
        call.requireScope(Scope.CONTROL)
        call.respond(graph.input.text(call.receive<TextRequest>()))
    }
    post("/v1/ime/hide") {
        call.requireScope(Scope.CONTROL)
        call.respond(graph.input.hideIme())
    }
}
