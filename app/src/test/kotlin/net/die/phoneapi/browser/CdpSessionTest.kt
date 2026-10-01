package net.die.phoneapi.browser

import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import io.ktor.websocket.Frame
import io.ktor.websocket.RawWebSocket
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("InjectDispatcher") // The fake DevTools socket is read on a real dispatcher.
class CdpSessionTest {
    @Test
    fun `matches commands and events`() = runBlocking {
        withTimeout(10_000) {
            ServerSocket(0).use { server ->
                Socket("127.0.0.1", server.localPort).use { client ->
                    server.accept().use { accepted ->
                        client.soTimeout = 8_000
                        accepted.soTimeout = 8_000
                        val streams = client.getInputStream() to client.getOutputStream()
                        CdpSession(streams.first, streams.second, Dispatchers.IO).use { session ->
                            val served = launch(Dispatchers.IO) { serve(accepted) }
                            session.open("/devtools/page/abc")
                            val event = async { session.events.first() }
                            val result =
                                session.call(
                                    "Runtime.evaluate",
                                    buildJsonObject { put("expression", "1+1") },
                                )
                            assertEquals(2, result.decodeCdp<Eval>()!!.result.value)
                            val delivered = event.await()
                            assertEquals("Log.entryAdded", delivered.method)
                            assertEquals("hi", delivered.params.decodeCdp<LogAdded>()!!.entry.text)
                            served.join()
                        }
                    }
                }
            }
        }
    }

    private suspend fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val key = input.headerValue("Sec-WebSocket-Key")
        output.write(
            asciiLines(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${websocketAccept(key)}",
            )
        )
        output.flush()
        val ws =
            RawWebSocket(
                input = input.toByteReadChannel(),
                output = output.asByteWriteChannel(),
                masking = false,
                coroutineContext = Dispatchers.IO,
            )
        val command = ws.incoming.receive() as Frame.Text
        val text = command.readText()
        val parsed = ApiJson.decodeFromString<Command>(text)
        assertEquals("Runtime.evaluate", parsed.method)
        assertTrue(text.contains("1+1"))
        ws.send(Frame.Text("""{"method":"Log.entryAdded","params":{"entry":{"text":"hi"}}}"""))
        ws.send(
            Frame.Text("""{"id":${parsed.id},"result":{"result":{"type":"number","value":2}}}""")
        )
    }

    @Serializable private data class Command(val id: Int, val method: String)

    @Serializable private data class Eval(val result: Remote)

    @Serializable private data class Remote(val value: Int = 0)

    @Serializable private data class LogAdded(val entry: LogLine)

    @Serializable private data class LogLine(val text: String = "")
}

private fun InputStream.headerValue(name: String): String {
    val out = ByteArrayOutputStream()
    var matched = 0
    val end =
        byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    while (matched < end.size) {
        val value = read()
        if (value < 0) throw IOException("closed before the HTTP header finished")
        out.write(value)
        matched =
            if (value == end[matched].toInt()) {
                matched + 1
            } else if (value == end[0].toInt()) {
                1
            } else {
                0
            }
    }
    val prefix = "$name:"
    val line =
        out.toByteArray().decodeToString().lineSequence().firstOrNull {
            it.startsWith(prefix, ignoreCase = true)
        } ?: throw IOException("missing $name")
    return line.substringAfter(':').trim()
}
