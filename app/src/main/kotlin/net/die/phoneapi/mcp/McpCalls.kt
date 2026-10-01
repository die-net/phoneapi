package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.ScreenshotScale
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.core.checkLogTag
import net.die.phoneapi.core.windowId
import net.die.phoneapi.model.ActionMode
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SnapshotFormat
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.schema.Doc
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.wait.requireWaitAccess

internal suspend fun deviceInfo(services: ServerServices): CallToolResult = runTool {
    jsonText(services.device.info())
}

internal suspend fun uiSnapshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<SnapshotArgs>()
    val snapshot =
        services.ui.snapshot(
            SnapshotOptions(
                format = SnapshotFormat.COMPACT,
                windowId = args.window?.let(::windowId),
                maxDepth = args.maxDepth,
                includeInvisible = args.includeInvisible,
                allWindows = args.all,
                autoWake = args.autoWake,
            )
        )
    snapshot.compact ?: "(empty snapshot)"
}

internal suspend fun uiFind(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.ui.find(request.args<FindRequest>()))
    }

internal suspend fun uiAct(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<NodeActArgs>()
        jsonText(
            services.ui.act(
                args.ref,
                NodeActionRequest(
                    action = args.action,
                    mode = args.mode,
                    text = args.text,
                    force = args.force,
                    autoWake = args.autoWake,
                ),
            )
        )
    }

internal suspend fun tap(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.tap(request.args<TapRequest>()))
    }

internal suspend fun swipe(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.swipe(request.args<SwipeRequest>()))
    }

internal suspend fun typeText(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.text(request.args<TextRequest>()))
    }

internal suspend fun pressKey(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.key(request.args<KeyRequest>()))
    }

internal suspend fun waitFor(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<WaitArgs>()
    val wait =
        WaitRequest(
            all = args.all,
            any = args.any,
            timeoutMs = args.timeoutMs,
            settleMs = args.settleMs,
            snapshot = args.snapshot,
            snapshotFormat = SnapshotFormat.COMPACT,
        )
    requireWaitAccess(wait, scopes)
    val result = services.waits.wait(wait, scopes)
    val header =
        "matched=${result.matched} timedOut=${result.timedOut} elapsedMs=${result.elapsedMs}"
    val body = result.snapshot?.compact
    if (body.isNullOrBlank()) header else "$header\n$body"
}

internal suspend fun keyboardHide(services: ServerServices): CallToolResult = runTool {
    jsonText(services.input.hideIme())
}

internal suspend fun keyboardShow(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.input.showIme(request.args<ImeShowRequest>()))
}

internal suspend fun appLaunch(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<PackageArgs>()
        jsonText(
            services.apps.launch(
                args.packageName,
                LaunchRequest(fresh = args.fresh, activity = args.activity, wait = args.wait),
            )
        )
    }

internal suspend fun appStop(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.apps.stop(request.args<PackageNameArgs>().packageName))
    }

internal suspend fun appClear(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.apps.clear(request.args<PackageNameArgs>().packageName))
    }

internal suspend fun openIntent(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.apps.intent(request.args<IntentRequest>()))
}

internal suspend fun deviceWake(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.wake())
}

internal suspend fun deviceUnlock(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.unlock(UnlockRequest()))
}

internal suspend fun deviceLock(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.lock())
}

internal suspend fun browserTargets(services: ServerServices): CallToolResult = runTool {
    jsonText(services.browser.targets())
}

internal suspend fun browserOpen(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.openTab(request.args<UrlArgs>().url))
}

internal suspend fun browserNavigate(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<TargetUrlArgs>()
    jsonText(services.browser.navigate(args.target, args.url))
}

internal suspend fun browserSnapshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val snapshot = services.browser.snapshot(request.args<TargetArgs>().target)
    snapshot.compact
}

internal suspend fun browserTap(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserTapArgs>()
    jsonText(
        services.browser.tap(
            args.target,
            BrowserTapRequest(
                ref = args.ref,
                selector = args.selector,
                humanize = args.humanize,
                autoWake = args.autoWake,
            ),
        )
    )
}

internal suspend fun browserEval(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<EvalArgs>()
    jsonText(
        services.browser.evaluate(args.target, EvalRequest(args.expression, args.awaitPromise))
    )
}

internal suspend fun browserConsole(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<ConsoleArgs>()
    jsonText(services.browser.console(args.target, ConsoleRequest(args.timeoutMs)))
}

