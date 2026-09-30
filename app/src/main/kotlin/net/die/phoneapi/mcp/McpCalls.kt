package net.die.phoneapi.mcp

import android.util.Base64
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.model.ActionMode
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.FindRequest
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
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.wait.isBrowserCondition

internal suspend fun deviceInfo(graph: AppGraph): CallToolResult = runTool {
    jsonText(graph.deviceInfo.info())
}

internal suspend fun uiSnapshot(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<SnapshotArgs>()
        val snapshot =
            graph.ui.snapshot(
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

internal suspend fun uiFind(graph: AppGraph, request: CallToolRequest): CallToolResult = runTool {
    jsonText(graph.ui.find(request.args<FindRequest>()))
}

internal suspend fun uiAct(graph: AppGraph, request: CallToolRequest): CallToolResult = runTool {
    val args = request.args<NodeActArgs>()
    jsonText(
        graph.ui.act(
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

internal suspend fun tap(graph: AppGraph, request: CallToolRequest): CallToolResult = runTool {
    jsonText(graph.input.tap(request.args<TapRequest>()))
}

internal suspend fun swipe(graph: AppGraph, request: CallToolRequest): CallToolResult = runTool {
    jsonText(graph.input.swipe(request.args<SwipeRequest>()))
}

internal suspend fun typeText(graph: AppGraph, request: CallToolRequest): CallToolResult = runTool {
    jsonText(graph.input.text(request.args<TextRequest>()))
}

internal suspend fun pressKey(graph: AppGraph, request: CallToolRequest): CallToolResult = runTool {
    jsonText(graph.input.key(request.args<KeyRequest>()))
}

internal suspend fun waitFor(
    graph: AppGraph,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<WaitRequest>().copy(snapshotFormat = SnapshotFormat.COMPACT)
    if ((args.all + args.any).any(::isBrowserCondition) && Scope.BROWSER !in scopes) {
        throw ApiException.badRequest("Browser wait conditions need the browser scope")
    }
    val result = graph.waits.wait(args)
    val header =
        "matched=${result.matched} timedOut=${result.timedOut} elapsedMs=${result.elapsedMs}"
    val body = result.snapshot?.compact
    if (body.isNullOrBlank()) header else "$header\n$body"
}

internal suspend fun keyboardHide(graph: AppGraph): CallToolResult = runTool {
    jsonText(graph.input.hideIme())
}

internal suspend fun appLaunch(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<PackageArgs>()
        jsonText(
            graph.apps.launch(
                args.packageName,
                LaunchRequest(fresh = args.fresh, activity = args.activity, wait = args.wait),
            )
        )
    }

internal suspend fun appStop(graph: AppGraph, request: CallToolRequest): CallToolResult = runTool {
    jsonText(graph.apps.stop(request.args<PackageArgs>().packageName))
}

internal suspend fun appClear(graph: AppGraph, request: CallToolRequest): CallToolResult = runTool {
    jsonText(graph.apps.clear(request.args<PackageArgs>().packageName))
}

internal suspend fun openIntent(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(graph.apps.intent(request.args<IntentRequest>()))
    }

internal suspend fun deviceWake(graph: AppGraph): CallToolResult = runTool {
    jsonText(graph.requirePower().wake())
}

internal suspend fun deviceUnlock(graph: AppGraph): CallToolResult = runTool {
    jsonText(graph.requirePower().unlock(UnlockRequest()))
}

internal suspend fun deviceLock(graph: AppGraph): CallToolResult = runTool {
    jsonText(graph.requirePower().lock())
}

internal suspend fun browserTargets(graph: AppGraph): CallToolResult = runTool {
    jsonText(graph.browser.targets())
}

internal suspend fun browserOpen(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(graph.browser.openTab(request.args<UrlArgs>().url))
    }

internal suspend fun browserNavigate(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<TargetUrlArgs>()
        jsonText(graph.browser.navigate(args.target, args.url))
    }

internal suspend fun browserSnapshot(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        val snapshot = graph.browser.snapshot(request.args<TargetArgs>().target)
        snapshot.compact
    }

internal suspend fun browserTap(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<BrowserTapArgs>()
        val woke = graph.prepareForAction(args.autoWake)
        val result =
            graph.browser.tap(
                args.target,
                BrowserTapRequest(
                    ref = args.ref,
                    selector = args.selector,
                    humanize = args.humanize,
                    autoWake = args.autoWake,
                ),
            )
        jsonText(result.copy(woke = woke, seq = graph.uiTracker.seq.value))
    }

internal suspend fun browserEval(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<EvalArgs>()
        jsonText(
            graph.browser.evaluate(args.target, EvalRequest(args.expression, args.awaitPromise))
        )
    }

internal suspend fun browserConsole(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<ConsoleArgs>()
        jsonText(graph.browser.console(args.target, ConsoleRequest(args.timeoutMs)))
    }

internal suspend fun screenshot(graph: AppGraph, request: CallToolRequest): CallToolResult =
    try {
        val scale = request.args<ScaleArgs>().scale
        if (scale !in MIN_SCALE..MAX_SCALE) {
            throw ApiException.badRequest("scale must be $MIN_SCALE..$MAX_SCALE")
        }
        val png = graph.screenshots.png(scale)
        if (png.size > MAX_PNG_BYTES) {
            throw ApiException.badRequest("Screenshot is too large; pass a smaller scale")
        }
        val data = Base64.encodeToString(png, Base64.NO_WRAP)
        CallToolResult(content = listOf(ImageContent(data = data, mimeType = "image/png")))
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        toolFailure(toolError(e))
    } catch (e: SerializationException) {
        toolFailure("bad_request: ${e.message.orEmpty()}")
    }

internal suspend fun logcatTail(graph: AppGraph, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<LogcatArgs>()
        if (args.lines !in 1..MAX_LOG_LINES) {
            throw ApiException.badRequest("lines must be 1..$MAX_LOG_LINES")
        }
        val tag = args.tag
        if (tag != null && !LOG_TAG.matches(tag)) {
            throw ApiException.badRequest("tag must be letters, digits, dots, or underscores")
        }
        val argv = mutableListOf("logcat", "-d", "-t", args.lines.toString())
        if (tag != null) argv += "$tag:I"
        val result = graph.shell.exec(argv)
        if (!result.ok)
            throw ApiException(503, "helper_error", result.stderr.ifBlank { result.stdout })
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

private fun windowId(value: String): Int =
    value.removePrefix("w").toIntOrNull()
        ?: throw ApiException.badRequest("window must be a window id like 3 or w3")

@Serializable
private data class SnapshotArgs(
    val window: String? = null,
    val maxDepth: Int? = null,
    val includeInvisible: Boolean = false,
    val all: Boolean = false,
    val autoWake: Boolean = true,
)

@Serializable
private data class NodeActArgs(
    val ref: String,
    val action: String,
    val mode: ActionMode = ActionMode.REAL,
    val text: String? = null,
    val force: Boolean = false,
    val autoWake: Boolean = true,
)

@Serializable
private data class PackageArgs(
    @SerialName("package") val packageName: String,
    val fresh: Boolean = false,
    val activity: String? = null,
    val wait: Boolean = true,
)

@Serializable private data class UrlArgs(val url: String)

@Serializable private data class TargetArgs(val target: String)

@Serializable private data class TargetUrlArgs(val target: String, val url: String)

@Serializable
private data class BrowserTapArgs(
    val target: String,
    val ref: String? = null,
    val selector: String? = null,
    val humanize: Boolean = true,
    val autoWake: Boolean = true,
)

@Serializable
private data class EvalArgs(
    val target: String,
    val expression: String,
    val awaitPromise: Boolean = false,
)

@Serializable private data class ConsoleArgs(val target: String, val timeoutMs: Long = 1_000)

@Serializable private data class ScaleArgs(val scale: Float = 0.5f)

@Serializable private data class LogcatArgs(val lines: Int = 80, val tag: String? = null)

private val LOG_TAG = Regex("[A-Za-z0-9._-]{1,80}")
private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 1f
private const val MAX_PNG_BYTES = 1_500_000
private const val MAX_LOG_LINES = 400
