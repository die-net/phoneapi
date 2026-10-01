package net.die.phoneapi

import android.content.BroadcastReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.BindMode
import net.die.phoneapi.model.PairingInfo
import net.die.phoneapi.model.Scope

/**
 * Host-side provisioning over ADB. Protected by `android.permission.DUMP`, which only shell and
 * root hold, so other apps cannot mint tokens.
 *
 * ```
 * adb shell am broadcast -a net.die.phoneapi.CREATE_TOKEN -n <pkg>/net.die.phoneapi.ShellCommandReceiver --es name laptop --es scopes observe,control
 * adb shell am broadcast -a net.die.phoneapi.SET_BIND -n <pkg>/net.die.phoneapi.ShellCommandReceiver --es mode ALL
 * adb shell am broadcast -a net.die.phoneapi.SET_PIN -n <pkg>/net.die.phoneapi.ShellCommandReceiver --es pin 1234
 * adb shell am broadcast -a net.die.phoneapi.PAIR_ADB -n <pkg>/net.die.phoneapi.ShellCommandReceiver --es code 123456 --ei port 37123
 * ```
 *
 * The result data is the pairing JSON for `CREATE_TOKEN`; its `host` is the Wi-Fi or Ethernet
 * address, empty when there is none (use `adb forward` and 127.0.0.1 then). Omitting `scopes`
 * grants every scope. `SET_BIND` takes `LAN` or `ALL`. `SET_PIN` with no `pin` clears it. A bad
 * extra returns result code 1 and a short message; success is result code 0. The same PIN store is
 * writable over `PUT|DELETE /v1/device/pin` (admin scope); neither path returns the PIN.
 */
class ShellCommandReceiver : BroadcastReceiver() {
    @Suppress("TooGenericExceptionCaught") // A bad extra must not crash the app process.
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        val graph =
            try {
                PhoneApiApp.graph
            } catch (e: Exception) {
                pending.resultCode = 1
                pending.resultData = e.message ?: "failed"
                pending.finish()
                return
            }
        graph.scope.launch(graph.ioDispatcher) { deliver(graph, intent, pending) }
    }

    @Suppress("TooGenericExceptionCaught") // A bad extra must not crash the app process.
    private suspend fun deliver(graph: AppGraph, intent: Intent, pending: PendingResult) {
        try {
            handle(graph, intent, pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pending.resultCode = 1
            pending.resultData = e.message ?: "failed"
        } finally {
            pending.finish()
        }
    }

    private suspend fun handle(graph: AppGraph, intent: Intent, pending: PendingResult) {
        when (intent.action) {
            ACTION_CREATE_TOKEN -> createToken(graph, intent, pending)
            ACTION_SET_BIND -> setBind(graph, intent, pending)
            ACTION_SET_PIN -> setPin(graph, intent, pending)
            ACTION_PAIR_ADB -> pairAdb(graph, intent, pending)
        }
    }

    private fun createToken(graph: AppGraph, intent: Intent, pending: PendingResult) {
        val name = intent.getStringExtra("name") ?: "adb"
        when (val scopes = parseScopeList(intent.getStringExtra("scopes"))) {
            is Parse.Error -> fail(pending, scopes.message)
            is Parse.Ok -> {
                val created = graph.tokens.create(name, scopes.value)
                val pins = graph.tls.pins
                val lan = graph.network.lanAddress.value
                val pairing =
                    PairingInfo(
                        host = lan?.hostAddress.orEmpty(),
                        port = graph.settings.current.port,
                        certSha256 = pins.certSha256,
                        spkiSha256 = pins.spkiSha256,
                        token = created.token,
                        name = name,
                    )
                pending.resultCode = 0
                pending.resultData = ApiJson.encodeToString(PairingInfo.serializer(), pairing)
            }
        }
    }

    private fun setBind(graph: AppGraph, intent: Intent, pending: PendingResult) {
        when (val mode = parseBindMode(intent.getStringExtra("mode"))) {
            is Parse.Error -> fail(pending, mode.message)
            is Parse.Ok -> {
                graph.settings.update { it.copy(bindMode = mode.value) }
                pending.resultCode = 0
                pending.resultData = mode.value.name
            }
        }
    }

    private fun setPin(graph: AppGraph, intent: Intent, pending: PendingResult) {
        graph.pins.write(intent.getStringExtra("pin"))
        pending.resultCode = 0
        pending.resultData = if (graph.pins.isSet) "stored" else "cleared"
    }

    private suspend fun pairAdb(graph: AppGraph, intent: Intent, pending: PendingResult) {
        val code =
            when (val parsed = parsePairingCode(intent.getStringExtra("code"))) {
                is Parse.Error -> return fail(pending, parsed.message)
                is Parse.Ok -> parsed.value
            }
        val port =
            when (val parsed = parsePairingPort(intent.getIntExtra("port", 0))) {
                is Parse.Error -> return fail(pending, parsed.message)
                is Parse.Ok -> parsed.value
            }
        pending.resultCode = 0
        pending.resultData = graph.helperSupervisor.pair(code, port)
    }

    private fun fail(pending: PendingResult, message: String) {
        pending.resultCode = 1
        pending.resultData = message
    }

    companion object {
        const val ACTION_CREATE_TOKEN = "net.die.phoneapi.CREATE_TOKEN"
        const val ACTION_SET_BIND = "net.die.phoneapi.SET_BIND"
        const val ACTION_SET_PIN = "net.die.phoneapi.SET_PIN"
        const val ACTION_PAIR_ADB = "net.die.phoneapi.PAIR_ADB"

        private val ACTIONS =
            setOf(ACTION_CREATE_TOKEN, ACTION_SET_BIND, ACTION_SET_PIN, ACTION_PAIR_ADB)

        /** The host command that mints a token over ADB and prints the pairing JSON. */
        fun createTokenCommand(packageName: String): String =
            "adb shell am broadcast -a $ACTION_CREATE_TOKEN " +
                "-n $packageName/${ShellCommandReceiver::class.java.name} --es name laptop"
    }
}

internal sealed interface Parse<out T> {
    data class Ok<T>(val value: T) : Parse<T>

    data class Error(val message: String) : Parse<Nothing>
}

/** Null [raw] grants every scope. Blank text and unknown names are errors. */
internal fun parseScopeList(raw: String?): Parse<Set<Scope>> {
    if (raw == null) return Parse.Ok(Scope.entries.toSet())
    val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return Parse.Error("scopes must name at least one scope")
    val scopes = names.map { name ->
        Scope.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return Parse.Error("unknown scope $name")
    }
    return Parse.Ok(scopes.toSet())
}

/** `LAN` or `ALL`, ignoring case and surrounding whitespace. */
internal fun parseBindMode(raw: String?): Parse<BindMode> {
    val token = raw?.trim().orEmpty()
    if (token.isEmpty()) return Parse.Error("mode is required")
    val mode =
        BindMode.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
            ?: return Parse.Error("unknown bind mode")
    return Parse.Ok(mode)
}

internal fun parsePairingCode(raw: String?): Parse<String> {
    val code = raw?.trim().orEmpty()
    if (!PAIRING_CODE.matches(code)) return Parse.Error("code must be 6 digits")
    return Parse.Ok(code)
}

/** Zero means the port extra was omitted. */
internal fun parsePairingPort(port: Int): Parse<Int?> {
    if (port == 0) return Parse.Ok(null)
    if (port !in 1..65_535) return Parse.Error("port must be 1..65535")
    return Parse.Ok(port)
}

private val PAIRING_CODE = Regex("""\d{6}""")
