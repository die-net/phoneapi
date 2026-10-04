package net.die.phoneapi.server

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthRoutesTest {
    @Test
    fun `missing and bad tokens are 404`() = testApplication {
        val api = FakeApi()
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val missing = client.get("/v1/device")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("", missing.bodyAsText())
            val unknown = client.get("/v1/device") { bearer("nope") }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("", unknown.bodyAsText())
        }
    }

    @Test
    fun `authorization header works`() = testApplication {
        val api = FakeApi()
        val secret = api.tokens.issue("good", setOf(Scope.OBSERVE))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val response = client.get("/v1/device") { bearer(secret) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("phoneapi-fake"))
        }
    }

    @Test
    fun `query token only on viewer ws`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        val stream = api.tokens.issue("stream", setOf(Scope.STREAM))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val rest = client.get("/v1/device?access_token=$observe")
            assertEquals(HttpStatusCode.NotFound, rest.status)
            assertEquals("", rest.bodyAsText())

            val viewer = client.get("/viewer?access_token=$stream&maxSize=720")
            assertEquals(HttpStatusCode.Found, viewer.status)
            assertEquals("/viewer?maxSize=720", viewer.headers[HttpHeaders.Location])
            assertFalse(viewer.bodyAsText().contains(stream))
            val setCookie = viewer.headers[HttpHeaders.SetCookie].orEmpty()
            assertTrue(setCookie.startsWith("$VIEWER_COOKIE=$stream"))
            assertTrue(setCookie.contains("HttpOnly"))
            assertTrue(setCookie.contains("SameSite=Strict"))

            val page =
                client.get("/viewer") { header(HttpHeaders.Cookie, "$VIEWER_COOKIE=$stream") }
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue(page.bodyAsText().contains("viewer"))

            val device =
                client.get("/v1/device") { header(HttpHeaders.Cookie, "$VIEWER_COOKIE=$observe") }
            assertEquals(HttpStatusCode.OK, device.status)

            withTimeout(5.seconds) {
                client.webSocket(
                    "/v1/events",
                    { header(HttpHeaders.Cookie, "$VIEWER_COOKIE=$observe") },
                ) {}
            }
            client.webSocket("/v1/events?access_token=$observe") {}
        }
    }
}
