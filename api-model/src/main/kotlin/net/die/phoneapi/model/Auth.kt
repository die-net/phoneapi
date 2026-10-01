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

/**
 * What a client needs to connect: from an approved `/v1/pair` request, or `CREATE_TOKEN` over ADB.
 * [certSha256] is the colon-separated certificate hash; [spkiSha256] is the base64 public-key hash
 * that `curl --pinnedpubkey sha256//...` takes.
 */
@Serializable
public data class PairingInfo(
    val host: String,
    val port: Int,
    val certSha256: String,
    val spkiSha256: String,
    val token: String,
    val name: String,
)

/** Body of `POST /v1/pair`. [name] labels the token in the app's list of paired computers. */
@Serializable public data class PairRequest(val name: String)

/** Returned by `POST /v1/pair`. [id] is the only handle on the request; poll it. */
@Serializable public data class PairTicket(val id: String)

@Serializable
public enum class PairState {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("denied") DENIED,
    @SerialName("expired") EXPIRED,
}

/** `GET /v1/pair/{id}`. [pairing] is present only once, on the approved response. */
@Serializable public data class PairStatus(val state: PairState, val pairing: PairingInfo? = null)
