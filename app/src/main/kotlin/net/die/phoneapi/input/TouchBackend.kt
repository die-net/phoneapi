package net.die.phoneapi.input

import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.InputBackend
import net.die.phoneapi.model.TimedPoint

/** Something that can put fingers on the screen. */
interface TouchBackend {
    /** Reported in [net.die.phoneapi.model.ActionResult.backend]. */
    val name: String

    val isAvailable: Boolean

    /**
     * Performs one gesture. Each inner list is one stroke (finger down, moves, up) with times
     * relative to the gesture start; strokes that overlap in time are simultaneous pointers, and
     * strokes that don't are sequential touches. Returns false if the system cancelled it.
     */
    suspend fun perform(pointers: List<List<TimedPoint>>): Boolean
}

/** Picks a touch backend per request. Touches go through helper injection. */
class TouchBackends(private val inject: TouchBackend) {
    fun select(requested: InputBackend): TouchBackend =
        when (requested) {
            InputBackend.AUTO,
            InputBackend.INJECT ->
                inject.takeIf { it.isAvailable } ?: throw ApiException.helperUnavailable()
        }
}
