package net.die.phoneapi.power

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.SettingsStore
import net.die.phoneapi.core.awaitDeviceState
import net.die.phoneapi.helperclient.HelperShell
import net.die.phoneapi.helperclient.failureMessage
import net.die.phoneapi.input.SwipeSpec
import net.die.phoneapi.input.TouchInput
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.PinpadKeys
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.ScreenState
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.tree.TreeSession

/**
 * Screen and keyguard control. Waking prefers the helper's `KEYCODE_WAKEUP` and falls back to
 * [WakeActivity]; unlocking dismisses a non-secure keyguard and types a stored PIN on a secure one.
 *
 * Wake and unlock run one at a time, so concurrent requests (an agent firing several calls at once)
 * queue behind a single attempt instead of fighting each other on the lock screen.
 */
class PowerServiceImpl(
    private val context: Context,
    settings: SettingsStore,
    private val seq: StateFlow<Long>,
    private val tree: TreeSession,
    private val state: DeviceStateTracker,
    private val bus: EventBus,
    private val shell: HelperShell,
    private val pins: PinStore,
    private val touch: TouchInput,
    private val display: () -> DisplayInfo,
    private val io: CoroutineDispatcher,
    private val random: Random = Random.Default,
) : PowerService {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val lease = AwakeLease(context, settings)
    private val mutex = Mutex()

    /** What an unlock attempt did, and why it failed if it did. */
    private data class Attempt(
        val ok: Boolean,
        val backend: String? = null,
        val points: List<Point> = emptyList(),
        val message: String? = null,
    )

    override suspend fun wake(): ActionResult = mutex.withLock {
        lease.extend()
        if (screenOn()) {
            return ActionResult(ok = true, message = "The screen was already on")
        }
        val backend = turnScreenOn()
        if (!awaitScreenOn()) throw wakeFailed()
        ActionResult(ok = true, backend = backend, woke = true)
    }

    override suspend fun unlock(request: UnlockRequest): ActionResult = mutex.withLock {
        lease.extend()
        var woke = false
        if (!screenOn()) {
            turnScreenOn()
            if (!awaitScreenOn()) throw wakeFailed()
            woke = true
        }
        if (!keyguard.isKeyguardLocked) {
            return ActionResult(ok = true, woke = woke, message = "The device was not locked")
        }
        val attempt = dismissKeyguard(request.useStoredPin)
        if (!attempt.ok) throw needsUser(attempt.message)
        ActionResult(
            ok = true,
            backend = attempt.backend,
            woke = woke,
            points = attempt.points,
            seq = seq.value,
        )
    }

    override suspend fun lock(): ActionResult = mutex.withLock {
        // Otherwise our own lease would light the screen straight back up.
        lease.release()
        if (!tree.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            throw ApiException(
                409,
                "lock_failed",
                "The helper could not lock the screen. Its automation connection has to be up.",
            )
        }
        val locked =
            awaitDeviceState(state, bus, LOCK_WAIT_MS) {
                it.screen != ScreenState.ON || it.keyguard.locked
            }
        ActionResult(
            ok = locked,
            backend = "global",
            message = if (locked) null else "The screen is still on",
        )
    }

    override suspend fun prepareForAction(autoWake: Boolean, allowLocked: Boolean): Boolean {
        lease.extend()
        val summary = state.refresh()
        val needsWake = autoWake && summary.screen != ScreenState.ON
        val needsUnlock = !allowLocked && summary.keyguard.locked
        if (!needsWake && !needsUnlock) return false
        return mutex.withLock { prepare(autoWake, allowLocked) }
    }

    private suspend fun prepare(autoWake: Boolean, allowLocked: Boolean): Boolean {
        var changed = false
        if (autoWake && !screenOn()) {
            turnScreenOn()
            changed = awaitScreenOn()
            // Acting on a device that stayed dark is still better than refusing outright, so this
            // only gives up when the keyguard check below finds the device locked.
            if (!changed) Log.w(TAG, "Auto-wake did not turn the screen on")
        }
        if (!allowLocked && keyguard.isKeyguardLocked) {
            val attempt =
                if (autoWake) dismissKeyguard(useStoredPin = true)
                else
                    Attempt(ok = false, message = "autoWake is off, so the lock screen was left up")
            if (!attempt.ok) throw deviceLocked(attempt.message)
            changed = true
        }
        return changed
    }

    private fun screenOn(): Boolean = state.refresh().screen == ScreenState.ON

    /** Starts a wake attempt and returns the backend that ran it. */
    private suspend fun turnScreenOn(): String {
        if (shell.isAvailable) {
            val result = shell.exec(listOf("input", "keyevent", "KEYCODE_WAKEUP"))
            if (result.ok) return "keyevent"
            Log.w(TAG, "KEYCODE_WAKEUP failed: ${result.failureMessage()}")
        }
        WakeActivity.start(context)
        return "activity"
    }

    private suspend fun awaitScreenOn(): Boolean =
        awaitDeviceState(state, bus, WAKE_WAIT_MS) { it.screen == ScreenState.ON }

    private suspend fun awaitUnlocked(): Boolean =
        awaitDeviceState(state, bus, UNLOCK_WAIT_MS) { !it.keyguard.locked }

    private suspend fun dismissKeyguard(useStoredPin: Boolean): Attempt {
        if (!keyguard.isDeviceSecure) return dismissInsecure()
        if (!useStoredPin) {
            return Attempt(
                ok = false,
                message = "The lock screen is secure and useStoredPin is off",
            )
        }
        val pin = pins.read() ?: return Attempt(ok = false, message = NO_PIN)
        return enterPin(pin)
    }

    /** A lock screen with no credential only has to be swiped (or told) out of the way. */
    private suspend fun dismissInsecure(): Attempt {
        if (shell.isAvailable) {
            val result = shell.exec(listOf("wm", "dismiss-keyguard"))
            if (result.ok && awaitUnlocked())
                return Attempt(ok = true, backend = "dismiss-keyguard")
        }
        WakeActivity.start(context, dismissKeyguard = true)
        if (awaitUnlocked()) return Attempt(ok = true, backend = "requestDismissKeyguard")
        val points = swipeUp()
        return if (awaitUnlocked()) {
            Attempt(ok = true, backend = "swipe", points = points)
        } else {
            Attempt(
                ok = false,
                backend = "swipe",
                points = points,
                message = "The lock screen would not go away",
            )
        }
    }

    private suspend fun enterPin(pin: String): Attempt {
        val points = ArrayList<Point>()
        var keypad = scanKeypad()
        if (!keypad.covers(pin)) {
            // On most devices the keypad sits behind the lock screen until it is swiped away.
            points += revealBouncer()
            keypad = scanKeypad()
        }
        if (!keypad.covers(pin)) {
            return Attempt(ok = false, points = points, message = NO_KEYPAD)
        }
        for ((i, digit) in pin.withIndex()) {
            if (i > 0) delay(random.nextLong(MIN_DIGIT_GAP_MS, MAX_DIGIT_GAP_MS))
            val outcome = touch.tap(checkNotNull(keypad.digits[digit]))
            points += outcome.points
            if (!outcome.ok) {
                return Attempt(ok = false, points = points, message = "A keypad tap was cancelled")
            }
        }
        keypad.submit?.let { points += touch.tap(it).points }
        return if (awaitUnlocked()) {
            Attempt(ok = true, backend = "pin", points = points)
        } else {
            Attempt(ok = false, backend = "pin", points = points, message = "The PIN was rejected")
        }
    }

    private suspend fun scanKeypad(): Keypad = withContext(io) { tree.pinpad().asKeypad() }

    private fun PinpadKeys.asKeypad(): Keypad =
        Keypad(digits.mapKeys { (digit, _) -> digit.first() }, submit)

    /** Brings up the credential prompt, and returns the points touched doing it. */
    private suspend fun revealBouncer(): List<Point> {
        if (shell.isAvailable) {
            val result = shell.exec(listOf("wm", "dismiss-keyguard"))
            if (result.ok) {
                awaitUiChange()
                return emptyList()
            }
        }
        val points = swipeUp()
        awaitUiChange()
        return points
    }

    private suspend fun swipeUp(): List<Point> {
        val screen = display()
        val x = screen.widthPx / 2f
        val from = Point(x, screen.heightPx * SWIPE_FROM)
        val to = Point(x, screen.heightPx * SWIPE_TO)
        return touch.swipe(from, to, SwipeSpec(durationMs = SWIPE_MS, fling = true)).points
    }

    /** Lets the bouncer's window settle before reading the tree again. */
    private suspend fun awaitUiChange() {
        val before = seq.value
        withTimeoutOrNull(BOUNCER_WAIT_MS) { seq.first { it != before } }
        delay(BOUNCER_SETTLE_MS)
    }

    private fun wakeFailed() =
        ApiException(
            409,
            "wake_failed",
            "The screen did not turn on. Without the helper this needs permission to start " +
                "activities from the background.",
            state = state.refresh(),
        )

    private fun needsUser(why: String?) =
        ApiException(409, "needs_user", why ?: NO_PIN, state = state.refresh())

    private fun deviceLocked(why: String?): ApiException {
        val summary = state.refresh()
        val detail = why?.let { " ($it)" }.orEmpty()
        return ApiException(
            409,
            "device_locked",
            "The device is locked$detail. Unlock it with POST /v1/device/unlock, or pass " +
                "autoWake=true once a PIN is configured.",
            state = summary,
        )
    }

    private companion object {
        const val TAG = "PhoneApiPower"
        const val WAKE_WAIT_MS = 2_000L
        const val UNLOCK_WAIT_MS = 3_000L
        const val LOCK_WAIT_MS = 2_000L
        const val BOUNCER_WAIT_MS = 1_500L
        const val BOUNCER_SETTLE_MS = 150L
        const val MIN_DIGIT_GAP_MS = 110L
        const val MAX_DIGIT_GAP_MS = 240L
        const val SWIPE_MS = 220L
        const val SWIPE_FROM = 0.85f
        const val SWIPE_TO = 0.2f

        const val NO_PIN =
            "The lock screen is secure and no PIN is stored. PUT /v1/device/pin with admin " +
                "scope, or provision one over ADB with `am broadcast -a net.die.phoneapi.SET_PIN " +
                "--es pin <pin>`. Otherwise unlock the phone by hand."
        const val NO_KEYPAD =
            "No PIN keypad was found on the lock screen; a pattern or password lock cannot be " +
                "entered automatically."
    }
}
