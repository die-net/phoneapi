package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class ApiError(
    val error: String,
    val message: String? = null,
    val state: DeviceStateSummary? = null,
)

@Serializable public data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable public data class Point(val x: Float, val y: Float)

@Serializable
public enum class ScreenState {
    @SerialName("on") ON,
    @SerialName("off") OFF,
    @SerialName("doze") DOZE,
}

@Serializable public data class KeyguardState(val locked: Boolean, val secure: Boolean)

@Serializable
public data class ImeState(
    val visible: Boolean,
    val bounds: Rect? = null,
    @SerialName("package") val packageName: String? = null,
)

@Serializable
public enum class HelperStatus {
    @SerialName("running") RUNNING,
    @SerialName("starting") STARTING,
    @SerialName("needs_pairing") NEEDS_PAIRING,
    @SerialName("needs_usb") NEEDS_USB,
    @SerialName("stopped") STOPPED,
}

@Serializable
public data class DeviceStateSummary(
    val screen: ScreenState,
    val keyguard: KeyguardState,
    val ime: ImeState,
    val foregroundPackage: String? = null,
)

@Serializable
public data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotation: Int,
    val refreshRate: Float,
)

@Serializable
public data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val appVersion: String,
    val display: DisplayInfo,
    val state: DeviceStateSummary,
    val helper: HelperStatus,
    val helperRecoveredAtMs: Long? = null,
    val accessibilityConnected: Boolean,
    val capabilities: Map<String, Boolean>,
)

/** Capability keys reported in [DeviceInfo.capabilities]. */
public object Capabilities {
    public const val UI_SNAPSHOT: String = "ui.snapshot"
    public const val INPUT_A11Y: String = "input.a11y"
    public const val INPUT_INJECT: String = "input.inject"
    public const val TEXT_IME: String = "text.ime"
    public const val TEXT_KEYEVENT: String = "text.keyevent"
    public const val SCREENSHOT_A11Y: String = "screenshot.a11y"
    public const val SCREENSHOT_HELPER: String = "screenshot.helper"
    public const val STABLE_NODE_IDS: String = "ui.stableIds"
    public const val APPS_MANAGE: String = "apps.manage"
    public const val LOGCAT_ALL: String = "logcat.all"
    public const val BROWSER_CDP: String = "browser.cdp"
    public const val STREAM_VIDEO_PROJECTION: String = "stream.video.projection"
    public const val STREAM_VIDEO_MIRROR: String = "stream.video.mirror"
    public const val STREAM_AUDIO_PLAYBACK_CAPTURE: String = "stream.audio.playbackCapture"
    public const val STREAM_AUDIO_SUBMIX: String = "stream.audio.submix"
    public const val ENCODER_LOW_LATENCY: String = "encoder.lowLatency"
    public const val WIRELESS_DEBUGGING: String = "adb.wireless"
    public const val SECURE_SETTINGS: String = "settings.secure"
}

/** Body of `PUT /v1/device/pin`. The value is stored and never returned. */
@Serializable public data class SetPinRequest(val pin: String)

/** Whether a lock-screen PIN is stored. The PIN itself is never included. */
@Serializable public data class PinStatus(val stored: Boolean)
