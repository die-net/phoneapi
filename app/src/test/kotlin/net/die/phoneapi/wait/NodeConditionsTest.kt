package net.die.phoneapi.wait

import net.die.phoneapi.a11y.UiStates
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.UiNode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeConditionsTest {
    private fun node(vararg states: String) =
        UiNode(ref = "e1", role = "button", bounds = Rect(0, 0, 10, 10), states = states.toList())

    private fun test(state: String, node: UiNode?) = NodeConditions.test(state)!!(node)

    @Test
    fun `tests presence and absence`() {
        assertTrue(test("present", node()))
        assertFalse(test("present", null))
        assertTrue(test("absent", null))
        assertFalse(test("absent", node()))
    }

    @Test
    fun `tests node states`() {
        assertTrue(test("checked", node(UiStates.CHECKABLE, UiStates.CHECKED)))
        assertFalse(test("checked", node(UiStates.CHECKABLE)))
        assertTrue(test("unchecked", node(UiStates.CHECKABLE)))
        // A node that can't be checked at all is neither checked nor unchecked.
        assertFalse(test("unchecked", node()))
        assertFalse(test("checked", null))
        assertTrue(test("enabled", node()))
        assertFalse(test("enabled", node(UiStates.DISABLED)))
        assertTrue(test("disabled", node(UiStates.DISABLED)))
        assertTrue(test("focused", node(UiStates.FOCUSED)))
        assertTrue(test("selected", node(UiStates.SELECTED)))
    }

    @Test
    fun `normalises state names`() {
        assertTrue(test(" Present ", node()))
        assertNull(NodeConditions.test("bouncy"))
    }

    @Test
    fun `scopes presence to the tree`() {
        assertTrue(NodeConditions.includeInvisible("present"))
        assertTrue(NodeConditions.includeInvisible("absent"))
        assertFalse(NodeConditions.includeInvisible("visible"))
        assertFalse(NodeConditions.includeInvisible("checked"))
    }
}
