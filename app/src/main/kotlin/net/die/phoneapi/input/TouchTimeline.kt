package net.die.phoneapi.input

import net.die.phoneapi.model.TimedPoint

internal enum class TouchPhase {
    DOWN,
    MOVE,
    UP,
}

/** One pointer change, with [tMs] relative to the start of the gesture. */
internal data class TouchSample(
    val pointer: Int,
    val x: Float,
    val y: Float,
    val tMs: Long,
    val phase: TouchPhase,
)

/**
 * Expands stroke paths into a single time-ordered stream of pointer changes. A one-point stroke is
 * a tap: down, then up.
 */
internal fun touchTimeline(pointers: List<List<TimedPoint>>): List<TouchSample> {
    val samples = ArrayList<TouchSample>()
    pointers.forEachIndexed { pointer, path ->
        require(path.isNotEmpty()) { "A stroke needs at least one point" }
        if (path.size == 1) {
            val point = path.first()
            samples += TouchSample(pointer, point.x, point.y, point.tMs, TouchPhase.DOWN)
            samples += TouchSample(pointer, point.x, point.y, point.tMs, TouchPhase.UP)
        } else {
            path.forEachIndexed { index, point ->
                val phase =
                    when (index) {
                        0 -> TouchPhase.DOWN
                        path.lastIndex -> TouchPhase.UP
                        else -> TouchPhase.MOVE
                    }
                samples += TouchSample(pointer, point.x, point.y, point.tMs, phase)
            }
        }
    }
    return samples.sortedWith(compareBy({ it.tMs }, { it.phase.ordinal }))
}
