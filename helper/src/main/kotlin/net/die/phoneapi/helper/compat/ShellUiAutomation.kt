package net.die.phoneapi.helper.compat

import android.app.UiAutomation
import android.os.Looper
import java.lang.reflect.InvocationTargetException

/**
 * Connects a shell [UiAutomation]. `UiAutomationConnection` and `connect` are hidden, so the
 * session is built through reflection.
 */
internal object ShellUiAutomation {
    fun connect(looper: Looper, flags: Int): UiAutomation {
        val connection =
            Class.forName("android.app.UiAutomationConnection")
                .getDeclaredConstructor()
                .newInstance()
        val type = Class.forName("android.app.IUiAutomationConnection")
        val ui =
            UiAutomation::class
                .java
                .getConstructor(Looper::class.java, type)
                .newInstance(looper, connection) as UiAutomation
        try {
            UiAutomation::class
                .java
                .getMethod("connect", Int::class.javaPrimitiveType)
                .invoke(ui, flags)
        } catch (e: InvocationTargetException) {
            throw IllegalStateException(e.cause?.message ?: "UiAutomation connect failed", e)
        }
        return ui
    }

    fun disconnect(ui: UiAutomation) {
        UiAutomation::class.java.getMethod("disconnect").invoke(ui)
    }
}
