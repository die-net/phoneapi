package net.die.phoneapi.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import net.die.phoneapi.model.PinStatus
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SetPinRequest
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped

/** `GET /v1/device`, `POST /v1/device/wake|unlock|lock`, and `PUT|DELETE /v1/device/pin`. */
fun Route.deviceRoutes(services: ServerServices) {
    scoped(Scope.OBSERVE) {
        get("/v1/device") { call.respond(services.device.info()) }
    }
    scoped(Scope.CONTROL) {
        post("/v1/device/wake") { call.respond(services.power().wake()) }
        post("/v1/device/unlock") {
            call.respond(services.power().unlock(bodyOrDefault(call, UnlockRequest())))
        }
        post("/v1/device/lock") { call.respond(services.power().lock()) }
    }
    scoped(Scope.ADMIN) {
        put("/v1/device/pin") {
            services.writePin(call.receive<SetPinRequest>().pin)
            call.respond(PinStatus(stored = true))
        }
        delete("/v1/device/pin") {
            services.writePin(null)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * The JSON body of [call], or [default] when the client sent none, so endpoints whose options all
 * have defaults can be called with an empty `POST`.
 */
internal suspend inline fun <reified T : Any> bodyOrDefault(call: ApplicationCall, default: T): T {
    val declared = call.request.headers[HttpHeaders.ContentType]
    return if (declared == null || call.request.contentLength() == 0L) default else call.receive()
}
