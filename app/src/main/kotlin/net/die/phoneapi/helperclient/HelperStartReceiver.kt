package net.die.phoneapi.helperclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch
import net.die.phoneapi.PhoneApiApp

/**
 * Notification actions for the helper. "Start helper" retries the automatic path. The pairing
 * action carries the 6-digit code from the notification, so the system pairing dialog can stay
 * open; leaving that dialog closes its port.
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
                val message = graph.helperSupervisor.pair(code, port = null)
                Log.i(TAG, message)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
        private const val TAG = "PhoneApiHelper"
    }
}
