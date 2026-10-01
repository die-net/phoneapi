package net.die.phoneapi.helperclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch
import net.die.phoneapi.PhoneApiApp

/**
 * Notification actions for the helper. "Start helper" retries the automatic path. "Enter code"
 * carries the wireless-debugging code typed into [WirelessPairing]'s notification.
 */
class HelperStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val graph = PhoneApiApp.graph
        val code =
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(EXTRA_CODE)
                ?.toString()
                ?.trim()
                .orEmpty()
        if (code.isEmpty()) {
            graph.helperSupervisor.nudge()
            return
        }
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.wirelessPairing.submit(code)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
