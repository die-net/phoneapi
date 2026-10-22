package net.die.phoneapi.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.die.phoneapi.model.BrowserTarget

/**
 * Chrome's tabs from `Target.getTargets`, which, unlike `/json/list`, uses the same ids as
 * `Target.createTarget` and reports which tab opened a popup. Tabs Chrome has unloaded can be
 * missing.
 */
internal fun chromeTargets(
    result: JsonObject,
    socket: String,
    pid: Int?,
    packageName: String?,
): List<BrowserTarget> {
    val infos = result.decodeCdp<TargetInfos>()?.targetInfos ?: return emptyList()
    return infos.mapNotNull {
        if (it.type != "page") return@mapNotNull null
        BrowserTarget(
            id = browserTargetId(socket, it.targetId),
            type = it.type,
            title = it.title?.takeIf { title -> title.isNotBlank() },
            url = it.url?.takeIf { url -> url.isNotBlank() },
            socket = socket,
            pid = pid,
            packageName = packageName,
            openerId = it.openerId?.let { opener -> browserTargetId(socket, opener) },
        )
    }
}

@Serializable private data class TargetInfos(val targetInfos: List<TargetInfo> = emptyList())

@Serializable
private data class TargetInfo(
    val targetId: String,
    val type: String = "page",
    val title: String? = null,
    val url: String? = null,
    val openerId: String? = null,
)
