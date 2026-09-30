package net.die.phoneapi.server.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.die.phoneapi.AppGraph
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.server.requireScope

/**
 * `POST /v1/wait` blocks until the conditions hold, and can return a fresh snapshot with the result
 * so one call replaces a wait followed by a snapshot.
 */
fun Route.waitRoutes(graph: AppGraph) {
    post("/v1/wait") {
        call.requireScope(Scope.OBSERVE)
        call.respond(graph.waits.wait(call.receive<WaitRequest>()))
    }
}
