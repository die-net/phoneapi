package net.die.phoneapi.browser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSocketClientTest {
    @Test
    fun `accept matches rfc`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", websocketAccept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `reads one text`() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val headers =
            asciiLines(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${websocketAccept(key)}",
            )
        val input = ByteArrayInputStream(headers + serverTextFrame("""{"id":1}"""))
        val output = ByteArrayOutputStream()
        val client =
            WebSocketClient(input, output, newKey = { key }, newMask = { byteArrayOf(1, 2, 3, 4) })
        client.handshake("/devtools/browser")
        client.sendText("hi")
        assertEquals("""{"id":1}""", client.nextText())
        val sent = output.toByteArray().decodeToString()
        assertTrue(sent.contains("GET /devtools/browser HTTP/1.1"))
    }

    @Test
    fun `sets the mask bit`() {
        val frame =
            clientFrame(OPCODE_TEXT, byteArrayOf('a'.code.toByte()), byteArrayOf(0, 0, 0, 0))
        assertEquals(0x81, frame[0].toInt() and 0xFF)
        assertEquals(0x80 or 1, frame[1].toInt() and 0xFF)
    }
}
