package net.die.phoneapi.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.ApiError
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WaitRouteTest {
    @Test
    fun `browser wait needs browser`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        val browser = api.tokens.issue("browser", setOf(Scope.OBSERVE, Scope.BROWSER))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val denied =
                client.post("/v1/wait") {
                    bearer(observe)
                    contentType(ContentType.Application.Json)
                    setBody(BROWSER_WAIT)
                }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertEquals(
                "forbidden",
                ApiJson.decodeFromString(ApiError.serializer(), denied.bodyAsText()).error,
            )
            assertEquals(0, api.waits.calls)

            val node =
                client.post("/v1/wait") {
                    bearer(observe)
                    session(startSession(client, observe))
                    contentType(ContentType.Application.Json)
                    setBody(NODE_WAIT)
                }
            assertEquals(HttpStatusCode.OK, node.status)
            assertEquals(1, api.waits.calls)

            val allowed =
                client.post("/v1/wait") {
                    bearer(browser)
                    session(startSession(client, browser))
                    contentType(ContentType.Application.Json)
                    setBody(BROWSER_WAIT)
                }
            assertEquals(HttpStatusCode.OK, allowed.status)
            assertEquals(2, api.waits.calls)
            assertEquals(setOf(Scope.OBSERVE, Scope.BROWSER), api.waits.scopes)
        }
    }

    private companion object {
        const val NODE_WAIT = """{"all":[{"type":"node","selector":{"text":"Ok"}}],"timeoutMs":0}"""
        // A session's browser wait names its tab unless the session can see exactly one.
        const val BROWSER_WAIT =
            """{"all":[{"type":"browser.url","target":"chrome_devtools_remote~t",""" +
                """"contains":"example"}],"timeoutMs":0}"""
    }
}
