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
)

@Serializable public data class OpenTabRequest(val url: String)

@Serializable public data class NavigateRequest(val url: String)

/** How a browser tap is delivered. [TOUCH] is the default. */
@Serializable
public enum class BrowserInput {
    /** Bring the tab forward and inject a touchscreen event. */
    @SerialName("touch") TOUCH,
    /** Dispatch the tap on that target. The tab can stay in the background. */
    @SerialName("cdp") CDP,
}

/**
 * Tap a browser target. Pass a snapshot [ref] (the id in square brackets) or a CSS [selector], not
 * both.
 */
@Serializable
public data class BrowserTapRequest(
    val ref: String? = null,
    val selector: String? = null,
    val humanize: Boolean = true,
    val autoWake: Boolean = true,
    @Doc(
        "touch brings the tab forward and injects a touchscreen event. cdp sends the tap to that target, including a background tab. Defaults to touch."
    )
    val input: BrowserInput = BrowserInput.TOUCH,
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
