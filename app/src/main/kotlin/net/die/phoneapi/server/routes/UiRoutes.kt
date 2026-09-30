package net.die.phoneapi.server.routes

import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SnapshotFormat
import net.die.phoneapi.server.requireScope

/**
 * `GET /v1/ui/snapshot?format=compact|json|both&window=&maxDepth=&includeInvisible=&all=`, `POST
 * /v1/ui/find`, `POST /v1/ui/nodes/{ref}/action` and `GET /v1/screenshot?scale=`.
 */
fun Route.uiRoutes(graph: AppGraph) {
    get("/v1/ui/snapshot") {
        call.requireScope(Scope.OBSERVE)
        val q = call.request.queryParameters
        val options =
            SnapshotOptions(
                format = q["format"]?.let(::parseFormat) ?: SnapshotFormat.COMPACT,
                windowId = q["window"]?.let(::parseWindowId),
                maxDepth = q.int("maxDepth"),
                includeInvisible = q.flag("includeInvisible", default = false),
                allWindows = q.flag("all", default = false),
                autoWake = q.flag("autoWake", default = true),
            )
        call.respond(graph.ui.snapshot(options))
    }
    post("/v1/ui/find") {
        call.requireScope(Scope.OBSERVE)
        call.respond(graph.ui.find(call.receive<FindRequest>()))
    }
    post("/v1/ui/nodes/{ref}/action") {
        call.requireScope(Scope.CONTROL)
        val ref = call.parameters["ref"] ?: throw ApiException.badRequest("ref required")
        call.respond(graph.ui.act(ref, call.receive<NodeActionRequest>()))
    }
    get("/v1/screenshot") {
        call.requireScope(Scope.OBSERVE)
        val scale =
            call.request.queryParameters["scale"]?.let {
                it.toFloatOrNull() ?: throw ApiException.badRequest("scale must be a number")
            } ?: 1f
        call.respondBytes(graph.screenshots.png(scale), ContentType.Image.PNG)
    }
}

private fun parseFormat(value: String): SnapshotFormat =
    SnapshotFormat.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw ApiException.badRequest("format must be compact, json or both")

private fun parseWindowId(value: String): Int =
    value.removePrefix("w").toIntOrNull()
        ?: throw ApiException.badRequest("window must be a window id like 3 or w3")

internal fun Parameters.int(name: String): Int? =
    get(name)?.let { it.toIntOrNull() ?: throw ApiException.badRequest("$name must be an integer") }

/** `?x`, `?x=true` and `?x=1` are true; `?x=false` and `?x=0` are false. */
internal fun Parameters.flag(name: String, default: Boolean): Boolean =
    when (get(name)?.lowercase()) {
        null -> default
        "",
        "1",
        "true",
        "yes" -> true
        "0",
        "false",
        "no" -> false
        else -> throw ApiException.badRequest("$name must be true or false")
    }
