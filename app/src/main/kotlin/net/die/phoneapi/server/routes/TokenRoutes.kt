package net.die.phoneapi.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.CreateTokenRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.requireScope

fun Route.tokenRoutes(graph: AppGraph) {
    get("/v1/tokens") {
        call.requireScope(Scope.ADMIN)
        call.respond(graph.tokens.list())
    }
    post("/v1/tokens") {
        call.requireScope(Scope.ADMIN)
        val req = call.receive<CreateTokenRequest>()
        if (req.scopes.isEmpty()) throw ApiException.badRequest("At least one scope is required")
        call.respond(HttpStatusCode.Created, graph.tokens.create(req.name, req.scopes))
    }
    delete("/v1/tokens/{id}") {
        call.requireScope(Scope.ADMIN)
        val id = call.parameters["id"] ?: throw ApiException.badRequest("id required")
        if (!graph.tokens.revoke(id)) throw ApiException.notFound("token $id")
        call.respond(HttpStatusCode.NoContent)
    }
}
