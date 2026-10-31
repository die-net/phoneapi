package net.die.phoneapi.input

import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TimedPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TouchInputTest {
    private val display = DisplayInfo(1080, 2400, 420, 0, 60f)
    private val stroke =
        listOf(
            listOf(
                TimedPoint(-5f, 290f, 0),
                TimedPoint(500f, 270f, 8),
                TimedPoint(1200f, 2600f, 16),
            )
        )

    @Test
    fun `clamps to the display`() {
        val clamped = clampPoints(stroke, display, within = null).single()
        assertEquals(listOf(0f, 500f, 1079f), clamped.map { it.x })
        assertEquals(listOf(290f, 270f, 2399f), clamped.map { it.y })
    }

    @Test
    fun `clamps to the page`() {
        // A bow above the page's top edge would land on Chrome's toolbar.
        val page = Rect(0, 283, 1080, 2339)
        val clamped = clampPoints(stroke, display, page).single()
        assertEquals(listOf(0f, 500f, 1079f), clamped.map { it.x })
        assertEquals(listOf(290f, 283f, 2338f), clamped.map { it.y })
        assertEquals(listOf(0L, 8L, 16L), clamped.map { it.tMs })
    }
}
