package net.die.phoneapi.server

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerializationException
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.mcp.installPhoneMcp
import net.die.phoneapi.model.ApiError
import net.die.phoneapi.server.routes.appRoutes
import net.die.phoneapi.server.routes.browserRoutes
import net.die.phoneapi.server.routes.deviceRoutes
import net.die.phoneapi.server.routes.eventRoutes
import net.die.phoneapi.server.routes.inputRoutes
import net.die.phoneapi.server.routes.streamRoutes
import net.die.phoneapi.server.routes.tokenRoutes
import net.die.phoneapi.server.routes.uiRoutes
import net.die.phoneapi.server.routes.waitRoutes

/** HTTPS + WebSocket API server (Ktor on Netty, since CIO cannot terminate TLS). */
class ApiServer(private val graph: AppGraph) {
    private var server:
        EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
        null

    @Synchronized
    fun start(host: String, port: Int) {
        stop()
        val tls = graph.tls
        val password = graph.settings.current.keystorePassword.toCharArray()
        val env = applicationEnvironment {}
        server =
            embeddedServer(
                    Netty,
                    env,
                    configure = {
                        // With HTTP/2 enabled, Netty drops TLS clients that send no ALPN (common
                        // for Python and MCP clients). WebSockets need HTTP/1.1 anyway.
                        enableHttp2 = false
                        sslConnector(
                            keyStore = tls.keyStore,
                            keyAlias = TlsManager.ALIAS,
                            keyStorePassword = { password },
                            privateKeyPassword = { password },
                        ) {
                            this.host = host
                            this.port = port
                        }
                    },
                    module = { module(graph) },
                )
                .also { it.start(wait = false) }
    }

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
    }
}

internal fun Application.module(graph: AppGraph) {
    install(ContentNegotiation) { register(ContentType.Application.Json, PhoneJsonConverter) }
    install(WebSockets) { pingPeriod = 15.seconds }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.status), ApiError(e.error, e.message, e.state))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", e.rootMessage()))
        }
        exception<SerializationException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", e.message))
        }
        exception<Throwable> { call, e ->
            Log.e("PhoneApiServer", "Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", e.message))
        }
    }
    install(BearerAuth) { tokens = graph.tokens }
    routing {
        deviceRoutes(graph)
        tokenRoutes(graph)
        eventRoutes(graph)
        uiRoutes(graph)
        inputRoutes(graph)
        appRoutes(graph)
        browserRoutes(graph)
        waitRoutes(graph)
        streamRoutes(graph)
    }
    installPhoneMcp(graph)
}

private fun Throwable.rootMessage(): String? =
    generateSequence(this) { e -> e.cause?.takeIf { it !== e } }.last().message ?: message
