package net.die.phoneapi.input

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.AppGraph
import net.die.phoneapi.a11y.NodeCompat
import net.die.phoneapi.a11y.require
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.InputService
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest

class InputServiceImpl(private val graph: AppGraph) : InputService {
    private val typer = TextTyper(graph)

    override suspend fun tap(request: TapRequest): ActionResult =
        action(request.autoWake) {
            if (request.count !in 1..MAX_TAPS)
                throw ApiException.badRequest("count must be 1..$MAX_TAPS")
            if ((request.holdMs ?: 0L) !in 0L..MAX_HOLD_MS)
                throw ApiException.badRequest("holdMs must be 0..$MAX_HOLD_MS")
            val target =
                request.selector?.let { nodeTarget(it, request.force) }
                    ?: pointTarget(request.x, request.y, request.humanize)
            val outcome =
                graph.touch.tap(
                    target,
                    request.count,
                    request.holdMs,
                    request.humanize,
                    request.backend,
                )
            result(outcome)
        }

    override suspend fun swipe(request: SwipeRequest): ActionResult =
        action(request.autoWake) {
            if (request.durationMs !in MIN_SWIPE_MS..MAX_SWIPE_MS) {
                throw ApiException.badRequest("durationMs must be $MIN_SWIPE_MS..$MAX_SWIPE_MS")
            }
            val (from, to) = swipeEnds(request)
            val spec = SwipeSpec(request.durationMs, request.fling, request.humanize)
            result(graph.touch.swipe(from, to, spec, request.backend))
        }

    override suspend fun gesture(request: GestureRequest): ActionResult =
        action(request.autoWake) {
            val pointers = request.pointers
            if (pointers.isEmpty() || pointers.any { it.isEmpty() }) {
                throw ApiException.badRequest("Each pointer needs at least one point")
            }
            if (pointers.sumOf { it.size } > MAX_GESTURE_POINTS) {
                throw ApiException.badRequest("At most $MAX_GESTURE_POINTS points per gesture")
            }
            if (pointers.any { path -> path.any { it.tMs < 0 } })
                throw ApiException.badRequest("tMs must be >= 0")
            result(graph.touch.gesture(pointers, request.backend))
        }

    override suspend fun key(request: KeyRequest): ActionResult =
        action(request.autoWake) {
            val name = request.key.trim().uppercase().removePrefix("KEYCODE_")
            val global = GLOBAL_ACTIONS[name]
            if (global != null) {
                val ok = graph.a11y.require().performGlobalAction(global)
                ActionResult(ok = ok, backend = "global")
            } else {
                val code =
                    KeyEvent.keyCodeFromString("KEYCODE_$name").takeIf {
                        it != KeyEvent.KEYCODE_UNKNOWN
                    }
                        ?: KeyEvent.keyCodeFromString(request.key.trim()).takeIf {
                            it != KeyEvent.KEYCODE_UNKNOWN
                        }
                        ?: throw ApiException.badRequest("Unknown key '${request.key}'")
                val backend = graph.keyBackends.press(code, request.metaState, request.longPress)
                ActionResult(ok = true, backend = backend)
            }
        }

    override suspend fun text(request: TextRequest): ActionResult =
        action(request.autoWake) {
            typer.type(request)
        }

    override suspend fun hideIme(): ActionResult =
        action(autoWake = true) {
            val service = graph.a11y.require()
            if (!graph.state.ime.visible) {
                ActionResult(ok = true, message = "The keyboard is already hidden")
            } else {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (awaitImeHidden()) {
                    ActionResult(ok = true, backend = "back")
                } else {
                    val controller = service.softKeyboardController
                    controller.showMode = AccessibilityService.SHOW_MODE_HIDDEN
                    val hidden = awaitImeHidden()
                    controller.showMode = AccessibilityService.SHOW_MODE_AUTO
                    ActionResult(
                        ok = hidden,
                        backend = "softKeyboardController",
                        message = if (hidden) null else "The keyboard is still showing",
                    )
                }
            }
        }

