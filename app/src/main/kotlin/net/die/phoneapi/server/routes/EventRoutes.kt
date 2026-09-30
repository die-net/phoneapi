package net.die.phoneapi.server.routes

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.Event
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.requireScope

/**
 * `WS /v1/events?types=a,b` streams [Event]s as JSON text frames. `types` filters by exact type or
 * prefix ending in `.` (e.g. `ime.`).
 */
fun Route.eventRoutes(graph: AppGraph) {
    webSocket("/v1/events") {
        call.requireScope(Scope.OBSERVE)
        val filters =
            call.request.queryParameters["types"]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
        graph.bus.events.collect { event ->
            if (filters == null || filters.any { matchesType(event.type, it) }) {
                send(Frame.Text(ApiJson.encodeToString(Event.serializer(), event)))
            }
        }
    }
}

internal fun matchesType(type: String, filter: String): Boolean =
    if (filter.endsWith('.')) type.startsWith(filter) else type == filter
