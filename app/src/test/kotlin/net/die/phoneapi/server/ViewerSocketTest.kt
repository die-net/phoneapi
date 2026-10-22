package net.die.phoneapi.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.model.PointerOp
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenOrientation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewerSocketTest {
    private val api = FakeApi()
    private val secret = api.tokens.issue("viewer", setOf(Scope.CONTROL))
    private val agent = Holder.Session("agent")

    @Test
    fun `viewer takes a free screen`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("none", lease(this).holder())
                    send(Frame.Text("""{"op":"down","id":0,"x":1,"y":2,"tMs":0}"""))
                    assertEquals("you", lease(this).holder())
                    send(Frame.Text("""{"op":"up","id":0,"x":1,"y":2,"tMs":30}"""))
                }
                val seen = api.pointerSessions.receive()
                assertEquals(listOf(PointerOp.DOWN, PointerOp.UP), seen.map { it.op })
            }
        }
    }

    @Test
    fun `agents keep it until take`() = testApplication {
        application { phoneApiModule(api.services) }
        api.services.lease.withScreen(agent) {}
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    assertEquals("agent", lease(this).holder())
                    send(Frame.Text("""{"op":"down","id":0,"x":1,"y":2,"tMs":0}"""))
                    send(Frame.Text("""{"op":"up","id":0,"x":1,"y":2,"tMs":30}"""))
                    send(Frame.Text("""{"op":"take"}"""))
                    val taken = lease(this)
                    assertEquals("you", taken.holder())
                    assertEquals("agent", taken["takenFrom"]?.jsonPrimitive?.content)
                    send(Frame.Text("""{"op":"down","id":1,"x":5,"y":6,"tMs":0}"""))
                    send(Frame.Text("""{"op":"up","id":1,"x":5,"y":6,"tMs":30}"""))
                }
                val seen = api.pointerSessions.receive()
                assertEquals(listOf(1, 1), seen.map { it.id })
            }
        }
        settle { api.services.lease.holds(agent) }
        assertTrue(api.services.lease.holds(agent))
        assertEquals(0, api.cleanups)
    }

    @Test
    fun `orientation goes on the socket`() = testApplication {
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/input/pointer", { bearer(secret) }) {
                    lease(this)
                    send(Frame.Text("""{"op":"orientation","orientation":"90"}"""))
                    settle { api.orientation != null }
                }
            }
        }
        assertEquals(ScreenOrientation.R90, api.orientation?.orientation)
    }

    /** The next lease message, skipping anything else. */
    private suspend fun lease(socket: DefaultClientWebSocketSession): JsonObject {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame !is Frame.Text) continue
            val message = Json.parseToJsonElement(frame.readText()).jsonObject
            if (message["op"]?.jsonPrimitive?.content == "lease") return message
        }
    }

    private fun JsonObject.holder(): String? = this["holder"]?.jsonPrimitive?.content

    private suspend fun settle(done: () -> Boolean) {
        repeat(50) {
            if (done()) return
            delay(20)
        }
    }
}
