package net.die.phoneapi.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.PairRequest
import net.die.phoneapi.model.PairState
import net.die.phoneapi.model.PairStatus
import net.die.phoneapi.model.PairTicket
import net.die.phoneapi.model.PairingInfo
import net.die.phoneapi.server.PairingManager
import net.die.phoneapi.server.ServerServices

internal const val PAIR_PAGE_PATH = "/pair"
internal const val PAIR_PATH = "/v1/pair"

/**
 * Pairing, the only routes that run without a token. [isPairingCall] lets them through
 * authentication only while the window is open (or the polled id is live), so a closed server still
 * answers them with the same empty 404 as any unauthenticated request.
 */
fun Route.pairRoutes(services: ServerServices) {
    val page = CachedBytes(services.pairHtml)
    get(PAIR_PAGE_PATH) {
        call.noStore()
        call.respondBytes(page.bytes(), ContentType.Text.Html)
    }
    post(PAIR_PATH) {
        val request = call.receive<PairRequest>()
        val id = services.pairing.submit(request.name, call.request.local.remoteAddress)
        call.noStore()
        call.respond(HttpStatusCode.Created, PairTicket(id))
    }
    get("$PAIR_PATH/{id}") {
        val id = call.parameters["id"].orEmpty()
        val outcome =
            services.pairing.await(id, LONG_POLL_MS)
                ?: throw ApiException.notFound("pairing request")
        val status =
            when (outcome) {
                PairingManager.Outcome.Waiting -> PairStatus(PairState.PENDING)
                PairingManager.Outcome.Denied -> PairStatus(PairState.DENIED)
                PairingManager.Outcome.Expired -> PairStatus(PairState.EXPIRED)
                is PairingManager.Outcome.Approved -> {
                    val pins = services.pins()
                    PairStatus(
                        PairState.APPROVED,
                        PairingInfo(
                            host = call.request.host(),
                            port = call.request.local.localPort,
                            certSha256 = pins.certSha256,
                            spkiSha256 = pins.spkiSha256,
                            token = outcome.token,
                            name = outcome.name,
                        ),
                    )
                }
            }
        call.noStore()
        call.respond(status)
    }
}

internal fun ApplicationCall.isPairingCall(pairing: PairingManager): Boolean {
    val method = request.httpMethod
    val path = request.path()
    if (method == HttpMethod.Get && path.startsWith("$PAIR_PATH/")) {
        return pairing.knows(path.removePrefix("$PAIR_PATH/"))
    }
    val entry =
        (method == HttpMethod.Get && path == PAIR_PAGE_PATH) ||
            (method == HttpMethod.Post && path == PAIR_PATH)
    return entry && pairing.isOpen
}

private fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
}

private const val LONG_POLL_MS = 20_000L
