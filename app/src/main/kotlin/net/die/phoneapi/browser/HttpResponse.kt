package net.die.phoneapi.browser

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal fun httpRequest(method: String, path: String): ByteArray =
    asciiLines("$method $path HTTP/1.1", "Host: localhost", "Connection: close")

internal fun asciiLines(vararg lines: String): ByteArray {
    val out = ByteArrayOutputStream()
    for (line in lines) {
        out.write(line.toByteArray(Charsets.US_ASCII))
        out.write(CR)
        out.write(LF)
    }
    out.write(CR)
    out.write(LF)
    return out.toByteArray()
}

/**
 * Reads one HTTP response and leaves anything after the body in the stream, so a WebSocket
 * handshake can be followed by frames on the same socket.
 */
internal fun InputStream.readHttpResponse(maxBody: Int): HttpResponse {
    val headerBytes = readHeaderBlock(MAX_HEADER)
    val headerText = headerBytes.decodeToString()
    val lines = headerText.split(EOL)
    val status =
        lines.firstOrNull()?.substringAfter(' ')?.substringBefore(' ')?.toIntOrNull()
            ?: throw IOException("DevTools sent no HTTP status")
    val headers = HashMap<String, String>()
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    val body =
        when {
            headers["transfer-encoding"]?.contains("chunked") == true -> readChunked(maxBody)
            headers["content-length"] != null -> {
                val length =
                    headers.getValue("content-length").toIntOrNull()
                        ?: throw IOException("DevTools sent a bad Content-Length")
                if (length > maxBody) throw IOException("DevTools response is too large")
                readExact(length)
            }
            else -> ByteArray(0)
        }
    return HttpResponse(status, headers, body)
}

private fun InputStream.readHeaderBlock(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    var matched = 0
    while (matched < HEADER_END.size) {
        if (out.size() >= max) throw IOException("DevTools HTTP header is too large")
        val value = read()
        if (value < 0) throw EOFException("DevTools closed before the HTTP header finished")
        out.write(value)
        matched =
            if (value == HEADER_END[matched].toInt()) {
                matched + 1
            } else if (value == HEADER_END[0].toInt()) {
                1
            } else {
                0
            }
    }
    return out.toByteArray()
}

private fun InputStream.readChunked(maxBody: Int): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
        val sizeLine = readLine()
        val size =
            sizeLine.substringBefore(';').trim().toIntOrNull(HEX)
                ?: throw IOException("Bad chunk size")
        if (size == 0) {
            readLine()
            return out.toByteArray()
        }
        if (out.size() + size > maxBody) throw IOException("DevTools response is too large")
        out.write(readExact(size))
        readExact(CRLF.size)
    }
}

private fun InputStream.readLine(): String {
    val out = ByteArrayOutputStream()
    while (out.size() < MAX_HEADER) {
        val value = read()
        if (value < 0) throw EOFException("DevTools closed mid-line")
        if (value == '\n'.code) {
            val text = out.toByteArray().decodeToString()
            return if (text.endsWith('\r')) text.dropLast(1) else text
        }
        out.write(value)
    }
    throw IOException("DevTools line is too long")
}

internal fun InputStream.readExact(count: Int): ByteArray {
    if (count == 0) return ByteArray(0)
    val buf = ByteArray(count)
    var off = 0
    while (off < count) {
        val read = read(buf, off, count - off)
        if (read < 0) throw EOFException("DevTools closed after $off of $count bytes")
        off += read
    }
    return buf
}

private val EOL = charArrayOf('\r', '\n').concatToString()
private const val CR = '\r'.code
private const val LF = '\n'.code
private const val HEX = 16
private const val MAX_HEADER = 16 * 1024

private val HEADER_END =
    byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
