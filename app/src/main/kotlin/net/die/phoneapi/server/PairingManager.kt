package net.die.phoneapi.server

import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.randomToken
import net.die.phoneapi.model.Scope

/**
 * The pairing window. Closed by default; while [open], a client on the network may ask for a token
 * without holding one, and the person at the phone allows or denies each request. One request is
 * pending at a time. An approved token is handed out once, then the window closes. A token nobody
 * collects within [handoffMs] is revoked.
 */
class PairingManager(
    private val tokens: TokenGateway,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = WINDOW_MS,
    private val handoffMs: Long = HANDOFF_MS,
) {
    sealed interface State {
        data object Closed : State

        data class Open(val expiresAtMs: Long, val pending: Pending?) : State
    }

    data class Pending(val id: String, val name: String, val address: String)

    sealed interface Outcome {
        data object Waiting : Outcome

        data object Denied : Outcome

        data object Expired : Outcome

        data class Approved(val name: String, val token: String, val tokenId: String) : Outcome
    }

    private data class Request(
        val pending: Pending,
        val decided: CompletableDeferred<Outcome> = CompletableDeferred(),
    )

    private val random = SecureRandom()
    private val requests = mutableMapOf<String, Request>()
    private val stateFlow = MutableStateFlow<State>(State.Closed)
    private var expiry: Job? = null

    val state: StateFlow<State> = stateFlow.asStateFlow()

    val isOpen: Boolean
        get() = stateFlow.value is State.Open

    /** True while [id] can still be polled: pending, or decided and not yet collected. */
    @Synchronized fun knows(id: String): Boolean = id in requests

    /** Opens the window, or restarts its timer if it is already open. */
    @Synchronized
    fun open() {
        expiry?.cancel()
        val pending = (stateFlow.value as? State.Open)?.pending
        stateFlow.value = State.Open(clock() + windowMs, pending)
        expiry = scope.launch {
            delay(windowMs)
            close()
        }
    }

    @Synchronized
    fun close() {
        expiry?.cancel()
        expiry = null
        (stateFlow.value as? State.Open)?.pending?.let { finish(it.id, Outcome.Expired) }
        stateFlow.value = State.Closed
    }

    /** Queues a request from [address] and returns its id. */
    @Synchronized
    fun submit(name: String, address: String): String {
        val open = stateFlow.value as? State.Open ?: throw ApiException.notFound("pairing")
        if (open.pending != null) {
            throw ApiException(
                429,
                "pairing_busy",
                "Another request is waiting for approval on the phone",
            )
        }
        val id = randomToken(random, ID_BYTES)
        val pending = Pending(id, cleanName(name), address)
        requests[id] = Request(pending)
        stateFlow.value = open.copy(pending = pending)
        return id
    }

    @Synchronized
    fun approve(id: String) {
        val request = pendingRequest(id) ?: return
        val created = tokens.create(request.pending.name, Scope.entries.toSet())
        finish(id, Outcome.Approved(request.pending.name, created.token, created.info.id))
        expiry?.cancel()
        expiry = null
        stateFlow.value = State.Closed
    }

    @Synchronized
    fun deny(id: String) {
        val open = stateFlow.value as? State.Open ?: return
        pendingRequest(id) ?: return
        finish(id, Outcome.Denied)
        stateFlow.value = open.copy(pending = null)
    }

    /**
     * Waits up to [timeoutMs] for a decision on [id]. Null when [id] is unknown or already
     * collected. A decided outcome is returned to one caller only.
     */
    suspend fun await(id: String, timeoutMs: Long): Outcome? {
        val request = synchronized(this) { requests[id] } ?: return null
        val decided = request.decided
        val outcome =
            if (decided.isCompleted) decided.await()
            else withTimeoutOrNull(timeoutMs) { decided.await() } ?: return Outcome.Waiting
        val first = synchronized(this) { requests.remove(id) === request }
        return outcome.takeIf { first }
    }

    private fun pendingRequest(id: String): Request? {
        val open = stateFlow.value as? State.Open ?: return null
        if (open.pending?.id != id) return null
        return requests[id]
    }

    private fun finish(id: String, outcome: Outcome) {
        val request = requests[id] ?: return
        request.decided.complete(outcome)
        val tokenId = (outcome as? Outcome.Approved)?.tokenId
        scope.launch {
            delay(handoffMs)
            val uncollected = synchronized(this@PairingManager) { requests.remove(id) === request }
            if (uncollected && tokenId != null) tokens.revoke(tokenId)
        }
    }

    private fun cleanName(raw: String): String =
        raw.filterNot { it.isISOControl() }.trim().take(MAX_NAME_LENGTH).ifBlank { DEFAULT_NAME }

    companion object {
        const val WINDOW_MS = 5L * 60 * 1000
        const val HANDOFF_MS = 60_000L
        const val MAX_NAME_LENGTH = 64
        const val DEFAULT_NAME = "Computer"
        private const val ID_BYTES = 16
    }
}
