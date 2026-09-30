package net.die.phoneapi.input

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TimedPoint

data class SwipeSpec(
    val durationMs: Long = 300,
    /** Keep velocity at release instead of settling before lift. */
    val fling: Boolean = true,
    val humanize: Boolean = true,
    val sampleRateHz: Float = 60f,
)

/**
 * Turns targets into touch paths that look like a finger. Every path starts at its own `tMs`
 * (relative to the gesture start); paths that don't overlap in time are sequential touches.
 */
class Humanizer(private val random: Random = Random.Default) {

    /** One tap: jittered inside the inner [INNER_FRACTION] of [target], held with micro-moves. */
    fun tap(target: Rect, holdMs: Long? = null, humanize: Boolean = true): List<TimedPoint> =
        tapAt(pointIn(target, humanize), target, holdMs, humanize, startMs = 0)

    /** [count] taps on [target]; later taps land close to the first, like a double tap. */
    fun taps(
        target: Rect,
        count: Int,
        holdMs: Long? = null,
        humanize: Boolean = true,
    ): List<List<TimedPoint>> {
        require(count >= 1) { "count must be at least 1" }
        val first = pointIn(target, humanize)
        val strokes = ArrayList<List<TimedPoint>>(count)
        var start = 0L
        repeat(count) { i ->
            val point =
                if (i == 0 || !humanize) first
                else
                    Point(
                        clamp(first.x + gaussian(REPEAT_TAP_SIGMA_PX), target.left, target.right),
                        clamp(first.y + gaussian(REPEAT_TAP_SIGMA_PX), target.top, target.bottom),
                    )
            val stroke = tapAt(point, target, holdMs, humanize, start)
            strokes += stroke
            val gap =
                if (humanize) random.nextLong(MIN_TAP_GAP_MS, MAX_TAP_GAP_MS + 1)
                else PLAIN_TAP_GAP_MS
            start = stroke.last().tMs + gap
        }
        return strokes
    }

    /** A press held past [longPressTimeoutMs] so the platform reports a long click. */
    fun longPress(
        target: Rect,
        longPressTimeoutMs: Long,
        humanize: Boolean = true,
    ): List<TimedPoint> {
        val extra =
            if (humanize) random.nextLong(MIN_LONG_PRESS_EXTRA_MS, MAX_LONG_PRESS_EXTRA_MS + 1)
            else PLAIN_LONG_PRESS_EXTRA_MS
        return tapAt(
            pointIn(target, humanize),
            target,
            longPressTimeoutMs + extra,
            humanize,
            startMs = 0,
        )
    }

    /**
     * A swipe from [from] to [to]. Humanized swipes follow a quadratic Bézier with a small
     * perpendicular bow, ease in (and out unless flinging), and carry sub-pixel noise.
     */
    fun swipe(from: Point, to: Point, spec: SwipeSpec = SwipeSpec()): List<TimedPoint> {
        val length = hypot(to.x - from.x, to.y - from.y)
        val duration =
            (if (spec.humanize) spec.durationMs * random.nextDouble(0.9, 1.1)
                else spec.durationMs.toDouble())
                .roundToLong()
                .coerceAtLeast(MIN_SWIPE_MS)
        val control = if (spec.humanize) bowControl(from, to, length) else midpoint(from, to)
        val rate = spec.sampleRateHz.coerceIn(MIN_SAMPLE_HZ, MAX_SAMPLE_HZ)
        val steps = ceil(duration * rate / MS_PER_SECOND).toInt().coerceAtLeast(2)
        val points = ArrayList<TimedPoint>(steps + 2)
        for (i in 0..steps) {
            val u = i.toDouble() / steps
            val t = (u * duration).roundToLong()
            if (points.isNotEmpty() && t <= points.last().tMs) continue
            val s = progress(u, spec)
            var p = bezier(from, control, to, s)
            if (spec.humanize && i in 1 until steps) {
                p = Point(p.x + gaussian(PATH_NOISE_SIGMA_PX), p.y + gaussian(PATH_NOISE_SIGMA_PX))
            }
            points += TimedPoint(p.x, p.y, t)
        }
        if (!spec.fling) {
            val dwell =
                if (spec.humanize) random.nextLong(MIN_SETTLE_MS, MAX_SETTLE_MS + 1)
                else PLAIN_SETTLE_MS
            points += TimedPoint(to.x, to.y, points.last().tMs + dwell)
        }
        return points
    }

