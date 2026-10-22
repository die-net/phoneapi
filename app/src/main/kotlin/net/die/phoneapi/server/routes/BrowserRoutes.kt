package net.die.phoneapi.server.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.die.phoneapi.browser.DEVTOOLS_CLOSED
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.NavigateRequest
import net.die.phoneapi.model.OpenTabRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.CdpPipe
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped

/**
 * Browser routes: targets, navigation, snapshot, reading, input, evaluate, console, and raw CDP.
 */
fun Route.browserRoutes(services: ServerServices) {
    scoped(Scope.BROWSER) {
        get("/v1/browser/targets") { call.respond(services.browser.targets()) }
        post("/v1/browser/tabs") {
            call.respond(services.browser.openTab(call.receive<OpenTabRequest>().url))
        }
        post("/v1/browser/targets/{id}/navigate") {
            call.respond(
                services.browser.navigate(call.targetId(), call.receive<NavigateRequest>().url)
            )
        }
        post("/v1/browser/targets/{id}/close") {
            call.respond(services.browser.closeTab(call.targetId()))
        }
        get("/v1/browser/targets/{id}/snapshot") {
            val q = call.request.queryParameters
            val options =
                BrowserSnapshotOptions(
                    maxNodes = q.int("maxNodes") ?: BrowserSnapshotOptions().maxNodes,
                    maxChars = q.int("maxChars"),
                    show = q.flag("show", default = false),
                )
            call.respond(services.browser.snapshot(call.targetId(), options))
        }
        post("/v1/browser/targets/{id}/read") {
            call.respond(services.browser.read(call.targetId(), call.receive<BrowserReadRequest>()))
        }
        post("/v1/browser/targets/{id}/find") {
            call.respond(services.browser.find(call.targetId(), call.receive<BrowserFindRequest>()))
        }
        post("/v1/browser/targets/{id}/tap") {
            call.respond(services.browser.tap(call.targetId(), call.receive<BrowserTapRequest>()))
        }
        post("/v1/browser/targets/{id}/swipe") {
            call.respond(
                services.browser.swipe(call.targetId(), call.receive<BrowserSwipeRequest>())
            )
        }
        post("/v1/browser/targets/{id}/gesture") {
            call.respond(
                services.browser.gesture(call.targetId(), call.receive<BrowserGestureRequest>())
            )
        }
        post("/v1/browser/targets/{id}/key") {
            call.respond(services.browser.key(call.targetId(), call.receive<BrowserKeyRequest>()))
        }
        post("/v1/browser/targets/{id}/text") {
            call.respond(services.browser.text(call.targetId(), call.receive<BrowserTextRequest>()))
        }
        post("/v1/browser/targets/{id}/evaluate") {
            call.respond(services.browser.evaluate(call.targetId(), call.receive<EvalRequest>()))
        }
        post("/v1/browser/targets/{id}/console") {
            call.respond(services.browser.console(call.targetId(), call.receive<ConsoleRequest>()))
        }
        webSocket("/v1/browser/targets/{id}/cdp") { proxyCdp(this, services, call.targetId()) }
    }
}

private suspend fun proxyCdp(
    session: DefaultWebSocketServerSession,
    services: ServerServices,
    id: String,
) {
    services.cdp.open(id).use { pipe ->
        try {
            pipe.handshake()
            pipe.connect()
            coroutineScope {
                val reader =
                    launch(services.ioDispatcher) {
                        pipe.relayText { text -> session.send(Frame.Text(text)) }
                    }
                try {
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) forward(pipe, frame.readText())
                    }
                } finally {
                    reader.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(
                502,
                "cdp_error",
                e.message?.takeIf { it.isNotBlank() } ?: DEVTOOLS_CLOSED,
                cause = e,
            )
        }
    }
}

private suspend fun forward(pipe: CdpPipe, text: String) {
    if (text.length > MAX_CDP_FRAME) throw ApiException.badRequest("CDP frame is too large")
    try {
        pipe.sendText(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw ApiException(
            502,
            "cdp_error",
            e.message?.takeIf { it.isNotBlank() } ?: DEVTOOLS_CLOSED,
            cause = e,
        )
    }
}

private const val MAX_CDP_FRAME = 1_000_000

private fun ApplicationCall.targetId(): String =
    parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw ApiException.badRequest("A target id is required")
