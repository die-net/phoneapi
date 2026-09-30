package net.die.phoneapi.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.HelperStatus

/** Builds [DeviceInfo], including the runtime capability map. */
class DeviceInfoProvider(
    private val context: Context,
    private val state: DeviceStateTracker,
    private val isHelperRunning: () -> Boolean,
    private val isA11yConnected: () -> Boolean,
    private val helperStatus: () -> HelperStatus,
    private val helperRecoveredAtMs: () -> Long?,
) {
    fun info(): DeviceInfo =
        DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            appVersion = appVersion(),
            display = display(),
            state = state.current,
            helper = helperStatus(),
            helperRecoveredAtMs = helperRecoveredAtMs(),
            accessibilityConnected = isA11yConnected(),
            capabilities = capabilities(),
        )

    fun capabilities(): Map<String, Boolean> {
        val sdk = Build.VERSION.SDK_INT
        val helper = isHelperRunning()
        val a11y = isA11yConnected()
        val secureSettings =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        return mapOf(
            Capabilities.UI_SNAPSHOT to a11y,
            Capabilities.INPUT_A11Y to a11y,
            Capabilities.INPUT_INJECT to helper,
            Capabilities.TEXT_IME to (a11y && sdk >= Build.VERSION_CODES.TIRAMISU),
            Capabilities.TEXT_KEYEVENT to helper,
            Capabilities.SCREENSHOT_A11Y to (a11y && sdk >= Build.VERSION_CODES.R),
            Capabilities.SCREENSHOT_HELPER to helper,
            Capabilities.STABLE_NODE_IDS to (sdk >= Build.VERSION_CODES.TIRAMISU),
            Capabilities.APPS_MANAGE to helper,
            Capabilities.LOGCAT_ALL to helper,
            Capabilities.BROWSER_CDP to helper,
            Capabilities.STREAM_VIDEO_PROJECTION to true,
            Capabilities.STREAM_VIDEO_MIRROR to helper,
            Capabilities.STREAM_AUDIO_PLAYBACK_CAPTURE to true,
            Capabilities.STREAM_AUDIO_SUBMIX to (helper && sdk >= Build.VERSION_CODES.R),
            Capabilities.ENCODER_LOW_LATENCY to (sdk >= Build.VERSION_CODES.R),
            Capabilities.WIRELESS_DEBUGGING to (sdk >= Build.VERSION_CODES.R),
            Capabilities.SECURE_SETTINGS to secureSettings,
        )
    }

    fun display(): DisplayInfo {
        val display =
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = context.resources.displayMetrics
        val mode = display.mode
        val rotated = display.rotation % 2 == 1
        val (w, h) = mode.physicalWidth to mode.physicalHeight
        return DisplayInfo(
            widthPx = if (rotated) h else w,
            heightPx = if (rotated) w else h,
            densityDpi = metrics.densityDpi,
            rotation = display.rotation,
            refreshRate = mode.refreshRate,
        )
    }

    private fun appVersion(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
}
