package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class InputBackend {
    /** Helper injection when available, otherwise accessibility gestures. */
    @SerialName("auto") AUTO,
    /** InputManager injection from the shell-UID helper, stamped with the real touchscreen. */
    @SerialName("inject") INJECT,
    /** AccessibilityService.dispatchGesture. */
    @SerialName("a11y") A11Y,
}

@Serializable
public data class TapRequest(
    val x: Float? = null,
    val y: Float? = null,
    /** Tap a node instead of coordinates; the point is jittered inside its visible bounds. */
    val selector: NodeSelector? = null,
    val count: Int = 1,
    val holdMs: Long? = null,
    val backend: InputBackend = InputBackend.AUTO,
    val humanize: Boolean = true,
    /** Allow tapping nodes covered by the IME or other windows. */
    val force: Boolean = false,
    val autoWake: Boolean = true,
)

@Serializable
public data class SwipeRequest(
    val from: Point? = null,
    val to: Point? = null,
    /**
     * Alternative to from/to: swipe inside a node in a direction (`up`, `down`, `left`, `right`).
     */
    val selector: NodeSelector? = null,
    val direction: String? = null,
    /** Fraction of the node (or screen) to travel when using [direction]. */
    val distance: Float = 0.6f,
    val durationMs: Long = 300,
    /** End with a fling (keep velocity at release) rather than settling before lift. */
    val fling: Boolean = true,
    val backend: InputBackend = InputBackend.AUTO,
    val humanize: Boolean = true,
    val autoWake: Boolean = true,
)

@Serializable public data class TimedPoint(val x: Float, val y: Float, val tMs: Long)

@Serializable
public data class GestureRequest(
    /** One path per pointer; times are relative to the gesture start. */
    val pointers: List<List<TimedPoint>>,
    val backend: InputBackend = InputBackend.AUTO,
    val autoWake: Boolean = true,
)

@Serializable
public data class KeyRequest(
    /**
     * `BACK`, `HOME`, `RECENTS`, `ENTER`, `DEL`, `TAB`, `NOTIFICATIONS`, `QUICK_SETTINGS`, `POWER`,
     * or any `KEYCODE_*` name.
     */
    val key: String,
    val longPress: Boolean = false,
    val metaState: Int = 0,
    val autoWake: Boolean = true,
)

@Serializable
public enum class TextMode {
    @SerialName("auto") AUTO,
    /** Tap the on-screen keyboard's keys with real touches. */
    @SerialName("keyboard") KEYBOARD,
    /** Accessibility InputMethod (Android 13+): composing text then commit. */
    @SerialName("ime") IME,
    /** Helper KeyEvent injection (looks like a hardware keyboard). */
    @SerialName("keyevent") KEYEVENT,
    /** ACTION_SET_TEXT; no input events at all. */
    @SerialName("setText") SET_TEXT,
}

@Serializable
public data class TextRequest(
    val text: String,
    /** Focus (tap) this node first. Otherwise types into the currently focused field. */
    val selector: NodeSelector? = null,
    val mode: TextMode = TextMode.AUTO,
    val clear: Boolean = false,
    /** Press the IME action (Enter / Go / Search) afterwards. */
    val submit: Boolean = false,
    /** Per-character delay range for realistic cadence. */
    val minDelayMs: Long = 40,
    val maxDelayMs: Long = 140,
    val autoWake: Boolean = true,
)

@Serializable
public data class ActionResult(
    val ok: Boolean,
    val backend: String? = null,
    val woke: Boolean = false,
    val message: String? = null,
    /** The point(s) actually touched, after humanization. */
    val points: List<Point> = emptyList(),
    val seq: Long? = null,
)

@Serializable
public data class LaunchRequest(
    /** Clear the task and start fresh. */
    val fresh: Boolean = false,
    val activity: String? = null,
    val wait: Boolean = true,
)

@Serializable
public data class IntentRequest(
    val action: String = "android.intent.action.VIEW",
    val data: String? = null,
    @SerialName("package") val packageName: String? = null,
    val component: String? = null,
    val categories: List<String> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
    val flags: Int? = null,
)

@Serializable
public data class AppInfo(
    @SerialName("package") val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long,
    val system: Boolean,
    val enabled: Boolean,
    val launchable: Boolean,
)

@Serializable
public data class UnlockRequest(
    /** Use the PIN stored in the device config if the keyguard is secure. */
    val useStoredPin: Boolean = true
)
