package net.die.phoneapi.server

import io.ktor.server.application.ApplicationCall
import java.util.concurrent.atomic.AtomicLong
import net.die.phoneapi.lease.Holder

private val restCalls = AtomicLong()

/**
 * Runs a REST call that takes the screen as a one-call holder, which waits like any other and lets
 * the screen go, without cleanup, when it returns. Chrome shows none of a session's tabs while it
 * runs. Notes for the holder go in `X-PhoneAPI-Note` headers.
 */
internal suspend fun <T> withScreen(
    call: ApplicationCall,
    services: ServerServices,
    needed: Boolean = true,
    block: suspend () -> T,
): T {
    if (!needed) return block()
    val holder = Holder.Rest(restCalls.incrementAndGet())
    val screened = services.lease.withScreen(holder) { services.tabs.screenCall(holder, block) }
    screened.notes.forEach { call.response.headers.append(NOTE_HEADER, it) }
    return screened.value
}

const val NOTE_HEADER = "X-PhoneAPI-Note"
