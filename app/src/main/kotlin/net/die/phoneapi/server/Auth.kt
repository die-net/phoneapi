package net.die.phoneapi.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TokenInfo

private val TokenKey = AttributeKey<TokenInfo>("phoneapi.token")

/** `GET /viewer`. The page and its WebSocket upgrades are the only query-token callers. */
internal const val VIEWER_PATH = "/viewer"

class AuthConfig {
    lateinit var tokens: TokenGateway
}

/**
 * Every request needs a valid bearer token. `access_token` is accepted only on [VIEWER_PATH] and on
 * WebSocket upgrades, because a query parameter is copied into logs and history. Unauthenticated
 * requests get an empty 404, so the server reveals as little as possible about what it is.
 */
val BearerAuth =
    createApplicationPlugin("BearerAuth", ::AuthConfig) {
        val tokens = pluginConfig.tokens
        onCall { call ->
            val header =
                call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring(BEARER_PREFIX_LENGTH)
                    ?.trim()
            val query =
                call.request.queryParameters["access_token"]?.trim()?.takeIf { it.isNotEmpty() }
            val secret = header ?: query?.takeIf { call.acceptsAccessTokenQuery() }
            val info = secret?.let(tokens::authenticate)
            if (info == null) {
                call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
            } else {
                call.attributes.put(TokenKey, info)
            }
        }
    }

private const val BEARER_PREFIX_LENGTH = 7

internal fun ApplicationCall.acceptsAccessTokenQuery(): Boolean {
    val upgrade = request.headers[HttpHeaders.Upgrade]
    if (upgrade?.contains("websocket", ignoreCase = true) == true) return true
    return request.httpMethod == HttpMethod.Get && request.path() == VIEWER_PATH
}

val ApplicationCall.token: TokenInfo
    get() = attributes[TokenKey]

private class ScopePluginConfig {
    lateinit var scope: Scope
}

private val RequireScope =
    createRouteScopedPlugin("RequireScope", ::ScopePluginConfig) {
        val required = pluginConfig.scope
        onCall { call ->
            if (call.isHandled) return@onCall
            if (required !in call.token.scopes) throw ApiException.forbidden(required)
        }
    }

/**
 * Child routes require [scope]. A route added outside [scoped] has no scope, which the route test
 * rejects.
 */
fun Route.scoped(scope: Scope, build: Route.() -> Unit): Route {
    val required = scope
    val child = createChild(ScopeRouteSelector(required))
    child.install(RequireScope) { this.scope = required }
    child.build()
    return child
}

/** Marks a [scoped] block. Identity equality keeps two blocks with the same scope from merging. */
internal class ScopeRouteSelector(val scope: Scope) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(scope:${scope.name.lowercase()})"
}
