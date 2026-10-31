package net.die.phoneapi.input

import android.view.ViewConfiguration
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.channels.ReceiveChannel
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TimedPoint

data class TouchOutcome(val ok: Boolean, val backend: String, val points: List<Point>)

/** Humanized touches injected through the helper. */
class TouchInput(
    private val inject: InjectTouchBackend,
    private val humanizer: Humanizer,
    private val display: () -> DisplayInfo,
) {
    suspend fun tap(
        target: Rect,
        count: Int = 1,
        holdMs: Long? = null,
        humanize: Boolean = true,
    ): TouchOutcome {
        val strokes = humanizer.taps(target, count, holdMs, humanize)
        val fingers = strokes.map { humanizer.finger(it.size, FingerMotion.PRESS, humanize) }
        return perform(strokes, fingers)
    }

    suspend fun longPress(target: Rect, humanize: Boolean = true): TouchOutcome {
        val path =
            humanizer.longPress(
                target,
                ViewConfiguration.getLongPressTimeout().toLong(),
                humanize,
            )
        val fingers = listOf(humanizer.finger(path.size, FingerMotion.PRESS, humanize))
        return perform(listOf(path), fingers)
    }

    /** A swipe from [from] to [to], kept inside [within] when given, else on the display. */
    suspend fun swipe(
        from: Point,
        to: Point,
        spec: SwipeSpec,
        within: Rect? = null,
    ): TouchOutcome {
        val rate = display().refreshRate.takeIf { it > 0f } ?: DEFAULT_RATE_HZ
        val tuned = spec.copy(sampleRateHz = rate)
        val path = humanizer.swipe(from, to, tuned)
        val motion = if (tuned.fling) FingerMotion.FLING else FingerMotion.DRAG
        val fingers = listOf(humanizer.finger(path.size, motion, tuned.humanize))
        return perform(listOf(path), fingers, withEnds = true, within = within)
    }

    /** A gesture along [pointers], kept inside [within] when given, else on the display. */
    suspend fun gesture(
        pointers: List<List<TimedPoint>>,
        humanize: Boolean = true,
        within: Rect? = null,
    ): TouchOutcome {
        val human = humanizer.humanGesture(pointers, humanize)
        return perform(human.pointers, human.fingers, withEnds = true, within = within)
    }

    suspend fun playLive(
        first: PointerFrame,
        frames: ReceiveChannel<PointerFrame>,
        clamp: (PointerFrame) -> PointerFrame,
    ) {
        inject.playLive(first, frames, clamp)
    }

    /** Reports where each stroke started, and also where it ended when [withEnds] is set. */
    private suspend fun perform(
        pointers: List<List<TimedPoint>>,
        fingers: List<List<FingerShape>> = emptyList(),
        withEnds: Boolean = false,
        within: Rect? = null,
    ): TouchOutcome {
        val clamped = clampPoints(pointers, display(), within)
        val ok = inject.shapedPerform(clamped, fingers)
        val reported = clamped.flatMap { path ->
            if (withEnds && path.size > 1) listOf(path.first(), path.last())
            else listOf(path.first())
        }
        return TouchOutcome(ok, inject.name, reported.map { Point(it.x, it.y) })
    }

    private companion object {
        const val DEFAULT_RATE_HZ = 60f
    }
}

/**
 * Clamps [pointers] to [display], and to [within] when given, so humanized jitter and bow near an
 * edge stay inside it.
 */
internal fun clampPoints(
    pointers: List<List<TimedPoint>>,
    display: DisplayInfo,
    within: Rect?,
): List<List<TimedPoint>> {
    var minX = 0f
    var minY = 0f
    var maxX = (display.widthPx - 1).toFloat()
    var maxY = (display.heightPx - 1).toFloat()
    if (within != null) {
        minX = max(minX, within.left.toFloat())
        minY = max(minY, within.top.toFloat())
        maxX = max(minX, min(maxX, (within.right - 1).toFloat()))
        maxY = max(minY, min(maxY, (within.bottom - 1).toFloat()))
    }
    return pointers.map { path ->
        path.map { it.copy(x = it.x.coerceIn(minX, maxX), y = it.y.coerceIn(minY, maxY)) }
    }
}
