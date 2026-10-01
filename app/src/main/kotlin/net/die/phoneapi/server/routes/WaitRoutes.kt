package net.die.phoneapi.server.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped
import net.die.phoneapi.server.token
import net.die.phoneapi.wait.requireWaitAccess

/**
 * `POST /v1/wait` blocks until the conditions hold, and can return a fresh snapshot with the result
 * so one call replaces a wait followed by a snapshot. Browser conditions also need the browser
 * scope; [net.die.phoneapi.core.WaitService] enforces that.
 */
fun Route.waitRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE) {
        post("/v1/wait") {
            val request = call.receive<WaitRequest>()
            val scopes = call.token.scopes
            requireWaitAccess(request, scopes)
            call.respond(services.waits.wait(request, scopes))
        }
    }
}
