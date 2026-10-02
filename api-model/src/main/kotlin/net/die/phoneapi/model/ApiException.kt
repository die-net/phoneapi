package net.die.phoneapi.model

import kotlinx.serialization.Serializable

/** JSON envelope for a helper tree call. [body] is the success payload. */
@Serializable
public data class TreeResult(
    val status: Int = 200,
    val error: String? = null,
    val message: String? = null,
    val body: String? = null,
)

/** Mapped by the server to an HTTP status and an [ApiError] body. */
public class ApiException(
    public val status: Int,
    public val error: String,
    override val message: String? = null,
    public val state: DeviceStateSummary? = null,
    cause: Throwable? = null,
) : RuntimeException(message ?: error, cause) {
    public companion object {
        public fun badRequest(message: String, cause: Throwable? = null): ApiException =
            ApiException(400, "bad_request", message, cause = cause)

        public fun forbidden(scope: Scope): ApiException =
            ApiException(403, "forbidden", "Token lacks the '${scope.name.lowercase()}' scope")

        public fun notFound(what: String): ApiException = ApiException(404, "not_found", what)

        public fun unavailable(error: String, message: String): ApiException =
            ApiException(503, error, message)

        public fun helperUnavailable(next: String? = null): ApiException =
            unavailable(
                "helper_unavailable",
                if (next.isNullOrBlank()) {
                    "This requires the shell helper, which is not running"
                } else {
                    "This requires the shell helper, which is not running. $next"
                },
            )

        /** The binder died after the call had already started. */
        public fun helperDropped(cause: Throwable? = null): ApiException =
            ApiException(
                503,
                "helper_unavailable",
                "The shell helper stopped during this call. Start it again over USB, or pair " +
                    "wireless debugging on Android 11 or later.",
                cause = cause,
            )
    }
}
