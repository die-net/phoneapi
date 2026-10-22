package net.die.phoneapi.lease

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.model.ApiException

/** Who can hold the screen. See docs/sharing.md. */
sealed interface Holder {
    /** An MCP session, keyed by the `Mcp-Session-Id` PhoneAPI gave it. */
    data class Session(val id: String) : Holder

    /** A person's input socket in `/viewer`. */
    data class Viewer(val id: Long) : Holder

    /** One REST call, which holds the screen only while it runs. */
    data class Rest(val id: Long) : Holder
}

/** Who has the screen now, for the viewer page and the admin routes. */
data class LeaseState(
    val holder: Holder? = null,
    val sinceMs: Long = 0,
    /** The session a viewer took the screen from, which gets it back. */
    val takenFrom: Holder.Session? = null,
)

/** A screen call's result, with notes the holder should see before it acts. */
data class Screened<T>(val value: T, val notes: List<String>)

/**
 * The one exclusive screen lease. A holder keeps it until [release], except a [Holder.Rest] call,
 * which lets it go when the call returns. Others wait first come, first served for up to [waitMs],
 * then fail with `screen_busy`. Releasing runs [cleanup] before the next holder gets the screen,
 * and the steps that failed reach that holder as notes.
 *
 * A viewer takes a free screen on input with [viewerInput], and an agent's only with [takeOver].
 * Taking over cancels the agent's screen call in progress and makes its next ones fail with
 * `screen_taken` until the viewer lets go, when the screen goes back to it without cleanup.
 */
