package net.die.phoneapi.input

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.tree.TreeSession

/**
 * Focuses an editable node and clicks it so the IME appears. Callers run this inside the input
 * action wrapper, off the main thread.
 */
internal class ImeShow(
    private val tree: TreeSession,
    private val state: DeviceStateTracker,
) {
    suspend fun show(request: ImeShowRequest): ActionResult {
        if (request.selector == null && state.ime.visible) {
            return ActionResult(ok = true, message = "The keyboard is already visible")
        }
        tree.focus(request.selector)
        val shown =
            state.ime.visible ||
                withTimeoutOrNull(SHOW_WAIT_MS) { state.state.first { it.ime.visible } } != null
        return ActionResult(
            ok = shown,
            backend = "focus",
            message = if (shown) null else "The keyboard did not appear",
        )
    }

    private companion object {
        const val SHOW_WAIT_MS = 2_000L
    }
}
