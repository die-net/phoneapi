package net.die.phoneapi.tree

import android.os.RemoteException
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.helper.IHelper
import net.die.phoneapi.helper.ITreeClient
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.ImeState
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.PinpadKeys
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.SnapshotOptions
import net.die.phoneapi.model.TreeActCall
import net.die.phoneapi.model.TreeFocusCall
import net.die.phoneapi.model.TreeFocusedText
import net.die.phoneapi.model.TreeFound
import net.die.phoneapi.model.TreeGlobalCall
import net.die.phoneapi.model.TreeKey
import net.die.phoneapi.model.TreePackageCall
import net.die.phoneapi.model.TreeResult
import net.die.phoneapi.model.TreeScreened
import net.die.phoneapi.model.TreeSetTextCall
import net.die.phoneapi.model.TreeSnapshotCall
import net.die.phoneapi.model.TreeTitleCall
import net.die.phoneapi.model.TreeTouchCall
import net.die.phoneapi.model.UiSnapshot

/**
 * App-side client of the helper's UI tree. Live nodes stay in the helper; this process sends refs
 * and mirrors the sequence, IME, and foreground updates the helper pushes back.
 */
class TreeSession(
    private val helper: HelperConnection,
    private val bus: EventBus,
    private val state: DeviceStateTracker,
    private val display: () -> DisplayInfo,
    private val onSession: (Boolean) -> Unit = {},
) : ITreeClient.Stub() {
    private val seqFlow = MutableStateFlow(0L)
    private val windowsFlow = MutableStateFlow(0L)
    private val connectedFlow = MutableStateFlow(false)

    val seq: StateFlow<Long> = seqFlow.asStateFlow()
    val windowsVersion: StateFlow<Long> = windowsFlow.asStateFlow()
    val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

    @Volatile
    var lastChangeMs: Long = SystemClock.uptimeMillis()
        private set

    @Volatile private var listening = false

    /** Keeps the helper's UiAutomation connection up while something is collecting events. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            bus.subscribers.collect { count ->
                val want = count > 0
                if (want == listening) return@collect
                listening = want
                if (want) runCatching { rpc("hold", "{}") }
                else runCatching { rpc("release", "{}") }
            }
        }
    }

    /** Called once a helper binder is accepted, so events have somewhere to land. */
    fun attach(proxy: IHelper) {
        proxy.setTreeClient(this)
        if (listening) runCatching { proxy.tree("hold", envelope("{}")) }
    }

    fun snapshot(options: SnapshotOptions): UiSnapshot {
        val body =
            ApiJson.encodeToString(
                serializer<TreeSnapshotCall>(),
                TreeSnapshotCall(options, state.refresh()),
            )
        return decode(rpc("snapshot", body))
    }

    fun find(request: FindRequest): FindResult = decode(rpc("find", encode(request)))

    fun act(ref: String, action: String, text: String?): ActionResult =
        decode(rpc("act", encode(TreeActCall(ref, action, text))))

    fun touchTarget(ref: String, force: Boolean): Rect =
        decode(rpc("touchTarget", encode(TreeTouchCall(ref, force))))

    fun global(action: Int): Boolean {
        val result = decode<ActionResult>(rpc("global", encode(TreeGlobalCall(action))))
        return result.ok
    }

    fun setText(text: String, clear: Boolean, submit: Boolean): ActionResult =
        decode(rpc("setText", encode(TreeSetTextCall(text, clear, submit))))

    fun focus(selector: NodeSelector?): ActionResult =
        decode(rpc("focus", encode(TreeFocusCall(selector))))

    fun keyboard(): List<TreeKey> =
        ApiJson.decodeFromString(ListSerializer(TreeKey.serializer()), rpc("keyboard", "{}"))

    fun pinpad(): PinpadKeys = decode(rpc("pinpad", "{}"))

    fun contentBounds(packageName: String): Rect =
        decode(rpc("contentBounds", encode(TreePackageCall(packageName))))

    fun windowTitle(text: String): Boolean {
        val found = decode<TreeFound>(rpc("windowTitle", encode(TreeTitleCall(text))))
        return found.found
    }

    fun invalidate() {
        rpc("invalidate", "{}")
    }

    /** Characters currently in the focused field, after selecting them. Zero when it is empty. */
    fun selectFocused(): Int {
        val focused = decode<TreeFocusedText>(rpc("selectFocused", "{}"))
        return focused.length
    }

    fun imeEnter(): ActionResult = decode(rpc("imeEnter", "{}"))

    override fun onSeq(seq: Long, windowsVersion: Long, lastChangeMs: Long) {
        this.lastChangeMs = lastChangeMs
        seqFlow.value = seq
        windowsFlow.value = windowsVersion
    }

    override fun onBus(type: String, json: String) {
        val body = runCatching {
            ApiJson.parseToJsonElement(json).jsonObject
        }
            .getOrDefault(JsonObject(emptyMap()))
        bus.emit(type, body)
    }

    override fun onIme(json: String) {
        val ime =
            runCatching { ApiJson.decodeFromString(ImeState.serializer(), json) }.getOrNull()
                ?: return
        state.setIme(ime)
    }

    override fun onForeground(packageName: String) {
        state.setForegroundPackage(packageName.takeIf { it.isNotEmpty() })
    }

    override fun onConnected(connected: Boolean) {
        connectedFlow.value = connected
        onSession(connected)
    }

    private fun rpc(op: String, body: String): String {
        val raw =
            try {
                helper.require().tree(op, envelope(body))
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            } catch (e: IllegalStateException) {
                throw ApiException(
                    503,
                    "helper_error",
                    e.message ?: "The helper tree call failed",
                    cause = e,
                )
            } ?: throw ApiException.helperDropped()
        val result = ApiJson.decodeFromString(TreeResult.serializer(), raw)
        if (result.status != 200) {
            throw ApiException(
                result.status,
                result.error ?: "tree_failed",
                result.message,
            )
        }
        return result.body ?: "{}"
    }

    private fun envelope(body: String): String {
        val screen = display()
        return ApiJson.encodeToString(
            serializer<TreeScreened>(),
            TreeScreened(screen.widthPx, screen.heightPx, body),
        )
    }

    private inline fun <reified T> encode(value: T): String =
        ApiJson.encodeToString(serializer<T>(), value)

    private inline fun <reified T> decode(body: String): T =
        ApiJson.decodeFromString(serializer<T>(), body)
}
