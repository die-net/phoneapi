package net.die.phoneapi.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BrowserRouteTest {
    @Test
    fun `incognito takes the screen`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", setOf(Scope.BROWSER))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, browser)
            val normal =
                client.post("/v1/browser/tabs") {
                    bearer(browser)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"url":"https://example.com/"}""")
                }
            assertEquals(HttpStatusCode.OK, normal.status)
            assertNull(api.lease.state.value.holder)

            // Chrome's incognito shortcut brings Chrome forward, so the call holds the screen.
            val incognito =
                client.post("/v1/browser/tabs") {
                    bearer(browser)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"url":"https://example.com/","incognito":true}""")
                }
            assertEquals(HttpStatusCode.OK, incognito.status)
            assertNotNull(api.lease.state.value.holder)
        }
    }
}