internal suspend fun screenshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult =
    try {
        val scale = request.args<ScaleArgs>().scale
        val png = services.screenshots(scale)
        if (png.size > MAX_PNG_BYTES) {
            throw ApiException.badRequest("Screenshot is too large; pass a smaller scale")
        }
        val data = Base64.getEncoder().encodeToString(png)
        CallToolResult(content = listOf(ImageContent(data = data, mimeType = "image/png")))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        toolFailure(toolError(e))
    } catch (e: SerializationException) {
        toolFailure("bad_request: ${e.message.orEmpty()}")
    }

internal suspend fun logcatTail(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<LogcatArgs>()
    if (args.lines !in 1..MAX_LOG_LINES) {
        throw ApiException.badRequest("lines must be 1..$MAX_LOG_LINES")
    }
    val tag = args.tag
    if (tag != null) checkLogTag(tag, "tag")
    val argv = mutableListOf("logcat", "-d", "-t", args.lines.toString())
    if (tag != null) argv += "$tag:I"
    val result = services.shell(argv)
    if (!result.ok) throw ApiException(503, "helper_error", result.stderr.ifBlank { result.stdout })
    result.stdout
}

private suspend fun runTool(block: suspend () -> String): CallToolResult =
    try {
        toolOk(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        toolFailure(toolError(e))
    } catch (e: SerializationException) {
        toolFailure("bad_request: ${e.message.orEmpty()}")
    }

private fun toolOk(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = text)), isError = false)

private fun toolFailure(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = text)), isError = true)

private fun toolError(error: ApiException): String {
    val detail = error.message?.takeIf { it.isNotBlank() && it != error.error }
    return if (detail == null) error.error else "${error.error}: $detail"
}

internal inline fun <reified T> jsonText(value: T): String =
    ApiJson.encodeToString(serializer<T>(), value)

private inline fun <reified T> CallToolRequest.args(): T {
    val arguments = this.arguments ?: JsonObject(emptyMap())
    return ApiJson.decodeFromJsonElement(serializer<T>(), arguments)
}

@Serializable
internal data class SnapshotArgs(
    @Doc("Window id, such as 3 or w3. Defaults to the active window.") val window: String? = null,
    val maxDepth: Int? = null,
    val includeInvisible: Boolean = false,
    @Doc("Include system windows.") val all: Boolean = false,
    val autoWake: Boolean = true,
)

@Serializable
internal data class NodeActArgs(
    @Doc("Node ref from ui_snapshot or ui_find, such as e12.") val ref: String,
    @Doc(
        "Standard action such as click, longClick, setText, scrollForward or imeEnter, or a custom action label listed on the node in the snapshot."
    )
    val action: String,
    @Doc("real uses a touch where it can. semantic calls performAction and generates no touch.")
    val mode: ActionMode = ActionMode.REAL,
    val text: String? = null,
    val force: Boolean = false,
    val autoWake: Boolean = true,
)

@Serializable internal data class PackageNameArgs(@SerialName("package") val packageName: String)

@Serializable
internal data class PackageArgs(
    @SerialName("package") val packageName: String,
    val fresh: Boolean = false,
    val activity: String? = null,
    val wait: Boolean = true,
)

@Serializable internal data class UrlArgs(val url: String)

@Serializable internal data class TargetArgs(val target: String)

@Serializable internal data class TargetUrlArgs(val target: String, val url: String)

@Serializable
internal data class BrowserTapArgs(
    val target: String,
    val ref: String? = null,
    val selector: String? = null,
    val humanize: Boolean = true,
    val autoWake: Boolean = true,
)

@Serializable
internal data class EvalArgs(
    val target: String,
    val expression: String,
    val awaitPromise: Boolean = false,
)

@Serializable internal data class ConsoleArgs(val target: String, val timeoutMs: Long = 1_000)

@Serializable
internal data class ScaleArgs(
    @Doc("Scale from 0.1 to 1. Defaults to 0.5, which is smaller than the REST default.")
    val scale: Float = ScreenshotScale.MCP
)

@Serializable internal data class LogcatArgs(val lines: Int = 80, val tag: String? = null)

@Serializable internal class EmptyArgs

@Serializable
internal data class WaitArgs(
    @Doc(
        "Every condition must match. Each object's type is node, window, ime, idle, screen, keyguard, or browser.*."
    )
    val all: List<WaitCondition> = emptyList(),
    @Doc("At least one condition must match. Same types as all.")
    val any: List<WaitCondition> = emptyList(),
    @Doc("Give up after this many milliseconds.") val timeoutMs: Long = 10_000,
    @Doc("After a match, keep waiting until the UI is quiet for this many milliseconds.")
    val settleMs: Long = 0,
    @Doc("Include a fresh UI snapshot in the result.") val snapshot: Boolean = false,
)

private const val MAX_PNG_BYTES = 1_500_000
private const val MAX_LOG_LINES = 400
