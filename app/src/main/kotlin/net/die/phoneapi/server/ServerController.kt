package net.die.phoneapi.server

import android.util.Log
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.die.phoneapi.core.BindMode
import net.die.phoneapi.core.SettingsStore

/**
 * Runs the API server while at least one holder (the accessibility service, or the settings UI)
 * wants it, and rebinds when the LAN address or settings change.
 */
class ServerController(
    private val scope: CoroutineScope,
    private val server: ApiServer,
    private val network: NetworkWatcher,
    private val settings: SettingsStore,
    private val mdns: MdnsAdvertiser,
) {
    data class Endpoint(val host: String, val port: Int)

    private val holders = mutableSetOf<String>()
    private val endpointFlow = MutableStateFlow<Endpoint?>(null)
    private var job: Job? = null

    /** Where the server is currently listening, if anywhere. */
    val endpoint: StateFlow<Endpoint?> = endpointFlow.asStateFlow()

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

    private suspend fun run() {
        combine(network.lanAddress, settings.settings) { addr, s -> addr to s }
            .distinctUntilChanged()
            .collect { (addr, s) ->
                val host =
                    when (s.bindMode) {
                        BindMode.ALL -> "0.0.0.0"
                        BindMode.LAN -> (addr as? Inet4Address)?.hostAddress
                    }
                if (host == null) {
                    Log.i(TAG, "No LAN address; server idle")
                    stopServer()
                    return@collect
                }
                val next = Endpoint(host, s.port)
                if (next == endpointFlow.value) return@collect
                runCatching {
                    server.start(host, s.port)
                    endpointFlow.value = next
                    if (s.mdnsEnabled) mdns.advertise(s.mdnsName, s.port, s.instanceId)
                    else mdns.stop()
                    Log.i(TAG, "Listening on https://$host:${s.port}")
                }
                    .onFailure {
                        Log.e(TAG, "Failed to start server on $host:${s.port}", it)
                        stopServer()
                    }
            }
    }

    private fun stopServer() {
        mdns.stop()
        server.stop()
        endpointFlow.value = null
    }

    private companion object {
        const val TAG = "PhoneApiServer"
    }
}
