package net.die.phoneapi.browser

import kotlin.coroutines.CoroutineContext
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.server.CdpPipe
import net.die.phoneapi.server.CdpPipes

/** Opens a target's DevTools socket through the helper and relays it as a [CdpPipe]. */
internal class HelperCdpPipes(
    private val helper: HelperConnection,
    private val io: CoroutineContext,
) : CdpPipes {
    override suspend fun open(targetId: String): CdpPipe {
        val (socket, chromeId) = parseBrowserTargetId(targetId)
        val devtools = HelperDevtoolsSocket(helper, socket, readTimeoutMs = 0)
        val chrome = ChromeSocket(devtools.input, devtools.output, io)
        return HelperCdpPipe(chrome, devtools, "/devtools/page/$chromeId")
    }
}

private class HelperCdpPipe(
    private val chrome: ChromeSocket,
    private val devtools: HelperDevtoolsSocket,
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