    private suspend fun awaitImeHidden(): Boolean =
        withTimeoutOrNull(IME_HIDE_WAIT_MS) { graph.state.state.first { !it.ime.visible } } != null

    /** Wakes if needed, runs [block] off the main thread, and fills in `woke` and `seq`. */
    private suspend fun action(autoWake: Boolean, block: suspend () -> ActionResult): ActionResult {
        val woke = graph.prepareForAction(autoWake)
        val result = withContext(graph.ioDispatcher) { block() }
        graph.snapshots.invalidate()
        return result.copy(woke = woke, seq = graph.uiTracker.seq.value)
    }

    private fun result(outcome: TouchOutcome) =
        ActionResult(
            ok = outcome.ok,
            backend = outcome.backend,
            points = outcome.points,
            message = if (outcome.ok) null else "The system cancelled the gesture",
        )

    private suspend fun nodeTarget(selector: NodeSelector, force: Boolean): Rect {
        val service = graph.a11y.require()
        val node = graph.snapshots.resolve(selector)
        try {
            return graph.targeting.touchTarget(service, node, force)
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private fun pointTarget(x: Float?, y: Float?, humanize: Boolean): Rect {
        if (x == null || y == null) throw ApiException.badRequest("Pass x and y, or a selector")
        val slop = if (humanize) POINT_SLOP_PX else 0
        val cx = x.toInt()
        val cy = y.toInt()
        return Rect(cx - slop, cy - slop, cx + slop, cy + slop)
    }

    private suspend fun swipeEnds(request: SwipeRequest): Pair<Point, Point> {
        val from = request.from
        val to = request.to
        if (from != null && to != null) return from to to
        val direction =
            request.direction?.lowercase()
                ?: throw ApiException.badRequest("Pass from and to, or a direction")
        val area = request.selector?.let { nodeTarget(it, force = false) } ?: screenArea()
        val d = request.distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        val cx = (area.left + area.right) / 2f
        val cy = (area.top + area.bottom) / 2f
        val dx = (area.right - area.left) * d / 2f
        val dy = (area.bottom - area.top) * d / 2f
        return when (direction) {
            "up" -> Point(cx, cy + dy) to Point(cx, cy - dy)
            "down" -> Point(cx, cy - dy) to Point(cx, cy + dy)
            "left" -> Point(cx + dx, cy) to Point(cx - dx, cy)
            "right" -> Point(cx - dx, cy) to Point(cx + dx, cy)
            else -> throw ApiException.badRequest("direction must be up, down, left or right")
        }
    }

    /** The screen minus a margin, so direction swipes don't start in the system gesture areas. */
    private fun screenArea(): Rect {
        val display = graph.deviceInfo.display()
        val mx = (display.widthPx * EDGE_MARGIN).toInt()
        val my = (display.heightPx * EDGE_MARGIN).toInt()
        return Rect(mx, my, display.widthPx - mx, display.heightPx - my)
    }

    private companion object {
        const val MAX_TAPS = 10
        const val MAX_HOLD_MS = 10_000L
        const val MIN_SWIPE_MS = 20L
        const val MAX_SWIPE_MS = 10_000L
        const val MAX_GESTURE_POINTS = 10_000
        const val POINT_SLOP_PX = 3
        const val MIN_DISTANCE = 0.05f
        const val MAX_DISTANCE = 0.95f
        const val EDGE_MARGIN = 0.12f
        const val IME_HIDE_WAIT_MS = 1_000L

        val GLOBAL_ACTIONS =
            mapOf(
                "BACK" to AccessibilityService.GLOBAL_ACTION_BACK,
                "HOME" to AccessibilityService.GLOBAL_ACTION_HOME,
                "RECENTS" to AccessibilityService.GLOBAL_ACTION_RECENTS,
                "APP_SWITCH" to AccessibilityService.GLOBAL_ACTION_RECENTS,
                "NOTIFICATIONS" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                "QUICK_SETTINGS" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                "POWER" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
                "POWER_DIALOG" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
                "LOCK" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
                "LOCK_SCREEN" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
                "SCREENSHOT" to AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
            )
    }
}
