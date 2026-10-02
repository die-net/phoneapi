package net.die.phoneapi.server

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.die.phoneapi.core.BindMode
import net.die.phoneapi.core.Settings
import net.die.phoneapi.core.SettingsStore

/**
 * Runs the abstract-socket API while at least one holder (the foreground service, or the settings
 * UI) wants it. The HTTPS splice and mDNS run only while TLS is enabled and wireless debugging is
 * on.
 */
class ServerController(
    private val scope: CoroutineScope,
    private val server: ApiServer,
    private val network: NetworkWatcher,
    private val settings: SettingsStore,
    private val mdns: MdnsAdvertiser,
    private val wireless: StateFlow<Boolean>,
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
        runCatching { server.start() }
            .onFailure { Log.e(TAG, "Failed to open the abstract socket", it) }
        combine(network.lanAddress, settings.settings, wireless) { addr, s, wirelessOn ->
                Triple(addr, s, wirelessOn)
            }
            .distinctUntilChanged()
            .collect { (addr, s, wirelessOn) -> bindTls(addr, s, wirelessOn) }
    }

    private fun bindTls(addr: InetAddress?, s: Settings, wirelessOn: Boolean) {
        if (!s.tlsEnabled || !wirelessOn) {
            server.stopTls()
            mdns.stop()
            endpointFlow.value = null
            return
        }
        val host =
            when (s.bindMode) {
                BindMode.ALL -> "0.0.0.0"
                BindMode.LAN -> (addr as? Inet4Address)?.hostAddress
            }
        if (host == null) {
            Log.i(TAG, "TLS is on, but there is no LAN address")
            server.stopTls()
            mdns.stop()
            endpointFlow.value = null
            return
        }
        val next = Endpoint(host, s.port)
        if (next == endpointFlow.value) return
        runCatching {
            server.startTls(host, s.port)
            endpointFlow.value = next
            if (s.mdnsEnabled) mdns.advertise(s.mdnsName, s.port, s.instanceId) else mdns.stop()
            Log.i(TAG, "HTTPS splice on https://$host:${s.port}")
        }
            .onFailure {
                Log.e(TAG, "Failed to start HTTPS on $host:${s.port}", it)
                server.stopTls()
                mdns.stop()
                endpointFlow.value = null
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
