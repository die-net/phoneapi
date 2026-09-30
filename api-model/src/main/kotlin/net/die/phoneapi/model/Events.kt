package net.die.phoneapi.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Streamed over `WS /v1/events` as one JSON object per text frame. */
@Serializable
public data class Event(
    /**
     * e.g. `ui.changed`, `window.changed`, `ime.shown`, `ime.hidden`, `screen.on`, `screen.off`,
     * `user.present`, `toast`, `logcat`, `helper.status`.
     */
    val type: String,
    val timestampMs: Long,
    val seq: Long,
    val data: JsonObject = JsonObject(emptyMap()),
)

public object EventTypes {
    public const val UI_CHANGED: String = "ui.changed"
    public const val WINDOW_CHANGED: String = "window.changed"
    public const val IME_SHOWN: String = "ime.shown"
    public const val IME_HIDDEN: String = "ime.hidden"
    public const val SCREEN_ON: String = "screen.on"
    public const val SCREEN_OFF: String = "screen.off"
    public const val USER_PRESENT: String = "user.present"
    public const val TOAST: String = "toast"
    public const val LOGCAT: String = "logcat"
    public const val HELPER_STATUS: String = "helper.status"
    public const val A11Y_STATUS: String = "a11y.status"
}
