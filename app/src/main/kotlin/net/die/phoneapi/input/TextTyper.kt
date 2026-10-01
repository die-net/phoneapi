package net.die.phoneapi.input

import android.R.id.selectAll
import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.inputmethod.EditorInfo
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.a11y.NodeCompat
import net.die.phoneapi.a11y.NodeTargeting
import net.die.phoneapi.a11y.PhoneAccessibilityService
import net.die.phoneapi.a11y.SnapshotEngine
import net.die.phoneapi.a11y.require
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.InputBackend
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.TextMode
import net.die.phoneapi.model.TextRequest

/** Types text in one of the [TextMode]s, from most to least realistic. */
class TextTyper(
    private val a11y: StateFlow<PhoneAccessibilityService?>,
    private val io: CoroutineDispatcher,
    private val snapshots: SnapshotEngine,
    private val targeting: NodeTargeting,
    private val touch: TouchInput,
    private val state: DeviceStateTracker,
    private val touchBackends: TouchBackends,
    private val keyBackends: KeyBackends,
    private val random: Random = Random.Default,
) {

    suspend fun type(request: TextRequest): ActionResult {
        validate(request)
        val service = a11y.require()
        return withContext(io) {
            val points = ArrayList<Point>()
            request.selector?.let { focus(service, it, points) }
            val result =
                when (request.mode) {
                    TextMode.IME -> typeIme(service, request) ?: throw imeSessionStale()
                    TextMode.KEYBOARD -> typeKeyboardOrFallback(service, request, points)
                    TextMode.SET_TEXT -> setText(service, request)
                    TextMode.KEYEVENT -> typeKeyEvents(request)
                    TextMode.AUTO -> typeAuto(service, request, points)
                }
            result.copy(points = points + result.points)
        }
    }

    private suspend fun typeAuto(
        service: AccessibilityService,
        request: TextRequest,
        points: MutableList<Point>,
    ): ActionResult {
        val viaIme =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) typeIme(service, request)
            else null
        return viaIme ?: typeKeyboardOrFallback(service, request, points)
    }

    private suspend fun focus(
        service: AccessibilityService,
        selector: NodeSelector,
        points: MutableList<Point>,
    ) {
        val node = snapshots.resolve(selector)
        try {
            val target = targeting.touchTarget(service, node, force = false)
            val outcome = touch.tap(target)
            points += outcome.points
            if (!outcome.ok) throw gestureCancelled()
            if (node.isEditable) {
                withTimeoutOrNull(FOCUS_WAIT_MS) {
                    while (!(node.refresh() && node.isFocused)) delay(POLL_MS)
                }
            } else {
                delay(NON_EDITABLE_FOCUS_SETTLE_MS)
            }
        } finally {
            NodeCompat.recycle(node)
        }
    }

    /** Returns null, before typing anything, when the editor no longer accepts our commits. */
    private suspend fun typeIme(
        service: AccessibilityService,
        request: TextRequest,
    ): ActionResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw ApiException(422, "unsupported", "mode=ime needs Android 13 or later")
        }
        val connection =
            withTimeoutOrNull(EDITOR_WAIT_MS) {
                var found = service.editorConnection()
                while (found == null) {
                    delay(POLL_MS)
                    found = service.editorConnection()
                }
                found
            } ?: throw noEditor()
        // The platform stamps accessibility input calls with session 0, and the app silently drops
        // them once the editor has restarted input; getSurroundingText then answers null.
        if (connection.getSurroundingText(0, 0, 0) == null) return null
        if (request.clear) {
            connection.performContextMenuAction(selectAll)
            connection.commitText("", 1, null)
        }
        // The accessibility input connection has no composing-text API, so each character is
        // committed on its own, as a keyboard's per-key commit would be.
        codePoints(request.text).forEachIndexed { i, unit ->
            if (i > 0) delay(cadence(request))
            val current =
                service.editorConnection()
                    ?: throw ApiException(
                        409,
                        "editor_lost",
                        "Focus left the text field after $i characters",
                    )
            current.commitText(unit, 1, null)
        }
        if (request.submit) submitIme(service)
        return ActionResult(ok = true, backend = "ime")
    }

    private fun submitIme(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val connection = service.editorConnection() ?: throw noEditor()
        val options = service.inputMethod?.currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val plainEnter =
            action == EditorInfo.IME_ACTION_NONE ||
                action == EditorInfo.IME_ACTION_UNSPECIFIED ||
                options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (plainEnter) {
            val now = SystemClock.uptimeMillis()
            connection.sendKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
            )
            connection.sendKeyEvent(
                KeyEvent(
                    now,
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_ENTER,
                    0,
                )
            )
        } else {
            connection.performEditorAction(action)
        }
    }

    private suspend fun typeKeyboardOrFallback(
        service: AccessibilityService,
        request: TextRequest,
        points: MutableList<Point>,
    ): ActionResult {
        val typed = typeKeyboard(service, request, points)
        if (typed != null) return typed
        if (request.mode != TextMode.AUTO) {
            throw ApiException(409, "no_keyboard", "No on-screen keyboard keys were found")
        }
        return setText(service, request)
            .copy(message = "No on-screen keyboard keys were found, so setText was used instead")
    }

    /** Returns null, before typing anything, when there is no usable on-screen keyboard. */
    private suspend fun typeKeyboard(
        service: AccessibilityService,
        request: TextRequest,
        points: MutableList<Point>,
    ): ActionResult? {
        val unsupported = request.text.filterNot { it == '\n' || it in ' '..'~' }.toSet()
        if (unsupported.isNotEmpty()) throw unsupportedChars(unsupported)
        withTimeoutOrNull(IME_WAIT_MS) { state.state.first { it.ime.visible } }
        if (!KeyMatcher.looksLikeKeyboard(ImeKeyboard.scan(service))) return null
        if (request.clear) clearWithKeyboard(service, points)
        request.text.forEachIndexed { i, c ->
            if (i > 0) delay(cadence(request))
            typeKey(service, c, points)
        }
        var message: String? = null
        if (request.submit) {
            val action = KeyMatcher.action(ImeKeyboard.scan(service))
            if (action != null) tapKey(action, points) else message = imeEnterFocused(service)
        }
        return ActionResult(
            ok = true,
            backend = touchBackends.select(DEFAULT_BACKEND).name,
            message = message,
        )
    }

    private suspend fun typeKey(
        service: AccessibilityService,
        c: Char,
        points: MutableList<Point>,
    ) {
        val tried = HashSet<String>()
        repeat(MAX_KEY_ATTEMPTS) {
            val keys = ImeKeyboard.scan(service)
            val key =
                when (c) {
                    ' ' -> KeyMatcher.space(keys)
                    '\n' -> KeyMatcher.action(keys)
                    else -> KeyMatcher.forChar(keys, c)
                }
            if (key != null) {
                tapKey(key, points)
                return
            }
            val modifier = modifierFor(keys, c, tried) ?: throw unsupportedChars(setOf(c))
            tried += modifier.label
            tapKey(modifier, points)
            delay(MODIFIER_SETTLE_MS)
        }
        throw unsupportedChars(setOf(c))
    }

    /** The shift or page key to press so that [c] shows up, or null if we've run out of pages. */
    private fun modifierFor(keys: List<ImeKey>, c: Char, tried: Set<String>): ImeKey? {
        val candidates =
            if (c.isLetter()) {
                if (KeyMatcher.otherCase(keys, c) != null) listOfNotNull(KeyMatcher.shift(keys))
                else listOfNotNull(KeyMatcher.lettersPage(keys))
            } else {
                listOfNotNull(KeyMatcher.symbolsPage(keys), KeyMatcher.moreSymbolsPage(keys))
            }
        return candidates.firstOrNull { it.label !in tried }
    }

    private suspend fun clearWithKeyboard(
        service: AccessibilityService,
        points: MutableList<Point>,
    ) {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        try {
            val length = if (node.isShowingHintText) 0 else node.text?.length ?: 0
            if (length == 0) return
            val args =
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            delay(MODIFIER_SETTLE_MS)
            KeyMatcher.delete(ImeKeyboard.scan(service))?.let { tapKey(it, points) }
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private suspend fun tapKey(key: ImeKey, points: MutableList<Point>) {
        val outcome = touch.tap(key.bounds, backend = DEFAULT_BACKEND)
        points += outcome.points
        if (!outcome.ok) throw gestureCancelled()
    }

    private fun setText(service: AccessibilityService, request: TextRequest): ActionResult {
        val node =
            service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: throw noEditor()
        try {
            val current =
                if (request.clear || node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val args =
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        current + request.text,
                    )
                }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            val message = if (ok && request.submit) imeEnter(node) else null
            return ActionResult(ok = ok, backend = "setText", message = message)
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private fun imeEnterFocused(service: AccessibilityService): String? {
        val node =
            service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return "No focused field to submit"
        try {
            return imeEnter(node)
        } finally {
            NodeCompat.recycle(node)
        }
    }

    /** Returns a note for the result message when the IME action couldn't be performed. */
    private fun imeEnter(node: AccessibilityNodeInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "submit needs Android 11+ without an on-screen keyboard action key"
        }
        return if (node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) null
        else "The field rejected the IME action"
    }

    private suspend fun typeKeyEvents(request: TextRequest): ActionResult {
        val helper =
            keyBackends.helper.takeIf { it.isAvailable } ?: throw ApiException.helperUnavailable()
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events =
            request.text.map { c ->
                map.getEvents(charArrayOf(c)) ?: throw unsupportedChars(setOf(c))
            }
        if (request.clear) {
            keyBackends.press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, longPress = false)
            keyBackends.press(KeyEvent.KEYCODE_DEL, 0, longPress = false)
        }
        events.forEachIndexed { i, keyEvents ->
            if (i > 0) delay(cadence(request))
            keyEvents.forEach { helper.send(it) }
        }
        if (request.submit) keyBackends.press(KeyEvent.KEYCODE_ENTER, 0, longPress = false)
        return ActionResult(ok = true, backend = helper.name)
    }

    private fun cadence(request: TextRequest): Long =
        random.nextLong(request.minDelayMs, request.maxDelayMs + 1)

    private fun validate(request: TextRequest) {
        if (request.text.length > MAX_TEXT)
            throw ApiException.badRequest("text is longer than $MAX_TEXT characters")
        if (
            request.minDelayMs < 0 ||
                request.maxDelayMs < request.minDelayMs ||
                request.maxDelayMs > MAX_DELAY_MS
        ) {
            throw ApiException.badRequest("Need 0 <= minDelayMs <= maxDelayMs <= $MAX_DELAY_MS")
        }
    }

    private fun noEditor() =
        ApiException(
            409,
            "no_editor",
            "No text field is focused; pass a selector or tap a field first",
        )

    private fun imeSessionStale() =
        ApiException(
            409,
            "ime_session_stale",
            "This field restarted its input session, after which Android ignores accessibility " +
                "typing until the field is recreated; use mode=keyboard, setText or auto",
        )

    private fun gestureCancelled() =
        ApiException(409, "gesture_cancelled", "The system cancelled the touch")

    private fun unsupportedChars(chars: Set<Char>) =
        ApiException(
            422,
            "unsupported_char",
            "Can't type ${chars.joinToString(" ") { "'$it'" }} on the on-screen keyboard; " +
                "try mode=ime or mode=setText",
        )

    private companion object {
        val DEFAULT_BACKEND = InputBackend.AUTO
        const val MAX_TEXT = 10_000
        const val MAX_DELAY_MS = 5_000L
        const val MAX_KEY_ATTEMPTS = 5
        const val MODIFIER_SETTLE_MS = 120L
        const val POLL_MS = 50L
        const val FOCUS_WAIT_MS = 1_000L
        const val NON_EDITABLE_FOCUS_SETTLE_MS = 400L
        const val EDITOR_WAIT_MS = 1_500L
        const val IME_WAIT_MS = 1_500L
    }
}

private fun codePoints(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val n = Character.charCount(text.codePointAt(i))
        out += text.substring(i, i + n)
        i += n
    }
    return out
}
