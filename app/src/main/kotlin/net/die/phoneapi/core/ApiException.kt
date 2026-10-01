package net.die.phoneapi.core

import net.die.phoneapi.model.DeviceStateSummary
import net.die.phoneapi.model.Scope

/** Mapped by the server to an HTTP status and an [net.die.phoneapi.model.ApiError] body. */
class ApiException(
    val status: Int,
    val error: String,
    override val message: String? = null,
    val state: DeviceStateSummary? = null,
    cause: Throwable? = null,
) : RuntimeException(message ?: error, cause) {
    companion object {
        fun badRequest(message: String, cause: Throwable? = null) =
            ApiException(400, "bad_request", message, cause = cause)

        fun forbidden(scope: Scope) =
            ApiException(403, "forbidden", "Token lacks the '${scope.name.lowercase()}' scope")

        fun notFound(what: String) = ApiException(404, "not_found", what)

        fun unavailable(error: String, message: String) = ApiException(503, error, message)

        fun a11yUnavailable() =
            unavailable(
                "accessibility_unavailable",
                "The PhoneAPI accessibility service is not enabled",
            )

        fun helperUnavailable() =
            unavailable(
                "helper_unavailable",
                "This requires the shell helper, which is not running",
            )
    }
}
