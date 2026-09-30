package net.die.phoneapi.input

import net.die.phoneapi.model.TimedPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TouchTimelineTest {
    @Test
    fun `orders a stroke`() {
        val samples =
            touchTimeline(
                listOf(
                    listOf(TimedPoint(1f, 2f, 0), TimedPoint(3f, 4f, 16), TimedPoint(5f, 6f, 40))
                )
            )
        assertEquals(
            listOf(TouchPhase.DOWN, TouchPhase.MOVE, TouchPhase.UP),
            samples.map { it.phase },
        )
        assertEquals(40L, samples.last().tMs)
    }

    @Test
    fun `a point is a tap`() {
        val samples = touchTimeline(listOf(listOf(TimedPoint(1f, 2f, 5))))
        assertEquals(listOf(TouchPhase.DOWN, TouchPhase.UP), samples.map { it.phase })
    }

    @Test
    fun `downs precede ups at one time`() {
        val first = listOf(TimedPoint(0f, 0f, 0), TimedPoint(0f, 0f, 10))
        val second = listOf(TimedPoint(1f, 1f, 10), TimedPoint(1f, 1f, 20))
        val samples = touchTimeline(listOf(first, second))
        val atTen = samples.filter { it.tMs == 10L }.map { it.phase }
        assertEquals(listOf(TouchPhase.DOWN, TouchPhase.UP), atTen)
    }
}
