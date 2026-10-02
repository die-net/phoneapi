package net.die.phoneapi.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.die.phoneapi.PhoneApiApp

/** Starts the listener after boot only while the HTTPS adapter is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val graph = PhoneApiApp.graphOrNull() ?: return
        if (!graph.settings.current.tlsEnabled) return
        context.startForegroundService(Intent(context, ListenerService::class.java))
    }
}
