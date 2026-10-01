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
    val capabilities: Capabilities,
)

/**
 * Runtime feature flags. JSON keys stay dotted (`ui.snapshot`) so existing clients keep working. A
 * flag is true only when this build actually implements that path.
 */
@Serializable
public data class Capabilities(
    @SerialName("ui.snapshot") val uiSnapshot: Boolean,
    @SerialName("input.a11y") val inputA11y: Boolean,
    @SerialName("input.inject") val inputInject: Boolean,
    @SerialName("text.ime") val textIme: Boolean,
    @SerialName("text.keyevent") val textKeyevent: Boolean,
    @SerialName("screenshot.a11y") val screenshotA11y: Boolean,
    @SerialName("screenshot.helper") val screenshotHelper: Boolean,
    @SerialName("ui.stableIds") val uiStableIds: Boolean,
    @SerialName("apps.manage") val appsManage: Boolean,
    @SerialName("logcat.all") val logcatAll: Boolean,
    @SerialName("browser.cdp") val browserCdp: Boolean,
    @SerialName("stream.video.projection") val streamVideoProjection: Boolean,
    @SerialName("stream.video.mirror") val streamVideoMirror: Boolean,
    @SerialName("stream.audio.playbackCapture") val streamAudioPlaybackCapture: Boolean,
    @SerialName("stream.audio.submix") val streamAudioSubmix: Boolean,
    @SerialName("encoder.lowLatency") val encoderLowLatency: Boolean,
    @SerialName("adb.wireless") val adbWireless: Boolean,
    @SerialName("settings.secure") val settingsSecure: Boolean,
)

/** Body of `PUT /v1/device/pin`. The value is stored and never returned. */
@Serializable public data class SetPinRequest(val pin: String)

/** Whether a lock-screen PIN is stored. The PIN itself is never included. */
@Serializable public data class PinStatus(val stored: Boolean)
