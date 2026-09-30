package net.die.phoneapi.browser

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson

/** One DevTools WebSocket. Commands are matched by id; events in between are ignored. */
internal class CdpConnection(
    input: InputStream,
    output: OutputStream,
    newKey: () -> String = ::websocketKey,
    newMask: () -> ByteArray = ::websocketMask,
) {
    private val socket = WebSocketClient(input, output, newKey, newMask)
    private var nextId = 1

    fun handshake(path: String) = socket.handshake(path)

    fun call(method: String, params: JsonObject? = null): JsonObject {
        val id = nextId++
        socket.sendText(command(id, method, params))
        while (true) {
            val found = result(message(), id, method)
            if (found != null) return found
        }
    }

    private fun result(message: JsonObject, id: Int, method: String): JsonObject? {
        val messageId = (message["id"] as? JsonPrimitive)?.longOrNull ?: return null
        if (messageId != id.toLong()) return null
        val error = message["error"]?.jsonObject
        if (error != null) {
            throw ApiException(502, "cdp_error", error["message"]?.jsonPrimitive?.content ?: method)
        }
        return message["result"] as? JsonObject ?: buildJsonObject {}
    }

    private fun message(): JsonObject =
        try {
            ApiJson.parseToJsonElement(socket.nextText()).jsonObject
        } catch (e: SerializationException) {
            throw IOException("DevTools sent malformed JSON", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("DevTools sent malformed JSON", e)
        }

    private fun command(id: Int, method: String, params: JsonObject?): String = buildJsonObject {
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }
        .toString()
}
