package net.die.phoneapi.server.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped

/**
 * `GET /v1/apps?launchable=`, `POST /v1/apps/{pkg}/launch|stop|clear` and `POST /v1/intents`.
 * Listing defaults to apps with a launcher activity; pass `launchable=false` for every package.
 */
fun Route.appRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE) {
        get("/v1/apps") {
            val launchableOnly = call.request.queryParameters.flag("launchable", default = true)
            call.respond(services.apps.list(launchableOnly))
        }
    }
    scoped(Scope.CONTROL) {
        post("/v1/apps/{pkg}/launch") {
            call.respond(
                services.apps.launch(call.packageName(), bodyOrDefault(call, LaunchRequest()))
            )
        }
        post("/v1/apps/{pkg}/stop") { call.respond(services.apps.stop(call.packageName())) }
        post("/v1/apps/{pkg}/clear") { call.respond(services.apps.clear(call.packageName())) }
        post("/v1/intents") { call.respond(services.apps.intent(call.receive<IntentRequest>())) }
    }
}

private fun ApplicationCall.packageName(): String =
    parameters["pkg"]?.takeIf { it.isNotBlank() }
        ?: throw ApiException.badRequest("A package name is required")
