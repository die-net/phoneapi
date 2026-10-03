package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.die.phoneapi.model.schema.Doc
import net.die.phoneapi.model.schema.LenientEnumSerializer

@Serializable
public data class TapRequest(
    @Doc("Screen x in pixels. Omit when selector is set.") val x: Float? = null,
    @Doc("Screen y in pixels. Omit when selector is set.") val y: Float? = null,
    @Doc("Tap a node instead of coordinates. The point is jittered inside its visible bounds.")
    val selector: NodeSelector? = null,
    @Doc("How many times to tap. Defaults to 1.") val count: Int = 1,
    @Doc("Hold the pointer down for this many milliseconds.") val holdMs: Long? = null,
    @Doc("Jitter the point and timing. Defaults to true.") val humanize: Boolean = true,
    @Doc("Tap even when the node is covered by the keyboard or another window.")
    val force: Boolean = false,
    @Doc("Wake and unlock the device first. Defaults to true.") val autoWake: Boolean = true,
)

/** Direction of a swipe that does not name [SwipeRequest.from] and [SwipeRequest.to]. */
@Serializable(with = SwipeDirectionSerializer::class)
public enum class SwipeDirection {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

public object SwipeDirectionSerializer :
    LenientEnumSerializer<SwipeDirection>(
        serialName = "net.die.phoneapi.model.SwipeDirection",
        wires = listOf("up", "down", "left", "right"),
        values = { enumValues<SwipeDirection>() },
    )

@Serializable
public data class SwipeRequest(
    @Doc("Start point. Pass both from and to, or a direction.") val from: Point? = null,
    @Doc("End point. Pass both from and to, or a direction.") val to: Point? = null,
    /**
     * Alternative to from/to: swipe inside a node in a direction (`up`, `down`, `left`, `right`).
     */
    @Doc("Swipe inside this node. Omit to swipe across the screen.")
    val selector: NodeSelector? = null,
    @Doc("up, down, left, or right. Use instead of from and to.")
    val direction: SwipeDirection? = null,
    @Doc("Fraction of the node or screen to travel when using direction.")
    val distance: Float = 0.6f,
    @Doc("How long the swipe takes, in milliseconds.") val durationMs: Long = 300,
    @Doc("Keep velocity at release instead of stopping before the pointer lifts.")
    val fling: Boolean = true,
    @Doc("Jitter the path and timing. Defaults to true.") val humanize: Boolean = true,
    @Doc("Wake and unlock the device first. Defaults to true.") val autoWake: Boolean = true,
)

@Serializable public data class TimedPoint(val x: Float, val y: Float, val tMs: Long)

@Serializable
public data class GestureRequest(
    /** One path per pointer; times are relative to the gesture start. */
    val pointers: List<List<TimedPoint>>,
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
    /** Helper KeyEvent injection (looks like a hardware keyboard). */
    @SerialName("keyevent") KEYEVENT,
    /** ACTION_SET_TEXT; no input events at all. */
    @SerialName("setText") SET_TEXT,
}

@Serializable
public data class TextRequest(
    @Doc("Characters to type.") val text: String,
    @Doc("Focus this node first. Omit to type into the focused field.")
    val selector: NodeSelector? = null,
    @Doc(
        "auto, keyboard, keyevent, or setText. auto taps the on-screen keyboard, then uses setText."
    )
    val mode: TextMode = TextMode.AUTO,
    @Doc("Clear the field before typing.") val clear: Boolean = false,
    @Doc("Press the IME action (Enter, Go, or Search) afterwards.") val submit: Boolean = false,
    @Doc("Minimum delay between characters, in milliseconds.") val minDelayMs: Long = 40,
    @Doc("Maximum delay between characters, in milliseconds.") val maxDelayMs: Long = 140,
    @Doc("Wake and unlock the device first. Defaults to true.") val autoWake: Boolean = true,
)

@Serializable
public data class ImeShowRequest(
    @Doc(
        "Editable node to focus, the same selector as tap, including ref. Omit to use the field that already has input focus."
    )
    val selector: NodeSelector? = null,
    @Doc("Wake and unlock the device first. Defaults to true.") val autoWake: Boolean = true,
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
