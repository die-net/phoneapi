package net.die.phoneapi.browser

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.input.Humanizer
import net.die.phoneapi.input.SwipeSpec
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TimedPoint

/**
 * Pointer, key, and text for one page. `touch` is a hardware event after the tab is in front. `cdp`
 * stays on that target, including a background tab.
 */
internal class PageInput(
    private val touchAt: suspend (Rect, Boolean) -> ActionResult,
    private val swipeAt: suspend (Point, Point, SwipeSpec) -> ActionResult,
    private val gestureAt: suspend (List<List<TimedPoint>>) -> ActionResult,
    private val pressKey: suspend (KeyRequest) -> ActionResult,
    private val typeText: suspend (TextRequest) -> ActionResult,
    private val contentOnScreen: suspend (String?) -> Rect,
    private val quads: suspend (CdpSession, String, TapTarget) -> JsonArray,
    private val focusNode: suspend (CdpSession, String, TapTarget) -> JsonObject,
    private val target: (String?, String?) -> TapTarget?,
) {
    private val paths = Humanizer()

    suspend fun tap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        pkg: String?,
        request: BrowserTapRequest,
    ): ActionResult =
        when (request.input) {
            BrowserInput.TOUCH -> touchTap(cdp, id, wanted, pkg, request.humanize)
            BrowserInput.CDP -> cdpTap(cdp, id, wanted, request.humanize)
        }

    fun checkSwipe(request: BrowserSwipeRequest) {
        if (request.durationMs !in MIN_SWIPE_MS..MAX_SWIPE_MS) {
            throw ApiException.badRequest("durationMs must be $MIN_SWIPE_MS..$MAX_SWIPE_MS")
        }
        val from = request.from
        val to = request.to
        if (from != null || to != null) {
            if (from == null || to == null) {
                throw ApiException.badRequest("Pass both from and to, or a direction")
            }
            if (request.direction != null) {
                throw ApiException.badRequest("Pass from and to, or a direction, not both")
            }
        } else if (request.direction == null) {
            throw ApiException.badRequest("Pass from and to, or a direction")
        }
        target(request.ref, request.selector)
    }

    suspend fun swipe(
        cdp: CdpSession,
        id: String,
        pkg: String?,
        request: BrowserSwipeRequest,
    ): ActionResult {
        val spec = SwipeSpec(request.durationMs, request.fling, request.humanize)
        return when (request.input) {
            BrowserInput.TOUCH -> {
                foreground(cdp)
                val ends = swipeEnds(cdp, id, request)
                val metrics = cdp.call("Page.getLayoutMetrics")
                val content = contentOnScreen(pkg)
                swipeAt(
                    screenPoint(ends.first.x, ends.first.y, metrics, content),
                    screenPoint(ends.second.x, ends.second.y, metrics, content),
                    spec,
                )
            }
            BrowserInput.CDP -> {
                val ends = swipeEnds(cdp, id, request)
                val path = paths.swipe(ends.first, ends.second, spec)
                dispatchTouchPath(cdp, listOf(path))
                cdpPoints(listOf(path))
            }
        }
    }

    fun checkGesture(request: BrowserGestureRequest) {
        val pointers = request.pointers
        if (pointers.isEmpty() || pointers.any { it.isEmpty() }) {
            throw ApiException.badRequest("Each pointer needs at least one point")
        }
        if (pointers.sumOf { it.size } > MAX_GESTURE_POINTS) {
            throw ApiException.badRequest("At most $MAX_GESTURE_POINTS points per gesture")
        }
        if (pointers.any { path -> path.any { it.tMs < 0 } }) {
            throw ApiException.badRequest("tMs must be >= 0")
        }
    }

    suspend fun gesture(
        cdp: CdpSession,
        pkg: String?,
        request: BrowserGestureRequest,
    ): ActionResult =
        when (request.input) {
            BrowserInput.TOUCH -> {
                foreground(cdp)
                val metrics = cdp.call("Page.getLayoutMetrics")
                val content = contentOnScreen(pkg)
                val mapped =
                    request.pointers.map { path ->
                        path.map { point ->
                            val screen = screenPoint(point.x, point.y, metrics, content)
                            point.copy(x = screen.x, y = screen.y)
                        }
                    }
                gestureAt(mapped)
            }
            BrowserInput.CDP -> {
                dispatchTouchPath(cdp, request.pointers)
                cdpPoints(request.pointers)
            }
        }

    /** Resolves a CDP key before the socket opens. Device keys stay on `input=touch`. */
    fun prepareKey(request: BrowserKeyRequest): DomKey? =
        if (request.input == BrowserInput.CDP) domKey(request.key, request.metaState) else null

    suspend fun key(cdp: CdpSession, request: BrowserKeyRequest, pageKey: DomKey?): ActionResult =
        when (request.input) {
            BrowserInput.TOUCH -> {
                foreground(cdp)
                pressKey(
                    KeyRequest(
                        key = request.key,
                        longPress = request.longPress,
                        metaState = request.metaState,
                        autoWake = false,
                    )
                )
            }
            BrowserInput.CDP -> {
                dispatchDomKey(
                    cdp,
                    pageKey ?: domKey(request.key, request.metaState),
                    cdpModifiers(request.metaState),
                    request.longPress,
                )
                ActionResult(ok = true, backend = "cdp")
            }
        }

    fun prepareText(request: BrowserTextRequest): TapTarget? {
        checkText(request)
        return target(request.ref, request.selector)
    }

    suspend fun text(
        cdp: CdpSession,
        id: String,
        pkg: String?,
        request: BrowserTextRequest,
        focus: TapTarget?,
    ): ActionResult {
        if (request.input == BrowserInput.TOUCH) foreground(cdp)
        if (focus != null) focusField(cdp, id, focus, pkg, request.input)
        return when (request.input) {
            BrowserInput.TOUCH ->
                typeText(
                    TextRequest(
                        text = request.text,
                        mode = request.mode,
                        clear = request.clear,
                        submit = request.submit,
                        minDelayMs = request.minDelayMs,
                        maxDelayMs = request.maxDelayMs,
                        autoWake = false,
                    )
                )
            BrowserInput.CDP -> {
                dispatchInsertText(cdp, request.text, request.clear, request.submit)
                ActionResult(ok = true, backend = "cdp")
            }
        }
    }

    private suspend fun touchTap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        pkg: String?,
        humanize: Boolean,
    ): ActionResult {
        foreground(cdp)
        val boxes = quads(cdp, id, wanted)
        val hit = screenTarget(boxes, cdp.call("Page.getLayoutMetrics"), contentOnScreen(pkg))
        return touchAt(hit, humanize)
    }

    private suspend fun cdpTap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        humanize: Boolean,
    ): ActionResult {
        val boxes = quads(cdp, id, wanted)
        val point = viewportTap(boxes, cdp.call("Page.getLayoutMetrics"), humanize)
        dispatchTouch(cdp, point)
        return ActionResult(ok = true, backend = "cdp", points = listOf(point))
    }

    private suspend fun dispatchTouch(cdp: CdpSession, point: Point) {
        cdp.call(
            "Input.dispatchTouchEvent",
            buildJsonObject {
                put("type", "touchStart")
                put("touchPoints", JsonArray(listOf(touchPoint(point))))
            },
        )
        cdp.call(
            "Input.dispatchTouchEvent",
            buildJsonObject {
                put("type", "touchEnd")
                put("touchPoints", JsonArray(emptyList()))
            },
        )
    }

    private fun touchPoint(point: Point): JsonObject = buildJsonObject {
        put("x", point.x)
        put("y", point.y)
        put("id", 0)
    }

    private suspend fun foreground(cdp: CdpSession) {
        cdp.call("Page.bringToFront")
        delay(FRONT_SETTLE_MS)
    }

    private fun cdpPoints(pointers: List<List<TimedPoint>>): ActionResult {
        val points = pointers.flatMap { path ->
            if (path.size > 1) listOf(path.first(), path.last()) else listOf(path.first())
        }
        return ActionResult(ok = true, backend = "cdp", points = points.map { Point(it.x, it.y) })
    }

    private suspend fun swipeEnds(
        cdp: CdpSession,
        id: String,
        request: BrowserSwipeRequest,
    ): Pair<Point, Point> {
        val from = request.from
        val to = request.to
        if (from != null && to != null) return from to to
        val direction =
            request.direction ?: throw ApiException.badRequest("Pass from and to, or a direction")
        val boxes = target(request.ref, request.selector)?.let { quads(cdp, id, it) }
        return directionSwipe(boxes, cdp.call("Page.getLayoutMetrics"), direction, request.distance)
    }

    private suspend fun focusField(
        cdp: CdpSession,
        id: String,
        focus: TapTarget,
        pkg: String?,
        input: BrowserInput,
    ) {
        when (input) {
            BrowserInput.TOUCH -> {
                val boxes = quads(cdp, id, focus)
                val rect =
                    screenTarget(boxes, cdp.call("Page.getLayoutMetrics"), contentOnScreen(pkg))
                val tapped = touchAt(rect, false)
                if (!tapped.ok) {
                    throw ApiException(409, "gesture_cancelled", "The system cancelled the touch")
                }
            }
            BrowserInput.CDP -> cdp.call("DOM.focus", focusNode(cdp, id, focus))
        }
    }

    private fun checkText(request: BrowserTextRequest) {
        if (request.text.length > MAX_TEXT) {
            throw ApiException.badRequest("text is longer than $MAX_TEXT characters")
        }
        if (
            request.minDelayMs < 0 ||
                request.maxDelayMs < request.minDelayMs ||
                request.maxDelayMs > MAX_DELAY_MS
        ) {
            throw ApiException.badRequest("Need 0 <= minDelayMs <= maxDelayMs <= $MAX_DELAY_MS")
        }
        target(request.ref, request.selector)
    }

    private companion object {
        const val FRONT_SETTLE_MS = 200L
        const val MIN_SWIPE_MS = 20L
        const val MAX_SWIPE_MS = 10_000L
        const val MAX_GESTURE_POINTS = 10_000
        const val MAX_TEXT = 10_000
        const val MAX_DELAY_MS = 5_000L
    }
}
