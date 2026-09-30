package net.die.phoneapi.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.model.EventTypes

/**
 * Hosts the API server for as long as the system keeps it bound (including across reboots), and
 * feeds UI and window events to the rest of the app.
 */
class PhoneAccessibilityService : AccessibilityService() {
    private val graph
        get() = PhoneApiApp.graph

    override fun onServiceConnected() {
        super.onServiceConnected()
        graph.a11y.value = this
        graph.bus.emit(EventTypes.A11Y_STATUS, buildJsonObject { put("connected", true) })
        graph.serverController.acquire(HOLDER)
        graph.uiTracker.onConnected(this)
        graph.helperSupervisor.nudge()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        graph.uiTracker.onEvent(this, event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (graph.a11y.value !== this) return
        graph.a11y.value = null
        graph.bus.emit(EventTypes.A11Y_STATUS, buildJsonObject { put("connected", false) })
        graph.serverController.release(HOLDER)
    }

    private companion object {
        const val HOLDER = "a11y"
    }
}
