package net.die.phoneapi.helper.tree

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.die.phoneapi.helper.ITreeClient
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DeviceStateSummary
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.ImeState
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.SnapshotOptions
import net.die.phoneapi.model.TreeResult

/** UI tree for the shell helper. Live nodes stay in this process. */
@Suppress("InjectDispatcher") // The helper process has no app graph to inject a dispatcher from.
internal class TreeHost(private val client: () -> ITreeClient?) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }
    private val screen = Rect(0, 0, 1, 1)
    private var imePackage: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nodes = NodeRegistry()
    private val holds = AtomicInteger()
    private val session: UiAutomationSession
    private val tracker: UiChangeTracker
    private val snapshots: SnapshotEngine
    private val targeting = NodeTargeting { Rect(screen) }

    init {
        var created: UiChangeTracker? = null
        session =
            UiAutomationSession(
                onEvent = { event -> created?.onEvent(event) },
                onConnection = { up ->
                    if (up) created?.onConnected()
                    notify { it.onConnected(up) }
                },
                busy = { holds.get() > 0 },
            )
        tracker =
            UiChangeTracker(
                sink = RemoteSink(),
                windows = { session.windows() },
                scope = scope,
                dispatcher = Dispatchers.IO,
            )
        created = tracker
        snapshots =
            SnapshotEngine(
                windows = { session.windows() },
                io = Dispatchers.IO,
                seq = tracker.seq,
                imePackage = { imePackage },
                nodes = nodes,
                screenRect = { Rect(screen) },
            )
    }

    fun call(op: String, payload: String): String {
        val screened = json.decodeFromString(Screened.serializer(), payload)
        screen.set(0, 0, screened.widthPx.coerceAtLeast(1), screened.heightPx.coerceAtLeast(1))
        return try {
            done(dispatch(op, screened.body.ifBlank { "{}" }))
        } catch (e: ApiException) {
            result(TreeResult(e.status, e.error, e.message))
        } catch (e: SerializationException) {
            result(TreeResult(400, "bad_request", e.message))
        }
    }

    fun shutdown() {
        session.disconnect()
    }

    private fun dispatch(op: String, body: String): String =
        when (op) {
            "snapshot",
            "find",
            "keyboard",
            "pinpad",
            "contentBounds",
            "windowTitle" -> read(op, body)
            "hold",
            "release",
            "invalidate" -> sessionOp(op)
            else -> write(op, body)
        }

    private fun read(op: String, body: String): String =
        when (op) {
            "snapshot" -> {
                val call = json.decodeFromString(SnapshotCall.serializer(), body)
                val shot = runBlocking { snapshots.snapshot(call.options, call.summary) }
                json.encodeToString(shot)
            }
            "find" -> {
                val request = json.decodeFromString(FindRequest.serializer(), body)
                json.encodeToString(runBlocking { snapshots.find(request) })
            }
            "keyboard" -> json.encodeToString(withWindows { ImeKeyboard.scan(it) })
            "pinpad" -> json.encodeToString(withWindows { PinPad.keys(it) })
            "contentBounds" -> {
                val call = json.decodeFromString(PackageCall.serializer(), body)
                json.encodeToString(withWindows { ContentFrame.find(it, call.packageName) })
            }
            "windowTitle" -> {
                val call = json.decodeFromString(TitleCall.serializer(), body)
                val found = withWindows { windows ->
                    windows.any { it.title?.contains(call.text, ignoreCase = true) == true }
                }
                json.encodeToString(Found(found))
            }
            else -> throw ApiException.badRequest("Unknown tree op '$op'")
        }

    private fun sessionOp(op: String): String =
        when (op) {
            "invalidate" -> {
                snapshots.invalidate()
                "{}"
            }
            "hold" -> {
                holds.incrementAndGet()
                session.ensure()
                "{}"
            }
            "release" -> {
                holds.updateAndGet { (it - 1).coerceAtLeast(0) }
                session.poke()
                "{}"
            }
            else -> throw ApiException.badRequest("Unknown tree op '$op'")
        }

    private fun write(op: String, body: String): String =
        when (op) {
            "act" -> {
                val call = json.decodeFromString(ActCall.serializer(), body)
                val result = nodes.withNode(call.ref) { semantic(it, call.action, call.text) }
                snapshots.invalidate()
                json.encodeToString(result)
            }
            "touchTarget" -> {
                val call = json.decodeFromString(TouchCall.serializer(), body)
                val rect = runBlocking {
                    nodes.withNode(call.ref) { node ->
                        targeting.touchTarget(node, session.windows(), call.force)
                    }
                }
                json.encodeToString(rect)
            }
            "global" -> {
                val call = json.decodeFromString(GlobalCall.serializer(), body)
                json.encodeToString(ActionResult(ok = session.performGlobalAction(call.action)))
            }
            "setText" -> {
                val call = json.decodeFromString(SetTextCall.serializer(), body)
                json.encodeToString(setText(call))
            }
            "focus" -> {
                val call = json.decodeFromString(FocusCall.serializer(), body)
                json.encodeToString(focus(call.selector))
            }
            "selectFocused" -> json.encodeToString(selectFocused())
            "imeEnter" -> json.encodeToString(imeEnter())
            else -> throw ApiException.badRequest("Unknown tree op '$op'")
        }

    private fun <T> withWindows(block: (List<AccessibilityWindowInfo>) -> T): T {
        val open = session.windows()
        return try {
            block(open)
        } finally {
            NodeCompat.recycleAll(open)
        }
    }

    private fun semantic(node: AccessibilityNodeInfo, action: String, text: String?): ActionResult {
        if (
            action.equals(ActionNames.IME_ENTER, ignoreCase = true) &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            throw ApiException(422, "unsupported", "imeEnter needs Android 11 or later")
        }
        val platform =
            ActionCatalog.forName(action)
                ?: ActionCatalog.custom(node, action)
                ?: throw ApiException.badRequest("Unknown action '$action'")
        val args =
            if (platform.id == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val value = text ?: throw ApiException.badRequest("setText needs text")
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value,
                    )
                }
            } else {
                null
            }
        val ok = node.performAction(platform.id, args)
        return ActionResult(
            ok = ok,
            backend = "semantic",
            message = if (ok) null else "The node rejected '$action'",
        )
    }

    private fun setText(call: SetTextCall): ActionResult {
        val node =
            session.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: throw ApiException(409, "no_editor", "No text field is focused")
        try {
            val current =
                if (call.clear || node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val args =
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        current + call.text,
                    )
                }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            val message =
                if (ok && call.submit) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        "submit needs Android 11 or later"
                    } else if (
                        node.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    ) {
                        null
                    } else {
                        "The field rejected the IME action"
                    }
                } else {
                    null
                }
            snapshots.invalidate()
            return ActionResult(ok = ok, backend = "setText", message = message)
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private fun selectFocused(): FocusedText {
        val node = session.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return FocusedText(0)
        try {
            val length = if (node.isShowingHintText) 0 else node.text?.length ?: 0
            if (length == 0) return FocusedText(0)
            val args =
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            return FocusedText(length)
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private fun imeEnter(): ActionResult {
        val node =
            session.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return ActionResult(ok = false, message = "No focused field to submit")
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return ActionResult(
                    ok = false,
                    message = "submit needs Android 11+ without an on-screen keyboard action key",
                )
            }
            val ok =
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return ActionResult(
                ok = ok,
                backend = "setText",
                message = if (ok) null else "The field rejected the IME action",
            )
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private fun focus(selector: NodeSelector?): ActionResult {
        val node =
            if (selector == null || !selector.targetsNode()) {
                session.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: throw ApiException.badRequest("No editable field is focused")
            } else {
                runBlocking { snapshots.resolve(selector) }
            }
        try {
            if (!node.isEditable) throw ApiException.badRequest("The node is not editable")
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            snapshots.invalidate()
            return ActionResult(ok = true, backend = "focus")
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private fun NodeSelector.targetsNode(): Boolean =
        ref != null || index != null || !SelectorMatcher(this).isEmpty

    private fun done(body: String): String = result(TreeResult(body = body))

    private fun result(value: TreeResult): String = json.encodeToString(value)

    /** A dead client must not take down this process. Events arrive on the helper's looper. */
    private fun notify(block: (ITreeClient) -> Unit) {
        val current = client() ?: return
        try {
            block(current)
        } catch (e: RemoteException) {
            Log.w(TAG, "UI client is gone", e)
        }
    }

    private inner class RemoteSink : UiSink {
        private var ime = ImeState(visible = false)
        private var foreground: String? = null

        override fun emit(type: String, body: JsonObject) {
            notify { it.onBus(type, body.toString()) }
        }

        override fun setIme(state: ImeState) {
            if (state == ime) return
            ime = state
            imePackage = state.packageName
            notify { it.onIme(json.encodeToString(state)) }
        }

        override fun setForeground(packageName: String?) {
            if (packageName == foreground) return
            foreground = packageName
            notify { it.onForeground(packageName.orEmpty()) }
        }

        override fun foreground(): String? = foreground

        override fun onTick(seq: Long, windowsVersion: Long, lastChangeMs: Long) {
            notify { it.onSeq(seq, windowsVersion, lastChangeMs) }
        }
    }

    @Serializable
    private data class Screened(val widthPx: Int, val heightPx: Int, val body: String = "{}")

    @Serializable
    private data class SnapshotCall(val options: SnapshotOptions, val summary: DeviceStateSummary)

    @Serializable
    private data class ActCall(val ref: String, val action: String, val text: String? = null)

    @Serializable private data class TouchCall(val ref: String, val force: Boolean = false)

    @Serializable private data class GlobalCall(val action: Int)

    @Serializable
    private data class SetTextCall(
        val text: String,
        val clear: Boolean = false,
        val submit: Boolean = false,
    )

    @Serializable private data class FocusCall(val selector: NodeSelector? = null)

    @Serializable private data class PackageCall(val packageName: String = "")

    @Serializable private data class TitleCall(val text: String)

    @Serializable private data class Found(val found: Boolean)

    @Serializable private data class FocusedText(val length: Int)

    private companion object {
        const val TAG = "PhoneApiHelper"
    }
}
