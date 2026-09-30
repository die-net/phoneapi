package net.die.phoneapi.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import net.die.phoneapi.core.ApiException

/** Something that can deliver [KeyEvent]s. */
interface KeyBackend {
    val name: String

    val isAvailable: Boolean

    suspend fun send(event: KeyEvent): Boolean
}

/**
 * Key events through the accessibility service's own input method (Android 13+), delivered to the
 * focused editor only.
 */
class ImeKeyBackend(private val service: StateFlow<AccessibilityService?>) : KeyBackend {
    override val name = "ime"

    override val isAvailable: Boolean
        get() = service.value?.editorConnection() != null

    override suspend fun send(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val connection = service.value?.editorConnection() ?: return false
        connection.sendKeyEvent(event)
        return true
    }
}

/**
 * Picks where key events go. Editing keys go to the focused editor through [ime]; everything else
 * needs the helper, which registers itself in [helper].
 */
class KeyBackends(private val ime: KeyBackend) {
    @Volatile var helper: KeyBackend? = null

    fun forKey(keyCode: Int): KeyBackend {
        val injected = helper?.takeIf { it.isAvailable }
        if (keyCode !in EDITOR_KEYS) return injected ?: throw ApiException.helperUnavailable()
        return ime.takeIf { it.isAvailable }
            ?: injected
            ?: throw ApiException(
                409,
                "no_editor",
                "No text field is focused (editing keys go to the focused editor without the helper)",
            )
    }

    /** Sends a down/up pair, holding it past the long-press timeout if [longPress]. */
    suspend fun press(keyCode: Int, metaState: Int, longPress: Boolean): String {
        val backend = forKey(keyCode)
        val down = SystemClock.uptimeMillis()
        var ok = backend.send(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        if (longPress) {
            delay(ViewConfiguration.getLongPressTimeout().toLong())
            val now = SystemClock.uptimeMillis()
            ok =
                ok &&
                    backend.send(
                        KeyEvent.changeFlags(
                            KeyEvent(down, now, KeyEvent.ACTION_DOWN, keyCode, 1, metaState),
                            KeyEvent.FLAG_LONG_PRESS,
                        )
                    )
        }
        val up = SystemClock.uptimeMillis()
        ok = ok && backend.send(KeyEvent(down, up, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        if (!ok)
            throw ApiException(
                409,
                "key_failed",
                "The ${backend.name} backend did not deliver the key",
            )
        return backend.name
    }

    private companion object {
        val EDITOR_KEYS: Set<Int> = buildSet {
            addAll(
                listOf(
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_FORWARD_DEL,
                    KeyEvent.KEYCODE_TAB,
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MOVE_HOME,
                    KeyEvent.KEYCODE_MOVE_END,
                    KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_PAGE_DOWN,
                )
            )
            addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
            addAll(KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)
        }
    }
}

/**
 * The accessibility input method's connection to the focused editor (Android 13+), if one is bound.
 * Callers still need an SDK check before using it.
 */
internal fun AccessibilityService.editorConnection(): InputMethod.AccessibilityInputConnection? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        inputMethod?.takeIf { it.currentInputStarted }?.currentInputConnection
    } else {
        null
    }
