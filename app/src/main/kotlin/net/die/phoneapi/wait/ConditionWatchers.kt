package net.die.phoneapi.wait

import android.os.SystemClock
import kotlinx.coroutines.withContext
import net.die.phoneapi.AppGraph
import net.die.phoneapi.a11y.NodeCompat
import net.die.phoneapi.a11y.require
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.DeviceStateSummary
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.ScreenState
import net.die.phoneapi.model.UiNode
import net.die.phoneapi.model.WaitCondition

/** Whether a condition holds right now, and when to look again if nothing else happens. */
internal data class Check(val satisfied: Boolean, val recheckAtMs: Long? = null)

internal fun interface ConditionWatcher {
    suspend fun check(): Check
}

/**
 * Turns [WaitCondition]s into watchers. Selectors and state names are validated while the watcher
 * is built, so a bad request fails before the engine starts waiting.
 */
internal class ConditionWatchers(
    private val graph: AppGraph,
    private val browser: BrowserWatch? = null,
) {
    fun watcher(condition: WaitCondition): ConditionWatcher =
        when (condition) {
            is WaitCondition.Node -> node(condition)
            is WaitCondition.Window -> window(condition)
            is WaitCondition.Ime -> deviceState { it.ime.visible == condition.visible }
            is WaitCondition.Idle -> idle(condition.quietMs)
            is WaitCondition.Screen -> deviceState { (it.screen == ScreenState.ON) == condition.on }
            is WaitCondition.Keyguard -> deviceState { it.keyguard.locked == condition.locked }
            is WaitCondition.BrowserUrl,
            is WaitCondition.BrowserLifecycle,
            is WaitCondition.BrowserNetworkIdle,
            is WaitCondition.BrowserElement,
            is WaitCondition.BrowserRequest,
            is WaitCondition.BrowserDialog,
            is WaitCondition.BrowserNewTarget,
            is WaitCondition.BrowserLog,
            is WaitCondition.BrowserSettled -> browser(condition)
        }

    private fun node(condition: WaitCondition.Node): ConditionWatcher {
        val test = NodeConditions.test(condition.state)
        val request =
            FindRequest(
                selector = condition.selector,
                limit = 1,
                includeInvisible = NodeConditions.includeInvisible(condition.state),
            )
        return ConditionWatcher { Check(test(find(request))) }
    }

    /** The best match for [request], or null; a ref that has expired counts as "not there". */
    private suspend fun find(request: FindRequest): UiNode? =
        try {
            graph.snapshots.find(request).matches.firstOrNull()
        } catch (e: ApiException) {
            if (e.error != "stale_ref") throw e
            null
        }

    private fun window(condition: WaitCondition.Window): ConditionWatcher {
        val packageName = condition.packageName
        val title = condition.titleContains
        if (packageName == null && title == null) {
            throw ApiException.badRequest("A window condition needs a package or titleContains")
        }
        return ConditionWatcher {
            val packageOk =
                packageName == null || packageName == graph.state.current.foregroundPackage
            Check(packageOk && (title == null || hasWindowTitled(title)))
        }
    }

    /** Window titles come straight off the window list, with no tree traversal. */
    private suspend fun hasWindowTitled(text: String): Boolean {
        val service = graph.a11y.require()
        return withContext(graph.ioDispatcher) {
            val windows = service.windows
            try {
                windows.any { it.title?.contains(text, ignoreCase = true) == true }
            } finally {
                NodeCompat.recycleAll(windows)
            }
        }
    }

    /** Satisfied once the UI has been quiet for [quietMs]; nothing to react to, so it's timed. */
    private fun idle(quietMs: Long): ConditionWatcher {
        if (quietMs < 0) throw ApiException.badRequest("quietMs must not be negative")
        return ConditionWatcher {
            val quietAt = graph.uiTracker.lastChangeMs + quietMs
            val now = SystemClock.uptimeMillis()
            if (now >= quietAt) Check(satisfied = true) else Check(false, recheckAtMs = quietAt)
        }
    }

    private fun deviceState(predicate: (DeviceStateSummary) -> Boolean) = ConditionWatcher {
        Check(predicate(graph.state.current))
    }

    private fun browser(condition: WaitCondition): ConditionWatcher {
        validateBrowser(condition)
        val watch = browser ?: throw browserUnavailable()
        return ConditionWatcher { watch.check(condition) }
    }

    private fun browserUnavailable() =
        ApiException(500, "internal", "Browser wait has no DevTools session")
}
