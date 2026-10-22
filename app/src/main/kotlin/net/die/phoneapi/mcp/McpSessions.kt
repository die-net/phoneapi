package net.die.phoneapi.mcp

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.SessionInfo
import net.die.phoneapi.server.TokenGateway

/** One stateful MCP session: its transport, its server, and the token that started it. */
internal class McpSession(
    val id: String,
    val tokenId: String,
    val tokenName: String,
    val transport: StreamableHttpServerTransport,
    val server: Server,
    val startedAtMs: Long,
) {
    @Volatile
    var lastActiveMs: Long = startedAtMs
        private set

    /** Records a request at [atMs], which restarts the idle timer. */
    fun touch(atMs: Long) {
        lastActiveMs = atMs
    }
}

/**
 * The MCP sessions on `/mcp`, keyed by the `Mcp-Session-Id` PhoneAPI picks. A session ends on
 * `DELETE`, after [idleMs] without a request, or when its token is revoked, and [onEnd] then
 * releases what it held. Logs name a session only by [hashOf] its id, since `logcat_tail` lets any
 * session read the log.
 */
class McpSessions(
    private val tokens: TokenGateway,
    private val scope: CoroutineScope,
    private val onEnd: suspend (String) -> Unit = {},
    private val holdsScreen: (String) -> Boolean = { false },
    private val tabCount: (String) -> Int = { 0 },
    private val now: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = IDLE_MS,
    private val perToken: Int = PER_TOKEN,
    private val total: Int = TOTAL,
) {
    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val random = SecureRandom()

    /** Starts the sweep that ends idle sessions and those whose token was revoked. */
    fun start() {
        scope.launch {
            while (isActive) {
                delay(SWEEP_MS)
                sweep()
            }
        }
    }

    fun list(): List<SessionInfo> {
        val at = now()
        return sessions.values
            .sortedBy { it.startedAtMs }
            .map {
                SessionInfo(
                    hash = hashOf(it.id),
                    token = it.tokenName,
                    ageMs = at - it.startedAtMs,
                    idleMs = at - it.lastActiveMs,
                    holdsScreen = holdsScreen(it.id),
                    tabs = tabCount(it.id),
                )
            }
    }

    /** Ends the session whose id hashes to [hash]. False when there is none. */
    suspend fun endByHash(hash: String): Boolean {
        val id = sessions.keys.firstOrNull { hashOf(it) == hash } ?: return false
        end(id, "ended by an admin")
        return true
    }

    /** Ends idle sessions and those whose token no longer exists. */
    suspend fun sweep() {
        val at = now()
        val live = tokens.list().map { it.id }.toSet()
        for (session in sessions.values.toList()) {
            when {
                session.tokenId !in live -> end(session.id, "its token was revoked")
                at - session.lastActiveMs > idleMs -> end(session.id, "it was idle")
            }
        }
    }

    /** Ends [id]: closes its transport, which cancels its calls, then runs [onEnd]. Idempotent. */
    suspend fun end(id: String, why: String) {
        val session = sessions.remove(id) ?: return
        info("MCP session ${hashOf(id)} ended: $why")
        try {
            session.transport.close()
            session.server.close()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            runCatching { Log.w(TAG, "Closing MCP session ${hashOf(id)} failed", e) }
        }
        onEnd(id)
    }

    internal fun get(id: String): McpSession? = sessions[id]

    internal fun clock(): Long = now()

    /** Throws `too_many_sessions` when [tokenId] or the phone already has as many as allowed. */
    internal fun admit(tokenId: String) {
        val open = sessions.values
        if (open.size >= total || open.count { it.tokenId == tokenId } >= perToken) {
            throw ApiException(
                429,
                "too_many_sessions",
                "Too many MCP sessions are open: $perToken per token and $total in all. " +
                    "Close one with DELETE, or an admin can end one with DELETE /v1/sessions/{hash}.",
            )
        }
    }

    internal fun add(session: McpSession) {
        sessions[session.id] = session
        info("MCP session ${hashOf(session.id)} started for token ${session.tokenName}")
    }

    /** A transport closed itself, as on `DELETE`. */
    internal fun closed(id: String) {
        scope.launch { end(id, "the client closed it") }
    }

    // JVM unit tests have no android.util.Log, as in StreamRoutes.
    private fun info(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    internal fun newId(): String {
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    companion object {
        const val IDLE_MS = 20 * 60 * 1000L
        const val PER_TOKEN = 16
        const val TOTAL = 32
        private const val SWEEP_MS = 30_000L
        private const val ID_BYTES = 16
        private const val HASH_CHARS = 12
        private const val TAG = "PhoneApiMcp"

        /** A short hash of a session id, safe to log and to show to admins. */
        fun hashOf(id: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(id.toByteArray())
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
                .take(HASH_CHARS)
    }
}
