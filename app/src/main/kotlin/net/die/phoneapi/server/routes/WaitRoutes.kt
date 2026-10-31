package net.die.phoneapi.server.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.anyToken
import net.die.phoneapi.server.token
import net.die.phoneapi.server.waits
import net.die.phoneapi.server.withScreen
import net.die.phoneapi.wait.requireWaitAccess
import net.die.phoneapi.wait.usesScreen

/**
 * `POST /v1/wait` blocks until the conditions hold, and can return a fresh snapshot with the result
 * so one call replaces a wait followed by a snapshot. Its scope depends on its conditions, so the
 * route takes any token and [requireWaitAccess] checks observe and browser against the request.
 */
fun Route.waitRoutes(services: ServerServices) {
    anyToken {
        post("/v1/wait") {
            val request = call.receive<WaitRequest>()
            val scopes = call.token.scopes
            requireWaitAccess(request, scopes)
            val screen = request.snapshot || (request.all + request.any).any(::usesScreen)
            call.respond(
                withScreen(call, services, screen) {
                    call.waits(services, it).wait(request, scopes)
                }
            )
        }
    }
}
