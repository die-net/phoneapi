package net.die.phoneapi.helperclient

import android.os.RemoteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helper.ShellResult as HelperShellResult

/** One finished helper command. Output is truncated by the helper, not here. */
data class ShellResult(val exit: Int, val stdout: String = "", val stderr: String = "") {
    val ok: Boolean
        get() = exit == 0
}

/** A one-line reason for a failure, preferring stderr, for error messages and logs. */
fun ShellResult.failureMessage(): String =
    listOf(stderr, stdout)
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.lineSequence()
        ?.first()
        ?.take(MAX_MESSAGE)
        .orEmpty()

/**
 * Runs commands as the shell UID through the helper. Every call needs the helper, so callers that
 * have a reduced-mode fallback should check [isAvailable] first instead of catching.
 */
class HelperShell(
    private val helper: HelperConnection,
    private val dispatcher: CoroutineDispatcher,
) {
    val isAvailable: Boolean
        get() = helper.isRunning

    /** Throws the `503 helper_unavailable` [exec] would, before any other side effects. */
    fun require() {
        helper.require()
    }

    /** Throws `503 helper_unavailable` when the helper isn't running, or `503 helper_error`. */
    suspend fun exec(
        argv: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): ShellResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        val proxy = helper.require()
        val delivered =
            withContext(dispatcher) {
                try {
                    proxy.exec(argv.toTypedArray(), timeoutMs, maxOutputBytes)
                } catch (e: RemoteException) {
                    val why = e.message?.takeIf { it.isNotBlank() } ?: "the helper stopped"
                    throw helperError("`${argv.first()}` did not run: $why", e)
                }
            }
        return delivered.toShellResult()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
    }
}

private fun HelperShellResult.toShellResult(): ShellResult =
    ShellResult(exit = exitCode, stdout = stdout.orEmpty(), stderr = stderr.orEmpty())

private fun helperError(message: String, cause: Throwable? = null) =
    ApiException(503, "helper_error", message, cause = cause)

private const val MAX_MESSAGE = 200
