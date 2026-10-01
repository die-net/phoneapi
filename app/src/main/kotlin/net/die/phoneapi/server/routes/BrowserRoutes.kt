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
import net.die.phoneapi.AppGraph
import net.die.phoneapi.browser.ChromeSocket
import net.die.phoneapi.browser.HelperDevtoolsSocket
import net.die.phoneapi.browser.parseBrowserTargetId
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.NavigateRequest
import net.die.phoneapi.model.OpenTabRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.requireScope

/** Browser routes: targets, navigation, snapshot, tap, evaluate, console, and raw CDP. */
fun Route.browserRoutes(graph: AppGraph) {
    get("/v1/browser/targets") {
        call.requireScope(Scope.BROWSER)
        call.respond(graph.browser.targets())
    }
    post("/v1/browser/tabs") {
        call.requireScope(Scope.BROWSER)
        call.respond(graph.browser.openTab(call.receive<OpenTabRequest>().url))
    }
    post("/v1/browser/targets/{id}/navigate") {
        call.requireScope(Scope.BROWSER)
        call.respond(graph.browser.navigate(call.targetId(), call.receive<NavigateRequest>().url))
    }
    get("/v1/browser/targets/{id}/snapshot") {
        call.requireScope(Scope.BROWSER)
        call.respond(graph.browser.snapshot(call.targetId()))
    }
    post("/v1/browser/targets/{id}/tap") {
        call.requireScope(Scope.BROWSER)
        val request = call.receive<BrowserTapRequest>()
        val woke = graph.prepareForAction(request.autoWake)
        try {
            val result = graph.browser.tap(call.targetId(), request)
            call.respond(result.copy(woke = woke, seq = graph.uiTracker.seq.value))
        } finally {
            graph.snapshots.invalidate()
        }
    }
    post("/v1/browser/targets/{id}/evaluate") {
        call.requireScope(Scope.BROWSER)
        call.respond(graph.browser.evaluate(call.targetId(), call.receive<EvalRequest>()))
    }
    post("/v1/browser/targets/{id}/console") {
        call.requireScope(Scope.BROWSER)
        call.respond(graph.browser.console(call.targetId(), call.receive<ConsoleRequest>()))
    }
    webSocket("/v1/browser/targets/{id}/cdp") {
        call.requireScope(Scope.BROWSER)
        proxyCdp(this, graph, call.targetId())
    }
}

private suspend fun proxyCdp(
    session: DefaultWebSocketServerSession,
    graph: AppGraph,
    id: String,
) {
    val (socket, chromeId) = parseBrowserTargetId(id)
    HelperDevtoolsSocket(graph.helper, socket, readTimeoutMs = 0).use { devtools ->
        ChromeSocket(devtools.input, devtools.output, graph.ioDispatcher).use { chrome ->
            try {
                chrome.handshake("/devtools/page/$chromeId")
                chrome.connect()
                coroutineScope {
                    val reader =
                        launch(graph.ioDispatcher) {
                            chrome.relayText { text -> session.send(Frame.Text(text)) }
                        }
                    try {
                        for (frame in session.incoming) {
                            if (frame is Frame.Text) forward(chrome, frame.readText())
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
                    e.message ?: "DevTools connection failed",
                    cause = e,
                )
            }
        }
    }
}

private suspend fun forward(chrome: ChromeSocket, text: String) {
    if (text.length > MAX_CDP_FRAME) throw ApiException.badRequest("CDP frame is too large")
    try {
        chrome.sendText(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw ApiException(502, "cdp_error", e.message ?: "DevTools connection failed", cause = e)
    }
}

private const val MAX_CDP_FRAME = 1_000_000

private fun ApplicationCall.targetId(): String =
    parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw ApiException.badRequest("A target id is required")
