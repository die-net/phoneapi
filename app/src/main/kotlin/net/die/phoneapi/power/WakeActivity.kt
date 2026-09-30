package net.die.phoneapi.power

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.core.awaitDeviceState

/**
 * Turns the screen on without the helper: a transparent activity that asks to be shown over the
 * keyguard and to turn the display on, and that finishes itself again straight away. It is excluded
 * from recents and has no history, so it never shows up as a task.
 *
 * With [EXTRA_DISMISS_KEYGUARD] it also asks the keyguard to go away, which is the only supported
 * way to dismiss a non-secure lock screen from an app.
 */
class WakeActivity : Activity() {
    private val graph
        get() = PhoneApiApp.graph

    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        // Our window is showing, so the display is on whether or not the broadcast has landed yet.
        graph.state.refresh()
        val dismiss = intent?.getBooleanExtra(EXTRA_DISMISS_KEYGUARD, false) == true
        if (dismiss) {
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { this.scope = it }
        scope.launch {
            if (dismiss) {
                awaitDeviceState(graph.state, graph.bus, DISMISS_WAIT_MS) { !it.keyguard.locked }
            }
            delay(LINGER_MS)
            finish()
        }
    }

    override fun onPause() {
        scope?.cancel()
        scope = null
        super.onPause()
    }

    companion object {
        internal const val EXTRA_DISMISS_KEYGUARD = "net.die.phoneapi.extra.DISMISS_KEYGUARD"

        /** Grace period before finishing, so the screen-on and dismiss actually take effect. */
        private const val LINGER_MS = 250L
        private const val DISMISS_WAIT_MS = 2_000L

        fun start(context: Context, dismissKeyguard: Boolean = false) {
            val intent =
                Intent(context, WakeActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_NO_HISTORY
                    )
                    .putExtra(EXTRA_DISMISS_KEYGUARD, dismissKeyguard)
            context.startActivity(intent)
        }
    }
}
