package net.die.phoneapi.mcp

import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.FakeApi
import net.die.phoneapi.server.phoneApiModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpEndpointTest {
    @Test
    fun `lists tools and calls device`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        application { phoneApiModule(api.services) }
        val names =
            withMcp(observe) { client ->
                val listed = client.listTools().tools.map { it.name }
                val info = client.callTool("device_info", emptyMap())
                assertTrue(info.isError != true)
                val text = (info.content.single() as TextContent).text
                assertTrue(text.contains("phoneapi-fake"))
                listed
            }
        assertTrue("device_info" in names)
        assertTrue("ui_snapshot" in names)
        assertTrue("wait_for" in names)
        assertTrue("screenshot" in names)
        assertFalse("tap" in names)
        assertFalse("keyboard_show" in names)
        assertFalse("browser_targets" in names)

        api.capabilities = api.capabilities.copy(browserCdp = false, logcatAll = false)
        val browser = api.tokens.issue("browser", setOf(Scope.OBSERVE, Scope.BROWSER))
        val narrowed = withMcp(browser) { client -> client.listTools().tools.map { it.name } }
        assertTrue("device_info" in narrowed)
        assertFalse("browser_targets" in narrowed)
        assertFalse("logcat_tail" in narrowed)
    }

    @Test
    fun `no browser scope tool error`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        application { phoneApiModule(api.services) }
        val result =
            withMcp(observe) { client ->
                client.callTool(
                    "wait_for",
                    mapOf("all" to listOf(mapOf("type" to "browser.url", "contains" to "example"))),
                )
            }
        assertEquals(true, result.isError)
        assertTrue((result.content.single() as TextContent).text.contains("forbidden"))
        assertEquals(0, api.waits.calls)
    }

    private suspend fun <T> ApplicationTestBuilder.withMcp(
        secret: String,
        block: suspend (Client) -> T,
    ): T {
        return createClient {
            expectSuccess = false
            install(SSE)
        }
            .use { http ->
                val client = Client(Implementation(name = "phoneapi-test", version = "0"))
                val transport =
                    http.mcpStreamableHttpTransport(
                        url = "http://localhost/mcp",
                        reconnectionOptions =
                            ReconnectionOptions(
                                initialReconnectionDelay = 1.milliseconds,
                                maxRetries = 0,
                            ),
                        requestBuilder = {
                            header(HttpHeaders.Authorization, "Bearer $secret")
                            header(HttpHeaders.Host, "localhost")
                        },
                    )
                try {
                    client.connect(transport)
                    block(client)
                } finally {
                    withContext(NonCancellable) { client.close() }
                }
            }
    }
}
