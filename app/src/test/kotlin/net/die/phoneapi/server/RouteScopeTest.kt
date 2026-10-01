package net.die.phoneapi.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpHeaderRouteSelector
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.ApiError
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.openapi.ApiMethod
import net.die.phoneapi.model.openapi.phoneApiEndpoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteScopeTest {
    @Test
    fun `routes match openapi scopes`() = testApplication {
        val api = FakeApi()
        val holding =
            Scope.entries.associateWith { scope ->
                api.tokens.issue("has-$scope", setOf(scope))
            }
        val missing =
            Scope.entries.associateWith { scope ->
                api.tokens.issue("missing-$scope", Scope.entries.toSet() - scope)
            }
        lateinit var app: Application
        application {
            app = this
            phoneApiModule(api.services)
        }
        apiClient().use { client ->
            client.get("/v1/device")
            val routing = app.plugin(RoutingRoot) as RoutingNode
            val routes = routing.scopedRoutes()
            val declared = phoneApiEndpoints.map { RegisteredRoute(it.method, it.path, it.scope) }
            assertEquals(declared.toSet(), routes.toSet())
            val unscoped =
                routing.getAllRoutes().filter { it.declaredScope() == null }.map { it.routePath() }
            assertTrue(unscoped.all { it in UNSCOPED }, "routes without a scope: $unscoped")

            routes.forEach { route ->
                val denied = request(client, route, missing.getValue(route.scope))
                assertEquals(HttpStatusCode.Forbidden, denied.status, route.toString())
                if (route.method != ApiMethod.WEBSOCKET) {
                    val error = ApiJson.decodeFromString(ApiError.serializer(), denied.body)
                    assertEquals("forbidden", error.error, route.toString())
                }
                val allowed = request(client, route, holding.getValue(route.scope))
                assertTrue(
                    allowed.status != HttpStatusCode.Forbidden,
                    "$route -> ${allowed.status} ${allowed.body}",
                )
            }
        }
    }
}

/** MCP checks scopes per tool; pairing runs without a token while its window is open. */
private val UNSCOPED = setOf("/mcp", "/pair", "/v1/pair", "/v1/pair/{id}")

private data class RegisteredRoute(val method: ApiMethod, val path: String, val scope: Scope)

private data class CallResult(val status: HttpStatusCode, val body: String)

private suspend fun request(
    client: HttpClient,
    route: RegisteredRoute,
    secret: String,
): CallResult {
    val path =
        route.path.replace("{ref}", "e1").replace("{pkg}", "com.example").replace("{id}", "abc")
    if (route.method == ApiMethod.WEBSOCKET) {
        // The test engine reports a rejected upgrade as "WebSocket connection failed" and does not
        // surface the 403. A handshake that completes is the scope being accepted.
        return try {
            withTimeout(5.seconds) { client.webSocket(path, { bearer(secret) }) {} }
            CallResult(HttpStatusCode.SwitchingProtocols, "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            CallResult(e.response.status, e.response.bodyAsText())
        } catch (_: IllegalStateException) {
            CallResult(HttpStatusCode.Forbidden, "")
        }
    }
    val response =
        when (route.method) {
            ApiMethod.GET -> client.get(path) { bearer(secret) }
            ApiMethod.POST -> client.post(path) { json(secret) }
            ApiMethod.PUT -> client.put(path) { json(secret) }
            ApiMethod.DELETE -> client.delete(path) { bearer(secret) }
            ApiMethod.WEBSOCKET -> error(route)
        }
    return CallResult(response.status, response.bodyAsText())
}

private fun HttpRequestBuilder.json(secret: String) {
    bearer(secret)
    contentType(ContentType.Application.Json)
    setBody("{}")
}

private fun RoutingNode.scopedRoutes(): List<RegisteredRoute> =
    getAllRoutes().mapNotNull { node ->
        val scope = node.declaredScope() ?: return@mapNotNull null
        RegisteredRoute(node.routeMethod(), node.routePath(), scope)
    }

private fun RoutingNode.declaredScope(): Scope? =
    generateSequence(this) { it.parent }
        .mapNotNull { (it.selector as? ScopeRouteSelector)?.scope }
        .firstOrNull()

private fun RoutingNode.routeMethod(): ApiMethod {
    val selectors = generateSequence(this) { it.parent }.mapNotNull { it.selector }.toList()
    val websocket = selectors.any { selector ->
        selector is HttpHeaderRouteSelector &&
            selector.name.equals(HttpHeaders.Upgrade, ignoreCase = true) &&
            selector.value.equals("websocket", ignoreCase = true)
    }
    if (websocket) return ApiMethod.WEBSOCKET
    val method =
        selectors.filterIsInstance<HttpMethodRouteSelector>().firstOrNull()?.method
            ?: error("no method on $this")
    return when (method) {
        HttpMethod.Get -> ApiMethod.GET
        HttpMethod.Post -> ApiMethod.POST
        HttpMethod.Put -> ApiMethod.PUT
        HttpMethod.Delete -> ApiMethod.DELETE
        else -> error(method.value)
    }
}

private fun RoutingNode.routePath(): String {
    val raw = path
    val slashed = if (raw.startsWith("/")) raw else "/$raw"
    return slashed.trimEnd('/').ifEmpty { "/" }
}
