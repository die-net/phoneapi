package net.die.phoneapi.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respondBytes
import io.ktor.util.AttributeKey
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TokenInfo

private val TokenKey = AttributeKey<TokenInfo>("phoneapi.token")

class AuthConfig {
    lateinit var tokens: TokenStore
}

/**
 * Every request needs a valid bearer token (or `access_token` query parameter, for browser
 * WebSockets that can't set headers). Unauthenticated requests get an empty 404, so the server
 * reveals as little as possible about what it is.
 */
val BearerAuth =
    createApplicationPlugin("BearerAuth", ::AuthConfig) {
        val tokens = pluginConfig.tokens
        onCall { call ->
            val secret =
                call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring(BEARER_PREFIX_LENGTH)
                    ?.trim() ?: call.request.queryParameters["access_token"]
            val info = secret?.let(tokens::authenticate)
            if (info == null) {
                call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
            } else {
                call.attributes.put(TokenKey, info)
            }
        }
    }

private const val BEARER_PREFIX_LENGTH = 7

val ApplicationCall.token: TokenInfo
    get() = attributes[TokenKey]

fun ApplicationCall.requireScope(scope: Scope) {
    if (scope !in token.scopes) {
        throw ApiException(403, "forbidden", "Token lacks the '${scope.name.lowercase()}' scope")
    }
}