class ScreenLease(
    private val scope: CoroutineScope,
    private val cleanup: suspend () -> List<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val waitMs: Long = WAIT_MS,
) {
    private data class Waiter(val holder: Holder, val granted: CompletableDeferred<Unit>)

    private val mutex = Mutex()
    private val queue = ArrayDeque<Waiter>()
    private val notes = HashMap<Holder, MutableList<String>>()
    private val running = HashMap<Holder, MutableSet<Job>>()
    private val taken = HashSet<Job>()
    private var cleaning = false
    private var pendingCleanup: String? = null
    private val _state = MutableStateFlow(LeaseState())

    val state: StateFlow<LeaseState> = _state.asStateFlow()

    /**
     * Runs [block] holding the screen for [holder], waiting for it first when someone else has it.
     * Throws `screen_busy` when the wait runs out and `screen_taken` when a viewer has taken the
     * screen from this holder.
     */
    suspend fun <T> withScreen(holder: Holder, block: suspend () -> T): Screened<T> {
        acquire(holder)
        try {
            val value = runTracked(holder, block)
            return Screened(value, drainNotes(holder))
        } finally {
            if (holder is Holder.Rest) {
                withContext(NonCancellable) { release(holder, cleanup = false) }
            }
        }
    }

    /** Whether [holder] has the screen now. */
    fun holds(holder: Holder): Boolean = _state.value.holder == holder

    /** A viewer's input: takes the screen if no one has it. False means someone else does. */
    suspend fun viewerInput(viewer: Holder.Viewer): Boolean = mutex.withLock {
        val current = _state.value.holder
        when {
            current == viewer -> true
            current == null && !cleaning && queue.isEmpty() -> {
                grant(viewer)
                true
            }
            else -> false
        }
    }

    /** Takes the screen for [viewer] at once, from whoever has it. */
    suspend fun takeOver(viewer: Holder.Viewer) {
        mutex.withLock {
            val current = _state.value
            if (current.holder == viewer) return
            val from =
                when (val holder = current.holder) {
                    is Holder.Session -> holder
                    else -> current.takenFrom
                }
            current.holder?.let { holder ->
                running[holder]?.forEach { job ->
                    taken += job
                    job.cancel(TakenOver())
                }
            }
            _state.value = LeaseState(viewer, now(), from)
        }
    }

    /**
     * Lets the screen go. A viewer that took it over hands it back to that session without cleanup.
     * Otherwise [cleanup] runs, when asked, before the next waiter gets it. A holder that is only
     * waiting leaves the queue.
     */
    suspend fun release(holder: Holder, cleanup: Boolean = true) {
        val clean = mutex.withLock {
            queue.removeAll { it.holder == holder }
            val current = _state.value
            if (current.takenFrom == holder) _state.value = current.copy(takenFrom = null)
            if (current.holder != holder) return
            if (holder is Holder.Viewer && current.takenFrom != null) {
                handBack(current, current.takenFrom)
                return
            }
            _state.value = LeaseState()
            if (!cleanup) grantNext()
            cleaning = cleanup
            cleanup
        }
        if (clean) scope.launch { finishCleanup(runCleanup()) }
    }

    private fun handBack(current: LeaseState, back: Holder.Session) {
        note(
            back,
            "A person used the screen from the viewer for ${seconds(current)} s. " +
                "Check the screen before acting.",
        )
        _state.value = LeaseState(back, now())
    }

    private suspend fun runCleanup(): List<String> =
        try {
            cleanup()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            listOf(e.message ?: "cleanup failed")
        }

    private suspend fun finishCleanup(failed: List<String>) {
        mutex.withLock {
            cleaning = false
            val next = grantNext()
            if (failed.isEmpty()) return
            val message =
                "Cleanup after the last holder did not finish: ${failed.joinToString("; ")}. " +
                    "Check the screen before acting."
            if (next != null) note(next, message) else pendingCleanup = message
        }
    }

    /** Forgets [holder], as when its session ends or its socket closes. */
    suspend fun forget(holder: Holder) {
        release(holder)
        mutex.withLock { notes.remove(holder) }
    }

    private suspend fun acquire(holder: Holder) {
        val waiter = mutex.withLock {
            val current = _state.value
            if (current.takenFrom == holder) throw taken(current)
            if (current.holder == holder) return
            if (current.holder == null && !cleaning && queue.isEmpty()) {
                grant(holder)
                return
            }
            Waiter(holder, CompletableDeferred()).also { queue.addLast(it) }
        }
        val caller = currentCoroutineContext().job
        val granted =
            try {
                withTimeoutOrNull(waitMs) { waiter.granted.await() } != null
            } finally {
                // A caller canceled while waiting leaves, and gives back a screen granted
                // meanwhile.
                if (!caller.isActive) {
                    withContext(NonCancellable) {
                        if (leaveQueue(waiter)) release(holder, cleanup = false)
                    }
                }
            }
        // The grant can land just as the wait runs out. Then the screen is this holder's.
        if (!granted && !withContext(NonCancellable) { leaveQueue(waiter) }) throw busy()
    }

    /** Takes [waiter] out of the queue. True when it had already been granted the screen. */
    private suspend fun leaveQueue(waiter: Waiter): Boolean = mutex.withLock {
        if (waiter.granted.isCompleted) return@withLock true
        queue.remove(waiter)
        false
    }

    /** Runs [block] as a child that [takeOver] can cancel, turning that into `screen_taken`. */
    private suspend fun <T> runTracked(holder: Holder, block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        mutex.withLock { running.getOrPut(holder) { HashSet() } += work }
        try {
            // join throws only when this caller is canceled, not when take over cancels the child.
            work.join()
            if (work.isCancelled && mutex.withLock { taken.remove(work) }) throw taken(_state.value)
            work.await()
        } finally {
            withContext(NonCancellable) { mutex.withLock { running[holder]?.remove(work) } }
        }
    }

    private fun grant(holder: Holder) {
        _state.value = LeaseState(holder, now())
        pendingCleanup?.let {
            note(holder, it)
            pendingCleanup = null
        }
    }

    private fun grantNext(): Holder? {
        val next = queue.removeFirstOrNull() ?: return null
        grant(next.holder)
        next.granted.complete(Unit)
        return next.holder
    }

    private fun note(holder: Holder, message: String) {
        notes.getOrPut(holder) { mutableListOf() } += message
    }

    private suspend fun drainNotes(holder: Holder): List<String> = mutex.withLock {
        notes.remove(holder).orEmpty()
    }

    private fun seconds(state: LeaseState): Long = (now() - state.sinceMs) / MS_PER_S

    private fun busy(): ApiException {
        val state = _state.value
        val who = if (state.holder is Holder.Viewer) " by a person in the viewer" else ""
        val held = if (state.holder == null) 0 else seconds(state)
        return ApiException(
            409,
            "screen_busy",
            "The screen has been in use$who for $held s. Try again later. Background tabs " +
                "you own still work with input cdp or cdp-mouse.",
        )
    }

    private fun taken(state: LeaseState): ApiException =
        ApiException(
            409,
            "screen_taken",
            "A person in the viewer took the screen ${seconds(state)} s ago. Stop and tell " +
                "the user; the screen comes back when they let go.",
        )

    private class TakenOver : CancellationException("A person in the viewer took the screen")

    companion object {
        const val WAIT_MS = 60_000L
        private const val MS_PER_S = 1_000
    }
}
