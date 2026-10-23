package net.die.phoneapi.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.EvalResult

/**
 * Evaluates [expression] in an isolated world on the page's main frame, so the page's own
 * JavaScript neither sees nor changes it. A script exception comes back as [EvalResult.exception].
 */
internal suspend fun evaluateIn(
    cdp: CdpSession,
    expression: String,
    awaitPromise: Boolean,
): EvalResult {
    val loaded = cdp.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()
    val frameId =
        loaded?.frameTree?.frame?.id
            ?: throw ApiException(502, "cdp_error", "Chrome did not return a frame")
    return evaluateInFrame(cdp, frameId, expression, awaitPromise)
}

/** [evaluateIn] for a caller that already has the main frame's id. */
internal suspend fun evaluateInFrame(
    cdp: CdpSession,
    frameId: String,
    expression: String,
    awaitPromise: Boolean,
): EvalResult = evaluateInWorld(cdp, isolatedWorld(cdp, frameId), expression, awaitPromise)

/** A new isolated world on the page's main frame, by its execution context id. */
internal suspend fun isolatedWorld(cdp: CdpSession): Int {
    val loaded = cdp.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()
    val frameId =
        loaded?.frameTree?.frame?.id
            ?: throw ApiException(502, "cdp_error", "Chrome did not return a frame")
    return isolatedWorld(cdp, frameId)
}

private suspend fun isolatedWorld(cdp: CdpSession, frameId: String): Int =
    cdp.call(
            "Page.createIsolatedWorld",
            buildJsonObject {
                put("frameId", frameId)
                put("worldName", ISOLATED_WORLD)
            },
        )
        .decodeCdp<WorldResult>()
        ?.executionContextId
        ?: throw ApiException(502, "cdp_error", "Chrome did not return an isolated world")

/** Evaluates [expression] in the isolated world [contextId]. */
internal suspend fun evaluateInWorld(
    cdp: CdpSession,
    contextId: Int,
    expression: String,
    awaitPromise: Boolean,
): EvalResult {
    return evalResult(
        cdp.call(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("contextId", contextId)
                put("returnByValue", true)
                put("awaitPromise", awaitPromise)
            },
        )
    )
}

private fun evalResult(payload: JsonObject): EvalResult {
    val parsed = payload.decodeCdp<EvalPayload>() ?: return EvalResult()
    val details = parsed.exceptionDetails
    if (details != null) return EvalResult(exception = exceptionMessage(details))
    val remote = parsed.result ?: return EvalResult()
    return EvalResult(type = remote.type, value = remote.value)
}

private fun exceptionMessage(details: ExceptionText): String {
    val description = details.exception?.description
    val described = description?.lineSequence()?.firstOrNull()?.trim()
    if (!described.isNullOrEmpty()) return described
    val value = details.exception?.value
    if (value is JsonPrimitive && value.isString) return value.content
    return details.text ?: "exception"
}

@Serializable private data class WorldResult(val executionContextId: Int? = null)

@Serializable
private data class EvalPayload(
    val result: RemoteObject? = null,
    val exceptionDetails: ExceptionText? = null,
)

@Serializable
private data class RemoteObject(val type: String? = null, val value: JsonElement? = null)

@Serializable
private data class ExceptionText(
    val text: String? = null,
    val exception: ExceptionObject? = null,
)

@Serializable
private data class ExceptionObject(
    val description: String? = null,
    val value: JsonElement? = null,
)

private const val ISOLATED_WORLD = "phoneapi"
