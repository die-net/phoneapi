package net.die.phoneapi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.BindMode
import net.die.phoneapi.model.PairingInfo
import net.die.phoneapi.model.Scope

/**
 * Host-side provisioning over ADB. Protected by `android.permission.DUMP`, which only shell and
 * root hold, so other apps cannot mint tokens.
 *
 * ```
 * adb shell am broadcast -a net.die.phoneapi.CREATE_TOKEN -n <pkg>/net.die.phoneapi.ShellCommandReceiver --es name laptop
 * adb shell am broadcast -a net.die.phoneapi.SET_PIN -n <pkg>/net.die.phoneapi.ShellCommandReceiver --es pin 1234
 * adb shell am broadcast -a net.die.phoneapi.PAIR_ADB -n <pkg>/net.die.phoneapi.ShellCommandReceiver --es code 123456 --ei port 37123
 * ```
 *
 * The result data is the pairing JSON for `CREATE_TOKEN`. `SET_PIN` with no `pin` clears it. The
 * same store is writable over `PUT|DELETE /v1/device/pin` (admin scope); neither path returns the
 * PIN.
 */
class ShellCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = PhoneApiApp.graph
        when (intent.action) {
            ACTION_CREATE_TOKEN -> {
                val name = intent.getStringExtra("name") ?: "adb"
                val scopes =
                    intent
                        .getStringExtra("scopes")
                        ?.split(',')
                        ?.map { Scope.valueOf(it.trim().uppercase()) }
                        ?.toSet() ?: Scope.entries.toSet()
                val created = graph.tokens.create(name, scopes)
                val endpoint = graph.serverController.endpoint.value
                val pairing =
                    PairingInfo(
                        host = endpoint?.host.orEmpty(),
                        port = graph.settings.current.port,
                        certSha256 = graph.tls.fingerprint,
                        token = created.token,
                        name = name,
                    )
                resultCode = 0
                resultData = ApiJson.encodeToString(PairingInfo.serializer(), pairing)
            }
            ACTION_SET_BIND -> {
                val mode = intent.getStringExtra("mode")?.uppercase() ?: return
                graph.settings.update {
                    it.copy(bindMode = BindMode.valueOf(mode))
                }
                resultCode = 0
                resultData = mode
            }
            ACTION_SET_PIN -> {
                try {
                    graph.pins.write(intent.getStringExtra("pin"))
                    resultCode = 0
                    resultData = if (graph.pins.isSet) "stored" else "cleared"
                } catch (e: ApiException) {
                    resultCode = 1
                    resultData = e.message
                }
            }
            ACTION_PAIR_ADB -> {
                val code = intent.getStringExtra("code").orEmpty()
                val port = intent.getIntExtra("port", 0).takeIf { it > 0 }
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        pending.resultCode = 0
                        pending.resultData = graph.helperSupervisor.pair(code, port)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_CREATE_TOKEN = "net.die.phoneapi.CREATE_TOKEN"
        const val ACTION_SET_BIND = "net.die.phoneapi.SET_BIND"
        const val ACTION_SET_PIN = "net.die.phoneapi.SET_PIN"
        const val ACTION_PAIR_ADB = "net.die.phoneapi.PAIR_ADB"
    }
}
