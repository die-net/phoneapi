package net.die.phoneapi.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import net.die.phoneapi.model.TimedPoint

/** One pointer's piece of a [GestureChunk]; times are absolute (relative to the gesture start). */
data class StrokePart(
    val pointer: Int,
    val points: List<TimedPoint>,
    /** Continues the same pointer's stroke from the previous chunk. */
    val continued: Boolean,
    /** The pointer stays down after this chunk. */
    val willContinue: Boolean,
)

data class GestureChunk(val startMs: Long, val endMs: Long, val parts: List<StrokePart>)

/**
 * Splits pointer paths into chunks that `dispatchGesture` can reproduce. Within one stroke the
 * platform moves at constant speed along the path, so paths whose speed changes are cut where it
 * changes and replayed as continued strokes.
 */
object GesturePlanner {
    private const val STATIONARY_PX = 8.0
    private const val RELATIVE_TOLERANCE = 0.3
    /** px/ms; ignores sub-pixel noise on slow or stationary pointers. */
    private const val ABSOLUTE_TOLERANCE = 0.05

    const val DEFAULT_MIN_SEGMENT_MS = 40L
    const val DEFAULT_MAX_CHUNK_MS = 60_000L

    fun plan(
        pointers: List<List<TimedPoint>>,
        minSegmentMs: Long = DEFAULT_MIN_SEGMENT_MS,
        maxChunkMs: Long = DEFAULT_MAX_CHUNK_MS,
    ): List<GestureChunk> {
        val paths = pointers.map(::normalize)
        require(paths.isNotEmpty()) { "at least one pointer is required" }
        val start = paths.minOf { it.first().tMs }
        val end = paths.maxOf { it.last().tMs }
        val cuts = sortedSetOf(start, end)
        paths.forEach { cuts += speedBreaks(it, minSegmentMs) }
        var t = start + maxChunkMs
        while (t < end) {
            cuts += t
            t += maxChunkMs
        }
        return cuts.zipWithNext().map { (from, to) -> chunk(paths, from, to) }
    }

    private fun chunk(paths: List<List<TimedPoint>>, from: Long, to: Long): GestureChunk {
        val parts = paths.mapIndexedNotNull { index, path ->
            val s = max(from, path.first().tMs)
            val e = minOf(to, path.last().tMs)
            if (e <= s) return@mapIndexedNotNull null
            StrokePart(
                pointer = index,
                points = slice(path, s, e),
                continued = path.first().tMs < from,
                willContinue = path.last().tMs > to,
            )
        }
        return GestureChunk(from, to, parts)
    }

    private fun normalize(path: List<TimedPoint>): List<TimedPoint> {
        require(path.isNotEmpty()) { "pointer paths must not be empty" }
        val out = ArrayList<TimedPoint>(path.size + 1)
        for (p in path) {
            val last = out.lastOrNull()
            require(last == null || p.tMs >= last.tMs) { "pointer times must not decrease" }
            if (last != null && p.tMs == last.tMs) out[out.lastIndex] = p else out += p
        }
        if (out.size == 1) out += out[0].copy(tMs = out[0].tMs + 1)
        return out
    }

    private fun speedBreaks(path: List<TimedPoint>, minSegmentMs: Long): List<Long> {
        val total = path.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        if (total < STATIONARY_PX || path.size < 3) return emptyList()
        val breaks = ArrayList<Long>()
        var segmentStart = path[0].tMs
        var reference = speed(path[0], path[1])
        for (i in 1 until path.lastIndex) {
            val current = speed(path[i], path[i + 1])
            if (path[i].tMs - segmentStart >= minSegmentMs && differs(current, reference)) {
                breaks += path[i].tMs
                segmentStart = path[i].tMs
                reference = current
            }
        }
        return breaks
    }

    private fun slice(path: List<TimedPoint>, from: Long, to: Long): List<TimedPoint> {
        val out = ArrayList<TimedPoint>()
        out += positionAt(path, from)
        path.filterTo(out) { it.tMs in (from + 1) until to }
        out += positionAt(path, to)
        return out
    }

    private fun positionAt(path: List<TimedPoint>, t: Long): TimedPoint {
        val after = path.indexOfFirst { it.tMs >= t }
        if (after <= 0) return path.first().copy(tMs = t)
        val a = path[after - 1]
        val b = path[after]
        if (b.tMs == t) return b
        val f = (t - a.tMs).toFloat() / (b.tMs - a.tMs)
        return TimedPoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, t)
    }

    private fun differs(a: Double, b: Double) =
        abs(a - b) > RELATIVE_TOLERANCE * max(a, b) + ABSOLUTE_TOLERANCE

    private fun speed(a: TimedPoint, b: TimedPoint): Double =
        distance(a, b) / (b.tMs - a.tMs).coerceAtLeast(1)

    private fun distance(a: TimedPoint, b: TimedPoint): Double =
        hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
}
