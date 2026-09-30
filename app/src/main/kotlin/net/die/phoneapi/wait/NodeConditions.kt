package net.die.phoneapi.wait

import net.die.phoneapi.a11y.UiStates
import net.die.phoneapi.model.UiNode

/**
 * The node states a wait condition can ask for, tested against the best match for its selector (or
 * null when nothing matched).
 */
internal object NodeConditions {
    const val PRESENT = "present"
    const val ABSENT = "absent"

    private val TESTS: Map<String, (UiNode?) -> Boolean> =
        mapOf(
            PRESENT to { node -> node != null },
            ABSENT to { node -> node == null },
            "visible" to { node -> node != null },
            "enabled" to { node -> node.has { UiStates.DISABLED !in it } },
            "disabled" to { node -> node.has { UiStates.DISABLED in it } },
            "checked" to { node -> node.has { UiStates.CHECKED in it } },
            "unchecked" to
                { node ->
                    node.has { UiStates.CHECKABLE in it && UiStates.CHECKED !in it }
                },
            "focused" to { node -> node.has { UiStates.FOCUSED in it } },
            "selected" to { node -> node.has { UiStates.SELECTED in it } },
        )

    val names: Set<String>
        get() = TESTS.keys

    /** The test for [state], or null when the name is not one of [names]. */
    fun test(state: String): ((UiNode?) -> Boolean)? = TESTS[state.trim().lowercase()]

    /** `present` and `absent` look at the whole tree; every other state needs a visible node. */
    fun includeInvisible(state: String): Boolean =
        state.trim().lowercase().let { it == PRESENT || it == ABSENT }
}

private inline fun UiNode?.has(predicate: (List<String>) -> Boolean): Boolean =
    this != null && predicate(states)
