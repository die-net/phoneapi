package net.die.phoneapi.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.mcp.McpSessions
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.scoped

/** `GET /v1/sessions` and `DELETE /v1/sessions/{hash}`, for ending a stuck MCP session. */
fun Route.sessionRoutes(sessions: McpSessions) {
    scoped(Scope.ADMIN) {
        get("/v1/sessions") { call.respond(sessions.list()) }
        delete("/v1/sessions/{hash}") {
            val hash = call.parameters["hash"] ?: throw ApiException.badRequest("hash required")
            if (!sessions.endByHash(hash)) throw ApiException.notFound("session $hash")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
