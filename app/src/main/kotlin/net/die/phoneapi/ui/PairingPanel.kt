package net.die.phoneapi.ui

import android.app.AlertDialog
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import net.die.phoneapi.AppGraph
import net.die.phoneapi.R
import net.die.phoneapi.server.PairingManager

/**
 * "Pair a computer": the start button, the open window with its address and countdown, and the
 * Allow/Deny dialog. [run] while the activity is started; stopping it closes the window, so pairing
 * is only possible while someone is looking at this screen.
 */
internal class PairingPanel(
    private val activity: ComponentActivity,
    private val graph: AppGraph,
    private val onPaired: () -> Unit,
) {
    private val start = activity.button(R.string.pair_button) { graph.pairing.open() }
    private val offline = activity.body(R.string.pair_no_network)
    private val address = activity.code("", size = 20f)
    private val countdown = TextView(activity)
    private val toggle = activity.button(R.string.pair_show_ip) { flipAddress() }
    private val open =
        activity.vertical().apply {
            addView(address)
            addView(countdown)
            addView(
                activity.row().apply {
                    addView(toggle)
                    addView(activity.button(R.string.pair_stop) { graph.pairing.close() })
                }
            )
        }
    private var dialog: AlertDialog? = null
    private var dialogFor: String? = null

    val views: List<View> = listOf(start, offline, open)

    private data class Model(
        val state: PairingManager.State,
        val url: String?,
        val showsIp: Boolean,
        val canToggle: Boolean,
    )

    suspend fun run() {
        try {
            combine(
                    graph.pairing.state,
                    graph.serverController.endpoint,
                    graph.network.lanAddress,
                    graph.mdns.hostname,
                    graph.settings.settings,
                ) { state, endpoint, lan, hostname, settings ->
                    val ip = lan?.hostAddress
                    val name = hostname?.let { "$it.local" }
                    val showsIp = settings.pairingShowsIp || name == null
                    val host = if (showsIp) ip else name
                    val url =
                        if (endpoint == null || host == null) null
                        else "https://$host:${endpoint.port}/pair"
                    Model(state, url, showsIp, canToggle = ip != null && name != null)
                }
                .collectLatest { model ->
                    render(model)
                    val open = model.state as? PairingManager.State.Open ?: return@collectLatest
                    while (true) {
                        val left = (open.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)
                        val seconds = left / MILLIS_PER_SECOND
                        countdown.text =
                            activity.getString(
                                R.string.pair_countdown,
                                seconds / SECONDS_PER_MINUTE,
                                seconds % SECONDS_PER_MINUTE,
                            )
                        delay(MILLIS_PER_SECOND)
                    }
                }
        } finally {
            graph.pairing.close()
            dismiss()
            keepScreenOn(false)
        }
    }

    private fun render(model: Model) {
        val open = model.state as? PairingManager.State.Open
        val online = model.url != null
        start.shownIf(open == null && online)
        offline.shownIf(!online)
        this.open.shownIf(open != null && online)
        address.text = model.url.orEmpty()
        toggle.shownIf(model.canToggle)
        toggle.setText(if (model.showsIp) R.string.pair_show_name else R.string.pair_show_ip)
        keepScreenOn(open != null)
        val pending = open?.pending
        if (pending == null) dismiss() else ask(pending)
    }

    private fun keepScreenOn(on: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (on) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }

    private fun View.shownIf(shown: Boolean) {
        visibility = if (shown) View.VISIBLE else View.GONE
    }

    private fun ask(pending: PairingManager.Pending) {
        if (dialogFor == pending.id) return
        dismiss()
        dialogFor = pending.id
        dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.approve_title)
                .setMessage(
                    activity.getString(R.string.approve_message, pending.name, pending.address)
                )
                .setPositiveButton(R.string.approve_allow) { _, _ ->
                    graph.pairing.approve(pending.id)
                    Toast.makeText(
                            activity,
                            activity.getString(R.string.paired_toast, pending.name),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    onPaired()
                }
                .setNegativeButton(R.string.approve_deny) { _, _ -> graph.pairing.deny(pending.id) }
                .setCancelable(false)
                .show()
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
        dialogFor = null
    }

    private fun flipAddress() {
        graph.settings.update { it.copy(pairingShowsIp = !it.pairingShowsIp) }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val SECONDS_PER_MINUTE = 60L
    }
}
