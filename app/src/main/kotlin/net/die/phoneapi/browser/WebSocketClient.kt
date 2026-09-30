package net.die.phoneapi.browser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class WebSocketClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val newKey: () -> String = ::websocketKey,
    private val newMask: () -> ByteArray = ::websocketMask,
) {
    fun handshake(path: String) {
        val key = newKey()
        output.write(websocketRequest(path, key))
        output.flush()
        val response = input.readHttpResponse(MAX_HANDSHAKE_BODY)
        if (response.status != SWITCHING_PROTOCOLS) {
            throw IOException("DevTools WebSocket was refused (${response.status})")
        }
        if (response.headers["sec-websocket-accept"] != websocketAccept(key)) {
            throw IOException("DevTools WebSocket accept key did not match")
        }
    }

    fun sendText(text: String) {
        output.write(clientFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8), newMask()))
        output.flush()
    }

    /** The next data message. Ping frames are answered and do not count. */
    fun nextText(): String {
        val message = ByteArrayOutputStream()
        while (true) {
            val frame = input.readServerFrame()
            when (frame.opcode) {
                OPCODE_TEXT -> {
                    if (message.size() > 0)
                        throw IOException("DevTools mixed a new message into a fragment")
                    message.write(frame.payload)
                    if (frame.fin) return message.toByteArray().decodeToString()
                }
                OPCODE_CONTINUATION -> {
                    message.write(frame.payload)
                    if (frame.fin) return message.toByteArray().decodeToString()
                }
                OPCODE_PING -> {
                    output.write(clientFrame(OPCODE_PONG, frame.payload, newMask()))
                    output.flush()
                }
                OPCODE_CLOSE -> throw IOException("DevTools closed the WebSocket")
                else -> Unit
            }
        }
    }
}

internal fun websocketKey(): String {
    val bytes = ByteArray(KEY_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

internal fun websocketAccept(key: String): String {
    val digest =
        MessageDigest.getInstance("SHA-1")
            .digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
    return Base64.getEncoder().encodeToString(digest)
}

internal fun websocketRequest(path: String, key: String): ByteArray =
    asciiLines(
        "GET $path HTTP/1.1",
        "Host: localhost",
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Key: $key",
        "Sec-WebSocket-Version: 13",
    )

internal fun clientFrame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
    require(mask.size == MASK_BYTES)
    val out = ByteArrayOutputStream()
    out.write(FIN or opcode)
    writeLength(out, payload.size, masked = true)
    out.write(mask)
    for (index in payload.indices) {
        out.write(payload[index].toInt() xor mask[index % MASK_BYTES].toInt())
    }
    return out.toByteArray()
}

internal fun serverTextFrame(text: String): ByteArray {
    val payload = text.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream()
    out.write(FIN or OPCODE_TEXT)
    writeLength(out, payload.size, masked = false)
    out.write(payload)
    return out.toByteArray()
}

private fun InputStream.readServerFrame(): WsFrame {
    val first = read()
    val second = read()
    if (first < 0 || second < 0) throw IOException("DevTools closed the WebSocket")
    val fin = first and FIN != 0
    val opcode = first and OPCODE_MASK
    val masked = second and FIN != 0
    var length = (second and LENGTH_MASK).toLong()
    if (length == LENGTH_16.toLong()) {
        length = readExact(SHORT_BYTES).toBigEndianLong()
    } else if (length == LENGTH_64.toLong()) {
        length = readExact(LONG_BYTES).toBigEndianLong()
    }
    if (length > MAX_FRAME) throw IOException("DevTools frame is too large")
    val mask = if (masked) readExact(MASK_BYTES) else null
    val payload = readExact(length.toInt())
    if (mask != null) {
        for (index in payload.indices) {
            payload[index] = (payload[index].toInt() xor mask[index % MASK_BYTES].toInt()).toByte()
        }
    }
    return WsFrame(opcode, fin, payload)
}

private fun writeLength(out: ByteArrayOutputStream, length: Int, masked: Boolean) {
    val maskBit = if (masked) FIN else 0
    when {
        length < LENGTH_16 -> out.write(maskBit or length)
        length <= SHORT_MAX -> {
            out.write(maskBit or LENGTH_16)
            out.write(length ushr BYTE_BITS)
            out.write(length and BYTE_MASK)
        }
        else -> {
            out.write(maskBit or LENGTH_64)
            var remaining = length.toLong()
            val wide = ByteArray(LONG_BYTES)
            for (index in wide.indices.reversed()) {
                wide[index] = (remaining and BYTE_MASK.toLong()).toByte()
                remaining = remaining ushr BYTE_BITS
            }
            out.write(wide)
        }
    }
}

private fun ByteArray.toBigEndianLong(): Long =
    fold(0L) { acc, byte -> (acc shl BYTE_BITS) or (byte.toLong() and BYTE_MASK.toLong()) }

private data class WsFrame(val opcode: Int, val fin: Boolean, val payload: ByteArray)

internal fun websocketMask(): ByteArray {
    val mask = ByteArray(MASK_BYTES)
    SecureRandom().nextBytes(mask)
    return mask
}

internal const val OPCODE_CONTINUATION = 0x0
internal const val OPCODE_TEXT = 0x1
internal const val OPCODE_PING = 0x9
internal const val OPCODE_PONG = 0xA
internal const val OPCODE_CLOSE = 0x8

private const val FIN = 0x80
private const val OPCODE_MASK = 0x0F
private const val LENGTH_MASK = 0x7F
private const val LENGTH_16 = 126
private const val LENGTH_64 = 127
private const val MASK_BYTES = 4
private const val KEY_BYTES = 16
private const val SHORT_BYTES = 2
private const val LONG_BYTES = 8
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFF
private const val SHORT_MAX = 0xFFFF
private const val MAX_FRAME = 4 * 1024 * 1024
private const val MAX_HANDSHAKE_BODY = 64 * 1024
private const val SWITCHING_PROTOCOLS = 101
private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
