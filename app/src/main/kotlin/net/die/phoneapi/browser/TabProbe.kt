package net.die.phoneapi.browser

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException

/**
 * Tells, within a couple of seconds rather than the 15-second DevTools timeout, why a Chrome tab
 * does not answer, as docs/sharing.md describes under "Tabs that stop answering". Chrome unloads
 * hidden tabs under memory pressure, leaving them listed with no page to run script. Android can
 * freeze all of Chrome, and then nothing answers.
 */
internal class TabProbe(
    private val io: CoroutineContext,
    /** Opens a DevTools socket that gives up reading after about [probeMs]. */
    private val open: suspend (String) -> DevtoolsSocket,
    private val websocketKey: () -> String,
    private val probeMs: Long = PROBE_MS,
    private val reloadMs: Long = RELOAD_MS,
) {
    /** Whether [socket] is Chrome's and its browser endpoint does not answer. */
    suspend fun frozen(socket: String): Boolean = socket == CHROME_SOCKET && !browserAnswers(socket)

    /** Whether Chrome's browser endpoint on [socket] answers. */
    private suspend fun browserAnswers(socket: String): Boolean =
        try {
            withContext(io) {
                open(socket).use { devtools ->
                    devtools.withStreams { input, output ->
                        CdpSession(input, output, io, websocketKey).use { cdp ->
                            cdp.open("/devtools/browser")
                            withTimeoutOrNull(probeMs) { cdp.call("Browser.getVersion") } != null
                        }
                    }
                }
            }
        } catch (_: ApiException) {
            false
        } catch (_: IOException) {
            false
        }

    /**
     * Checks that [cdp]'s page has a renderer. When Chrome unloaded the tab, this reloads it in the
     * background. A [reading] caller then gets true once the fresh page has loaded; any other fails
     * with `tab_reloaded`, so it does not act on a page it has not looked at. When Chrome does not
     * answer at all, this fails with `browser_frozen`.
     */
    suspend fun revive(cdp: CdpSession, socket: String, reading: Boolean): Boolean {
        if (socket != CHROME_SOCKET || !unloaded(cdp, socket)) return false
        cdp.call("Page.reload")
        if (!reading) {
            throw ApiException(
                409,
                "tab_reloaded",
                "Chrome had unloaded this hidden tab to save memory, so PhoneAPI reloaded it. " +
                    "Anything typed or scrolled is gone; read or snapshot the tab again before " +
                    "acting on it.",
            )
        }
        awaitLoaded(cdp)
        return true
    }

    /**
     * Whether the tab has no renderer. `Inspector.enable` says so with `Inspector.targetCrashed`,
     * from the browser side, so a page whose main thread is busy is not taken for an unloaded one,
     * as a script probe with a timeout would.
     */
    private suspend fun unloaded(cdp: CdpSession, socket: String): Boolean {
        if (withTimeoutOrNull(probeMs) { cdp.call("Inspector.enable") } == null) {
            if (!browserAnswers(socket)) throw browserFrozen()
            return false
        }
        return cdp.seen("Inspector.targetCrashed")
    }

    private suspend fun evaluate(cdp: CdpSession, expression: String): JsonElement? =
        cdp.call(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("returnByValue", true)
                },
            )
            .let { (it["result"] as? JsonObject)?.get("value") }

    /** Waits, up to [reloadMs], until the reloaded page has its document. */
    private suspend fun awaitLoaded(cdp: CdpSession) {
        withTimeoutOrNull(reloadMs) {
            while (true) {
                val state = withTimeoutOrNull(probeMs) { evaluate(cdp, "document.readyState") }
                val text = (state as? JsonPrimitive)?.contentOrNull
                if (text == "interactive" || text == "complete") return@withTimeoutOrNull
                delay(POLL_MS)
            }
        }
    }

    companion object {
        const val PROBE_MS = 2_000L
        private const val RELOAD_MS = 10_000L
        private const val POLL_MS = 250L
    }
}

/** Chrome does not answer at all, which is Android's app freezer. */
internal fun browserFrozen(): ApiException =
    ApiException(
        503,
        "browser_frozen",
        "Android has frozen Chrome in the background, so its tabs cannot answer. PhoneAPI keeps " +
            "Chrome from being frozen while its helper runs. If this keeps happening, turn off " +
            "Developer options > Suspend execution for cached apps.",
    )
