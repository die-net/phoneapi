package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class Scope {
    /** Read UI, device state, events, snapshots. */
    @SerialName("observe") OBSERVE,
    /** Input, app control, wake/unlock. */
    @SerialName("control") CONTROL,
    /** CDP access to Chrome and WebViews. */
    @SerialName("browser") BROWSER,
    /** Video and audio streams. */
    @SerialName("stream") STREAM,
    /** Token management and settings. */
    @SerialName("admin") ADMIN,
}

@Serializable
public data class TokenInfo(
    val id: String,
    val name: String,
    val scopes: Set<Scope>,
    val createdAtMs: Long,
    val lastUsedAtMs: Long? = null,
)

@Serializable
public data class CreateTokenRequest(
    val name: String,
    val scopes: Set<Scope> = Scope.entries.toSet(),
)

/** Returned once on creation; [token] is never retrievable again. */
@Serializable public data class CreatedToken(val info: TokenInfo, val token: String)

/** Contents of the pairing QR code, also available as a `phoneapi://pair?...` URI. */
@Serializable
public data class PairingInfo(
    val host: String,
    val port: Int,
    val certSha256: String,
    val token: String,
    val name: String,
)
