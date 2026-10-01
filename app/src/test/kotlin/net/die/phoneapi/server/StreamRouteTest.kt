package net.die.phoneapi.server

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamRouteTest {
    @Test
    fun `unsupported audio is refused`() = testApplication {
        val api = FakeApi()
        api.capabilities = allCapabilities().copy(streamAudioSubmix = false)
        val secret = api.tokens.issue("stream", setOf(Scope.STREAM))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                client.webSocket("/v1/stream/audio", { bearer(secret) }) {
                    val reason = closeReason.await()
                    assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
                    assertTrue(reason?.message.orEmpty().contains("Android 11"))
                }
            }
        }
    }
}
