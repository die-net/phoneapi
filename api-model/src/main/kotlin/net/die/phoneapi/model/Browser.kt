package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import net.die.phoneapi.model.schema.Doc

/** One Chrome tab or debuggable WebView, addressed by [id] on later calls. */
@Serializable
public data class BrowserTarget(
    val id: String,
    val type: String,
    val title: String? = null,
    val url: String? = null,
    /** Abstract-namespace socket this target was listed from. */
    val socket: String,
    val pid: Int? = null,
    @SerialName("package") val packageName: String? = null,
    @Doc(
        "Set when a tab is opened. True when Chrome was on screen, so it switched to the new tab and the call took the screen. False when the tab opened in the background."
    )
    val tookScreen: Boolean? = null,
)

@Serializable public data class OpenTabRequest(val url: String)

@Serializable public data class NavigateRequest(val url: String)

/** How browser input is delivered. [TOUCH] is the default. */
@Serializable
public enum class BrowserInput {
    /** Bring the tab forward and inject a hardware event. */
    @SerialName("touch") TOUCH,
    /**
     * Touch events over DevTools, on that target. The tab can stay in the background, but Chrome
     * holds touch input until it is on screen, so this needs Chrome in front with the screen on.
     */
    @SerialName("cdp") CDP,
    /**
     * A mouse click over DevTools. Reaches a background tab with Chrome hidden and the screen off,
     * but the page sees a mouse rather than a finger. Taps only.
     */
    @SerialName("cdp-mouse") CDP_MOUSE,
}

public const val BROWSER_INPUT_DOC: String =
    "touch brings the tab to the front and uses the screen. cdp sends touch events to that target, even a background tab, but only while Chrome is on screen. cdp-mouse taps with a mouse click, which works with Chrome hidden and the screen off, but the page sees a mouse, not a finger. Defaults to touch."

public const val BROWSER_WAKE_DOC: String =
    "Turn the screen on first when input is touch. cdp and cdp-mouse never wake it. Defaults to true."

/**
 * Tap a browser target. Pass a snapshot [ref] (the id in square brackets) or a CSS [selector], not
 * both.
 */
@Serializable
public data class BrowserTapRequest(
    val ref: String? = null,
    val selector: String? = null,
    @Doc(
        "Jitter the point, timing, and finger contact. Defaults to true. A CDP tap matches a hardware tap."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.TOUCH,
)

/**
 * Swipe a browser target. [from] and [to] are CSS viewport pixels. Pass those, or a [direction]
 * across the page or inside a [ref] or CSS [selector].
 */
@Serializable
public data class BrowserSwipeRequest(
    @Doc("Start, in CSS viewport pixels. Pass both from and to, or a direction.")
    val from: Point? = null,
    @Doc("End, in CSS viewport pixels. Pass both from and to, or a direction.")
    val to: Point? = null,
    val ref: String? = null,
    val selector: String? = null,
    @Doc("up, down, left, or right. Use instead of from and to.")
    val direction: SwipeDirection? = null,
    @Doc("Fraction of the page or element to travel when using direction.")
    val distance: Float = 0.6f,
    @Doc("How long the swipe takes, in milliseconds.") val durationMs: Long = 300,
    @Doc("Keep velocity at release instead of stopping before the pointer lifts.")
    val fling: Boolean = true,
    @Doc(
        "Jitter the path, timing, and finger contact. Defaults to true. A CDP swipe matches a hardware swipe."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.TOUCH,
)

/**
 * One gesture on a browser target. Each pointer path is in CSS viewport pixels, with times relative
 * to the gesture start.
 */
@Serializable
public data class BrowserGestureRequest(
    val pointers: List<List<TimedPoint>>,
    @Doc(
        "Jitter the path and vary the finger contact. Defaults to true. A CDP gesture matches a hardware gesture."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.TOUCH,
)

/** A key aimed at a browser target. Names match [KeyRequest]. */
@Serializable
public data class BrowserKeyRequest(
    val key: String,
    val longPress: Boolean = false,
    val metaState: Int = 0,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.TOUCH,
)

/**
 * Type into a browser target. [ref] or CSS [selector] focuses a field first. [mode] applies to
 * [BrowserInput.TOUCH]; [BrowserInput.CDP] inserts the text.
 */
@Serializable
public data class BrowserTextRequest(
    @Doc("Characters to type.") val text: String,
    val ref: String? = null,
    val selector: String? = null,
    @Doc("auto, keyboard, keyevent, or setText. Used when input is touch.")
    val mode: TextMode = TextMode.AUTO,
    @Doc("Clear the field before typing.") val clear: Boolean = false,
    @Doc("Press Enter afterwards.") val submit: Boolean = false,
    val minDelayMs: Long = 40,
    val maxDelayMs: Long = 140,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.TOUCH,
)

/** Evaluate [expression] in an isolated world so the page's JavaScript is untouched. */
@Serializable
public data class EvalRequest(val expression: String, val awaitPromise: Boolean = false)

@Serializable
public data class EvalResult(
    val type: String? = null,
    val value: JsonElement? = null,
    val exception: String? = null,
)

/**
 * Collect console and browser log events for [timeoutMs]. Entries from before the call are not
 * replayed.
 */
@Serializable public data class ConsoleRequest(val timeoutMs: Long = 1_000)

@Serializable public data class ConsoleEntry(val level: String, val text: String)

@Serializable public data class ConsoleResult(val entries: List<ConsoleEntry> = emptyList())

/** Compact accessibility tree of a browser target. */
@Serializable
public data class BrowserSnapshot(
    val id: String,
    val url: String? = null,
    val title: String? = null,
    val compact: String,
)
