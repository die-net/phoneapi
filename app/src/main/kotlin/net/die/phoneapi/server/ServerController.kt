package net.die.phoneapi.server

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the abstract-socket API while at least one holder (the foreground service, or the settings
 * UI) wants it.
 */
class ServerController(
    private val scope: CoroutineScope,
    private val server: ApiServer,
) {
    private val holders = mutableSetOf<String>()
    private var job: Job? = null

    @Synchronized
    fun acquire(holder: String) {
        holders += holder
        if (job == null) job = scope.launch { run() }
    }

    @Synchronized
    fun release(holder: String) {
        holders -= holder
        if (holders.isEmpty()) {
            job?.cancel()
            job = null
            stopServer()
        }
    }

    private fun run() {
        runCatching { server.start() }
            .onFailure { Log.e(TAG, "Failed to open the abstract socket", it) }
    }

    private fun stopServer() {
        server.stop()
    }

    private companion object {
        const val TAG = "PhoneApiServer"
    }
}
