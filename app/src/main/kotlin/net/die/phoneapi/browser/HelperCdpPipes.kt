package net.die.phoneapi.browser

import kotlin.coroutines.CoroutineContext
import net.die.phoneapi.server.CdpPipe
import net.die.phoneapi.server.CdpPipes

/** Opens a target's DevTools socket and relays it as a [CdpPipe]. */
internal class HelperCdpPipes(
    private val openSocket: suspend (String, Long) -> DevtoolsSocket,
    private val io: CoroutineContext,
) : CdpPipes {
    @Suppress("MissingUseCall") // HelperCdpPipe.close closes the socket.
    override suspend fun open(targetId: String): CdpPipe {
        val (socket, chromeId) = parseBrowserTargetId(targetId)
        val devtools = openSocket(socket, 0)
        val chrome = ChromeSocket(devtools.input, devtools.output, io)
        return HelperCdpPipe(chrome, devtools, "/devtools/page/$chromeId")
    }
}

private class HelperCdpPipe(
    private val chrome: ChromeSocket,
    private val devtools: DevtoolsSocket,
    private val path: String,
) : CdpPipe {
    override fun handshake() = chrome.handshake(path)

    override fun connect() = chrome.connect()

    override suspend fun relayText(send: suspend (String) -> Unit) = chrome.relayText(send)

    override suspend fun sendText(text: String) = chrome.sendText(text)

    override fun close() {
        try {
            chrome.close()
        } finally {
            devtools.close()
        }
    }
}
