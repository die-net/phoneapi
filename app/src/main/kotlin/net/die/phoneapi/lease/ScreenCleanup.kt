package net.die.phoneapi.lease

import kotlin.coroutines.cancellation.CancellationException
import net.die.phoneapi.core.ClipboardService
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.KeyRequest

/**
 * Leaves the screen in a known state between holders: keyboard hidden, Chrome on its blank
 * placeholder tab, home, locked, and no clip PhoneAPI wrote or copied left on the clipboard.
 * Locking every time, rather than only when someone waits, keeps "locked between holders" simple to
 * check, and the stored PIN makes the next unlock cheap. Returns the steps that failed.
 */
class ScreenCleanup(
    private val input: InputService,
    private val power: PowerService,
    private val clipboard: ClipboardService,
    private val showPlaceholder: suspend () -> Unit = {},
) {
    suspend fun run(): List<String> = buildList {
        step("hide the keyboard") { input.hideIme() }
        step("show the blank tab") {
            showPlaceholder()
            ActionResult(ok = true)
        }
        step("go home") { input.key(KeyRequest(key = "HOME")) }
        step("lock") { power.lock() }
        step("clear the clipboard") { clipboard.clearOwn() }
    }

    private suspend fun MutableList<String>.step(what: String, block: suspend () -> ActionResult) {
        try {
            val result = block()
            if (!result.ok) add("$what: ${result.message ?: "it did not work"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            add("$what: ${e.message ?: e.error}")
        }
    }
}
