package net.die.phoneapi.server.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.openapi.phoneApiOpenApi
import net.die.phoneapi.server.scoped

/** `GET /v1/openapi.json` — the OpenAPI document generated from the api-model types. */
fun Route.openApiRoutes() {
    scoped(Scope.OBSERVE) {
        get("/v1/openapi.json") { call.respond(phoneApiOpenApi()) }
    }
}
