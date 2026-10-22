package net.die.phoneapi.model

import kotlinx.serialization.Serializable
import net.die.phoneapi.model.schema.Doc

/** One MCP session, as `GET /v1/sessions` lists it. The session id itself is never shown. */
@Serializable
public data class SessionInfo(
    @Doc("A short hash of the session id, for DELETE /v1/sessions/{hash}.") val hash: String,
    @Doc("The name of the token that started the session.") val token: String,
    @Doc("Milliseconds since the session started.") val ageMs: Long,
    @Doc("Milliseconds since its last request.") val idleMs: Long,
    @Doc("Whether it holds the screen.") val holdsScreen: Boolean = false,
    @Doc("Browser tabs it owns, opened or claimed.") val tabs: Int = 0,
    @Doc(
        "Times a call from it brought an app forward while it did not hold the screen, failing with took_screen."
    )
    val tookScreen: Int = 0,
)