    private fun tapAt(
        point: Point,
        target: Rect,
        holdMs: Long?,
        humanize: Boolean,
        startMs: Long,
    ): List<TimedPoint> {
        if (!humanize) {
            val hold = (holdMs ?: PLAIN_HOLD_MS).coerceAtLeast(1)
            return listOf(
                TimedPoint(point.x, point.y, startMs),
                TimedPoint(point.x, point.y, startMs + hold),
            )
        }
        val hold =
            holdMs?.let {
                (it * random.nextDouble(HOLD_JITTER_LOW, HOLD_JITTER_HIGH)).roundToLong()
            } ?: random.nextLong(MIN_HOLD_MS, MAX_HOLD_MS + 1)
        val safeHold = hold.coerceAtLeast(MIN_MICRO_MOVES.toLong() + 1)
        val moveCount = random.nextInt(MIN_MICRO_MOVES, MAX_MICRO_MOVES + 1)
        val times = generateSequence {
            random.nextLong(1, safeHold)
        }
            .take(moveCount * 4)
            .distinct()
            .take(moveCount)
            .sorted()
            .toList()
        val path = ArrayList<TimedPoint>(times.size + 2)
        path += TimedPoint(point.x, point.y, startMs)
        var x = point.x
        var y = point.y
        for (t in times) {
            x =
                clamp(
                    x + random.nextDouble(-MICRO_MOVE_PX, MICRO_MOVE_PX).toFloat(),
                    target.left,
                    target.right,
                )
            y =
                clamp(
                    y + random.nextDouble(-MICRO_MOVE_PX, MICRO_MOVE_PX).toFloat(),
                    target.top,
                    target.bottom,
                )
            path += TimedPoint(x, y, startMs + t)
        }
        path += TimedPoint(x, y, startMs + safeHold)
        return path
    }

    private fun pointIn(target: Rect, humanize: Boolean): Point {
        val cx = (target.left + target.right) / 2f
        val cy = (target.top + target.bottom) / 2f
        if (!humanize) return Point(cx, cy)
        val halfW = (target.right - target.left) * INNER_FRACTION / 2f
        val halfH = (target.bottom - target.top) * INNER_FRACTION / 2f
        return Point(
            cx + gaussian(halfW / 2f).coerceIn(-halfW, halfW),
            cy + gaussian(halfH / 2f).coerceIn(-halfH, halfH),
        )
    }

    private fun bowControl(from: Point, to: Point, length: Float): Point {
        val mid = midpoint(from, to)
        if (length < 1f) return mid
        val offset =
            length *
                random.nextDouble(MIN_BOW_FRACTION, MAX_BOW_FRACTION).toFloat() *
                (if (random.nextBoolean()) 1f else -1f)
        // Unit normal to the chord.
        val nx = -(to.y - from.y) / length
        val ny = (to.x - from.x) / length
        // The curve's peak deviation is half the control point's, so double it.
        return Point(mid.x + nx * offset * 2f, mid.y + ny * offset * 2f)
    }

    private fun progress(u: Double, spec: SwipeSpec): Double =
        when {
            !spec.humanize -> u
            spec.fling -> easeInThenCruise(u)
            else -> u * u * (3 - 2 * u)
        }

    /**
     * Velocity ramps linearly from zero to its peak over the first [FLING_RAMP] of the gesture and
     * stays there until release.
     */
    private fun easeInThenCruise(u: Double): Double {
        val peak = 1.0 / (1.0 - FLING_RAMP / 2)
        return if (u < FLING_RAMP) peak * u * u / (2 * FLING_RAMP)
        else peak * (FLING_RAMP / 2 + (u - FLING_RAMP))
    }

    /** Standard normal via Box-Muller, scaled by [sigma]. */
    private fun gaussian(sigma: Float): Float {
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)).toFloat() * sigma
    }

    private companion object {
        const val INNER_FRACTION = 0.6f
        const val MIN_HOLD_MS = 50L
        const val MAX_HOLD_MS = 120L
        const val PLAIN_HOLD_MS = 60L
        const val HOLD_JITTER_LOW = 0.95
        const val HOLD_JITTER_HIGH = 1.05
        const val MIN_MICRO_MOVES = 1
        const val MAX_MICRO_MOVES = 3
        const val MICRO_MOVE_PX = 1.0
        const val MIN_TAP_GAP_MS = 90L
        const val MAX_TAP_GAP_MS = 160L
        const val PLAIN_TAP_GAP_MS = 120L
        const val REPEAT_TAP_SIGMA_PX = 2f
        const val MIN_LONG_PRESS_EXTRA_MS = 150L
        const val MAX_LONG_PRESS_EXTRA_MS = 350L
        const val PLAIN_LONG_PRESS_EXTRA_MS = 200L
        const val MIN_BOW_FRACTION = 0.02
        const val MAX_BOW_FRACTION = 0.06
        const val FLING_RAMP = 0.4
        const val PATH_NOISE_SIGMA_PX = 0.25f
        const val MIN_SETTLE_MS = 40L
        const val MAX_SETTLE_MS = 90L
        const val PLAIN_SETTLE_MS = 60L
        const val MIN_SWIPE_MS = 20L
        const val MIN_SAMPLE_HZ = 30f
        const val MAX_SAMPLE_HZ = 240f
        const val MS_PER_SECOND = 1000.0
    }
}

private fun midpoint(a: Point, b: Point) = Point((a.x + b.x) / 2f, (a.y + b.y) / 2f)

private fun bezier(p0: Point, c: Point, p1: Point, s: Double): Point {
    val t = s.toFloat()
    val inv = 1f - t
    return Point(
        inv * inv * p0.x + 2 * inv * t * c.x + t * t * p1.x,
        inv * inv * p0.y + 2 * inv * t * c.y + t * t * p1.y,
    )
}

private fun clamp(v: Float, low: Int, high: Int): Float =
    if (high <= low) low.toFloat() else v.coerceIn(low.toFloat(), high.toFloat())
