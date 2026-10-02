package net.die.phoneapi.server

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerializationException
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.mcp.installPhoneMcp
import net.die.phoneapi.model.ApiError
import net.die.phoneapi.server.routes.appRoutes
import net.die.phoneapi.server.routes.browserRoutes
import net.die.phoneapi.server.routes.deviceRoutes
import net.die.phoneapi.server.routes.eventRoutes
import net.die.phoneapi.server.routes.inputRoutes
import net.die.phoneapi.server.routes.isPairingCall
import net.die.phoneapi.server.routes.openApiRoutes
import net.die.phoneapi.server.routes.pairRoutes
import net.die.phoneapi.server.routes.streamRoutes
import net.die.phoneapi.server.routes.tokenRoutes
import net.die.phoneapi.server.routes.uiRoutes
import net.die.phoneapi.server.routes.waitRoutes

/**
 * HTTP API on an abstract local socket. HTTPS, when enabled, is a byte splice onto that socket and
 * is not a second set of routes.
 */
class ApiServer(
    private val tls: () -> TlsManager,
    private val services: ServerServices,
    private val socketName: String,
) {
    private var server: EmbeddedServer<AbstractHttpEngine, AbstractHttpEngine.Configuration>? = null
    private var splice: TlsSplice? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val env = applicationEnvironment {}
        server =
            embeddedServer(
                    AbstractHttpEngine,
                    env,
                    configure = { this.socketName = this@ApiServer.socketName },
                    module = { phoneApiModule(services) },
                )
                .also { it.start(wait = false) }
        Log.i(TAG, "Listening on abstract socket $socketName")
    }

    @Synchronized
    fun startTls(host: String, port: Int) {
        start()
        splice?.stop()
        splice = TlsSplice(tls(), socketName).also { it.start(host, port) }
    }

    @Synchronized
    fun stopTls() {
        splice?.stop()
        splice = null
    }

    @Synchronized
    fun stop() {
        stopTls()
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
    }

    private companion object {
        const val TAG = "PhoneApiServer"
    }
}

fun Application.phoneApiModule(services: ServerServices) {
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
    install(BearerAuth) {
        tokens = services.tokens
        isPublic = { call -> call.isPairingCall(services.pairing) }
    }
    routing {
        pairRoutes(services)
        deviceRoutes(services)
        openApiRoutes()
        tokenRoutes(services)
        eventRoutes(services)
        uiRoutes(services)
        inputRoutes(services)
        appRoutes(services)
        browserRoutes(services)
        waitRoutes(services)
        streamRoutes(services)
    }
    installPhoneMcp(services)
}

private fun Throwable.rootMessage(): String? = generateSequence(this) { it.cause }.last().message
