package net.die.phoneapi.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.FakeApi
import net.die.phoneapi.server.apiClient
import net.die.phoneapi.server.bearer
import net.die.phoneapi.server.phoneApiModule
import net.die.phoneapi.server.session
import net.die.phoneapi.server.startSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpLeaseTest {
    private val api = FakeApi(leaseWaitMs = 200)
    private val token = api.tokens.issue("all", Scope.entries.toSet())

    @Test
    fun `sessions take turns`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val first = initialize(http)
            val second = initialize(http)
            assertFalse(call(http, first, "tap", """{"x":1,"y":2}""").contains("isError\":true"))

            val busy = call(http, second, "tap", """{"x":1,"y":2}""")
            assertTrue(busy.contains("screen_busy"), busy)
            assertTrue(call(http, second, "device_info", "{}").contains("phoneapi-fake"))
            val listed = http.get("/v1/sessions") { bearer(token) }.bodyAsText()
            assertTrue(listed.contains("\"holdsScreen\":true"), listed)

            http.delete("/mcp") { headers(first) }
            settle { api.cleanups == 1 }
            assertEquals(1, api.cleanups)
            val turn = call(http, second, "tap", """{"x":1,"y":2}""")
            assertFalse(turn.contains("isError\":true"), turn)
        }
    }

    @Test
    fun `release lets others in`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val first = initialize(http)
            val second = initialize(http)
            call(http, first, "tap", """{"x":1,"y":2}""")
            assertTrue(call(http, first, "screen_release", "{}").contains("Released the screen"))
            settle { api.cleanups == 1 }
            assertEquals(1, api.cleanups)
            val turn = call(http, second, "tap", """{"x":1,"y":2}""")
            assertFalse(turn.contains("isError\":true"), turn)
            assertTrue(call(http, first, "screen_release", "{}").contains("did not hold"))
        }
    }

    @Test
    fun `rest waits for sessions`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val session = initialize(http)
            val script = startSession(http, token)
            call(http, session, "tap", """{"x":1,"y":2}""")
            val busy =
                http.post("/v1/input/tap") {
                    bearer(token)
                    session(script)
                    contentType(ContentType.Application.Json)
                    setBody("""{"x":1,"y":2}""")
                }
            assertEquals(HttpStatusCode.Conflict, busy.status)
            assertTrue(busy.bodyAsText().contains("screen_busy"))

            http.delete("/mcp") { headers(session) }
            settle { api.cleanups == 1 }
            val taps = api.inputTaps
            val free =
                http.post("/v1/input/tap") {
                    bearer(token)
                    session(script)
                    contentType(ContentType.Application.Json)
                    setBody("""{"x":1,"y":2}""")
                }
            assertEquals(HttpStatusCode.OK, free.status)
            assertEquals(taps + 1, api.inputTaps)
        }
    }

    @Test
    fun `sessions keep their tabs`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val first = initialize(http)
            val second = initialize(http)
            val opened = call(http, first, "browser_open", """{"url":"https://example.com/"}""")
            assertTrue(opened.contains("chrome_devtools_remote~t"), opened)
            val read =
                call(http, second, "browser_read", """{"target":"chrome_devtools_remote~t"}""")
            assertTrue(read.contains("not_found"), read)
            val mine =
                call(http, first, "browser_read", """{"target":"chrome_devtools_remote~t"}""")
            assertFalse(mine.contains("isError\":true"), mine)
            assertEquals(setOf("chrome_devtools_remote~t"), api.tabStore.ids)
            http.delete("/mcp") { headers(first) }
            settle { api.tabStore.ids.isEmpty() }
            assertEquals(emptySet<String>(), api.tabStore.ids)
        }
    }

    @Test
    fun `subagents take turns`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val parent = initialize(http)
            val child = start(http, parent)
            val tap = """{"x":1,"y":2,"session":"$child"}"""
            assertFalse(call(http, parent, "tap", tap).contains("isError\":true"))
            // The parent does not queue behind its own subagent: it is told at once.
            val busy = call(http, parent, "tap", """{"x":1,"y":2}""")
            assertTrue(busy.contains("session_conflict"), busy)
            assertTrue(busy.contains("A session you started"), busy)
            val listed = http.get("/v1/sessions") { bearer(token) }.bodyAsText()
            assertTrue(listed.contains(PARENT), listed)

            val ended = call(http, parent, "session_end", """{"session":"$child"}""")
            assertTrue(ended.contains("Ended the session"), ended)
            settle { api.cleanups == 1 }
            assertFalse(call(http, parent, "tap", """{"x":1,"y":2}""").contains("isError\":true"))
            val gone = call(http, parent, "tap", tap)
            assertTrue(gone.contains("No such session"), gone)
        }
    }

    @Test
    fun `subagents never queue on you`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val parent = initialize(http)
            val child = start(http, parent)
            assertFalse(call(http, parent, "tap", """{"x":1,"y":2}""").contains("isError\":true"))
            val mixed = call(http, parent, "tap", """{"x":1,"y":2,"session":"$child"}""")
            assertTrue(mixed.contains("session_conflict"), mixed)
            assertTrue(mixed.contains("Your own session holds the screen"), mixed)
        }
    }

    @Test
    fun `device info names the holder`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val first = initialize(http)
            val second = initialize(http)
            assertTrue(call(http, first, "device_info", "{}").contains("isError\":false"))
            call(http, first, "tap", """{"x":1,"y":2}""")
            val mine = call(http, first, "device_info", "{}")
            assertTrue(mine.contains("""screenHolder\":\"you"""), mine)
            val theirs = call(http, second, "device_info", "{}")
            assertTrue(theirs.contains("""screenHolder\":\"agent"""), theirs)
        }
    }

    @Test
    fun `subagents keep their tabs`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val parent = initialize(http)
            val child = start(http, parent)
            val open = """{"url":"https://example.com/","session":"$child"}"""
            assertTrue(call(http, parent, "browser_open", open).contains("~t"))
            val target = """"target":"chrome_devtools_remote~t""""
            val theirs = call(http, parent, "browser_read", "{$target}")
            assertTrue(theirs.contains("another session in this conversation"), theirs)
            val stranger = call(http, initialize(http), "browser_read", "{$target}")
            assertTrue(stranger.contains("No browser target"), stranger)
            val mine = call(http, parent, "browser_read", """{$target,"session":"$child"}""")
            assertFalse(mine.contains("isError\":true"), mine)
        }
    }

    @Test
    fun `subagents end with parent`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val parent = initialize(http)
            val child = start(http, parent)
            val other = initialize(http)
            val borrowed = call(http, other, "device_info", """{"session":"$child"}""")
            assertTrue(borrowed.contains("No such session"), borrowed)
            val direct = http.post("/mcp") { headers(child) }
            assertEquals(HttpStatusCode.NotFound, direct.status)
            http.delete("/mcp") { headers(parent) }
            settle { !http.get("/v1/sessions") { bearer(token) }.bodyAsText().contains("parent") }
            val listed = http.get("/v1/sessions") { bearer(token) }.bodyAsText()
            assertFalse(listed.contains(PARENT), listed)
        }
    }

    @Test
    fun `initialize explains sharing`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { http ->
            val response =
                http.post("/mcp") {
                    headers(null)
                    contentType(ContentType.Application.Json)
                    setBody(INITIALIZE)
                }
            val body = response.bodyAsText()
            assertTrue(body.contains("\"instructions\""), body)
            assertTrue(body.contains("session_start"), body)
            assertTrue(body.contains("screen_release"), body)
        }
    }

    private suspend fun start(http: HttpClient, parent: String): String {
        val started = call(http, parent, "session_start", "{}")
        return Regex("session=([0-9a-f]+)").find(started)?.groupValues?.get(1)
            ?: error("no session: $started")
    }

    private suspend fun initialize(http: HttpClient): String {
        val response =
            http.post("/mcp") {
                headers(null)
                contentType(ContentType.Application.Json)
                setBody(INITIALIZE)
            }
        return response.headers["Mcp-Session-Id"] ?: error("no session: ${response.bodyAsText()}")
    }

    private suspend fun call(http: HttpClient, id: String, tool: String, args: String): String =
        http
            .post("/mcp") {
                headers(id)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"$tool",""" +
                        """"arguments":$args}}"""
                )
            }
            .bodyAsText()

    private fun HttpRequestBuilder.headers(id: String?) {
        bearer(token)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", "2025-06-18")
        if (id != null) header("Mcp-Session-Id", id)
    }

    private suspend fun settle(done: suspend () -> Boolean) {
        repeat(50) {
            if (done()) return
            delay(20)
        }
    }

    private companion object {
        const val PARENT = """"parent":""""
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    }
}
