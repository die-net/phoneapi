package net.die.phoneapi.server.routes

import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ScreenshotScale
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.core.windowId
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SnapshotFormat
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped

/**
 * `GET /v1/ui/snapshot?format=compact|json|both&window=&maxDepth=&includeInvisible=&all=`, `POST
 * /v1/ui/find`, `POST /v1/ui/nodes/{ref}/action` and `GET /v1/screenshot?scale=`.
 */
fun Route.uiRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE) {
        get("/v1/ui/snapshot") {
            val q = call.request.queryParameters
            val options =
                SnapshotOptions(
                    format = q["format"]?.let(::parseFormat) ?: SnapshotFormat.COMPACT,
                    windowId = q["window"]?.let(::windowId),
                    maxDepth = q.int("maxDepth"),
                    includeInvisible = q.flag("includeInvisible", default = false),
                    allWindows = q.flag("all", default = false),
                    autoWake = q.flag("autoWake", default = true),
                )
            call.respond(services.ui.snapshot(options))
        }
        post("/v1/ui/find") { call.respond(services.ui.find(call.receive<FindRequest>())) }
        get("/v1/screenshot") {
            val scale =
                call.request.queryParameters["scale"]?.let {
                    it.toFloatOrNull() ?: throw ApiException.badRequest("scale must be a number")
                } ?: ScreenshotScale.REST
            call.respondBytes(services.screenshots(scale), ContentType.Image.PNG)
        }
    }
    scoped(Scope.CONTROL) {
        post("/v1/ui/nodes/{ref}/action") {
            val ref = call.parameters["ref"] ?: throw ApiException.badRequest("ref required")
            call.respond(services.ui.act(ref, call.receive<NodeActionRequest>()))
        }
    }
}

private fun parseFormat(value: String): SnapshotFormat =
    SnapshotFormat.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw ApiException.badRequest("format must be compact, json or both")

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
