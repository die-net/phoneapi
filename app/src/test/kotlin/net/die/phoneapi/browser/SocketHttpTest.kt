package net.die.phoneapi.browser

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SocketHttpTest {
    @Test
    fun `reads the body`() {
        val body = """[{"id":"abc"}]"""
        val response =
            asciiLines(
                "HTTP/1.1 200 OK",
                "Content-Type: application/json",
                "Content-Length: ${body.length}",
            )
        val extra = "NEXT"
        val input =
            ByteArrayInputStream(
                response +
                    body.toByteArray(Charsets.US_ASCII) +
                    extra.toByteArray(Charsets.US_ASCII)
            )
        val parsed = input.readHttpResponse(maxBody = 1024)
        assertEquals(200, parsed.status)
        assertEquals(body, parsed.body.decodeToString())
        assertEquals("application/json", parsed.headers["content-type"])
        assertArrayEquals(extra.toByteArray(), input.readBytes())
    }

    @Test
    fun `writes a get`() {
        val text = httpRequest("GET", "/json/list").decodeToString()
        assertEquals("GET /json/list HTTP/1.1", text.substringBefore('\r'))
        assertTrue(text.endsWith(charArrayOf('\r', '\n', '\r', '\n').concatToString()))
    }
}
