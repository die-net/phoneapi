package net.die.phoneapi.input

import android.view.ViewConfiguration
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.InputBackend
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TimedPoint

data class TouchOutcome(val ok: Boolean, val backend: String, val points: List<Point>)

/** Humanized touches on top of whichever [TouchBackend] a request selects. */
class TouchInput(
    private val backends: TouchBackends,
    private val humanizer: Humanizer,
    private val display: () -> DisplayInfo,
) {
    suspend fun tap(
        target: Rect,
        count: Int = 1,
        holdMs: Long? = null,
        humanize: Boolean = true,
        backend: InputBackend = InputBackend.AUTO,
    ): TouchOutcome = perform(humanizer.taps(target, count, holdMs, humanize), backend)

    suspend fun longPress(
        target: Rect,
        humanize: Boolean = true,
        backend: InputBackend = InputBackend.AUTO,
    ): TouchOutcome =
        perform(
            listOf(
                humanizer.longPress(
                    target,
                    ViewConfiguration.getLongPressTimeout().toLong(),
                    humanize,
                )
            ),
            backend,
        )

    suspend fun swipe(
        from: Point,
        to: Point,
        spec: SwipeSpec,
        backend: InputBackend = InputBackend.AUTO,
    ): TouchOutcome {
        val rate = display().refreshRate.takeIf { it > 0f } ?: DEFAULT_RATE_HZ
        val path = humanizer.swipe(from, to, spec.copy(sampleRateHz = rate))
        return perform(listOf(path), backend, withEnds = true)
    }

    suspend fun gesture(pointers: List<List<TimedPoint>>, backend: InputBackend): TouchOutcome =
        perform(pointers, backend, withEnds = true)

    /** Reports where each stroke started, and also where it ended when [withEnds] is set. */
    private suspend fun perform(
        pointers: List<List<TimedPoint>>,
        backend: InputBackend,
        withEnds: Boolean = false,
    ): TouchOutcome {
        val selected = backends.select(backend)
        val d = display()
        val maxX = (d.widthPx - 1).toFloat()
        val maxY = (d.heightPx - 1).toFloat()
        val clamped = pointers.map { path ->
            path.map { it.copy(x = it.x.coerceIn(0f, maxX), y = it.y.coerceIn(0f, maxY)) }
        }
        val ok = selected.perform(clamped)
        val reported = clamped.flatMap { path ->
            if (withEnds && path.size > 1) listOf(path.first(), path.last())
            else listOf(path.first())
        }
        return TouchOutcome(ok, selected.name, reported.map { Point(it.x, it.y) })
    }

    private companion object {
        const val DEFAULT_RATE_HZ = 60f
    }
}
