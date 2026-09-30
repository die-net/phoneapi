package net.die.phoneapi.browser

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.Rect

/**
 * Maps a snapshot ref onto a screen rectangle. Letter-sized `StaticText` nodes are Chrome splitting
 * one element, so the tap uses the enclosing element's box. Quads are CSS pixels in the document;
 * the visual viewport turns them into pixels inside [content], the WebView's bounds on screen.
 */
internal fun elementBackendId(tree: JsonObject, ref: String): Int {
    val node =
        enclosing(AxIndex(tree), ref) ?: throw ApiException.notFound("No browser node '$ref'")
    return node.backendId()
        ?: throw ApiException.notFound("Browser node '$ref' has no box on the page")
}

private fun enclosing(index: AxIndex, ref: String): JsonObject? {
    var id = ref
    var node = index.node(id) ?: return null
    repeat(MAX_HOPS) {
        if (!textRole(node)) return node
        val parent = nonRootParent(index, id) ?: return node
        id = parent.first
        node = parent.second
    }
    return node
}

private fun nonRootParent(index: AxIndex, id: String): Pair<String, JsonObject>? {
    val up = index.parent(id) ?: return null
    val parent = index.node(up) ?: return null
    if (rootRole(parent)) return null
    return up to parent
}

internal fun screenTarget(quads: JsonArray, metrics: JsonObject, content: Rect): Rect {
    val viewport = viewport(metrics)
    val scaleX = viewport.deviceWidth / viewport.cssWidth
    val scaleY = viewport.deviceHeight / viewport.cssHeight
    var best: Rect? = null
    var bestArea = 0
    for (element in quads) {
        val visible = placed(element as? JsonArray, viewport, content, scaleX, scaleY) ?: continue
        val area = (visible.right - visible.left) * (visible.bottom - visible.top)
        if (area > bestArea) {
            best = visible
            bestArea = area
        }
    }
    return best ?: throw ApiException(409, "offscreen", "The node is outside the page on screen")
}

private fun placed(
    quad: JsonArray?,
    viewport: Viewport,
    content: Rect,
    scaleX: Double,
    scaleY: Double,
): Rect? {
    val box = quad?.let { box(it, viewport, content, scaleX, scaleY) } ?: return null
    return intersection(box, content)
}

private fun box(
    quad: JsonArray,
    viewport: Viewport,
    content: Rect,
    scaleX: Double,
    scaleY: Double,
): Rect? {
    if (quad.size != QUAD_COORDS) return null
    val coords = quad.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
    val xs =
        (0 until QUAD_COORDS step 2).map { i ->
            content.left + ((coords[i] - viewport.pageX) * scaleX).roundToInt()
        }
    val ys =
        (1 until QUAD_COORDS step 2).map { i ->
            content.top + ((coords[i] - viewport.pageY) * scaleY).roundToInt()
        }
    val left = xs.min()
    val top = ys.min()
    val right = xs.max()
    val bottom = ys.max()
    if (right - left < MIN_EDGE || bottom - top < MIN_EDGE) return null
    return Rect(left, top, right, bottom)
}

private fun intersection(a: Rect, b: Rect): Rect? {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    if (right - left < MIN_EDGE || bottom - top < MIN_EDGE) return null
    return Rect(left, top, right, bottom)
}

private fun positive(vararg values: Double): Boolean = values.all { it > 0.0 }

private fun viewport(metrics: JsonObject): Viewport {
    val css = metrics.obj("cssVisualViewport") ?: metrics.obj("cssLayoutViewport")
    val device = metrics.obj("visualViewport") ?: metrics.obj("layoutViewport")
    val cssWidth = css?.num("clientWidth") ?: 0.0
    val cssHeight = css?.num("clientHeight") ?: 0.0
    val deviceWidth = device?.num("clientWidth") ?: 0.0
    val deviceHeight = device?.num("clientHeight") ?: 0.0
    if (!positive(cssWidth, cssHeight, deviceWidth, deviceHeight)) {
        throw ApiException(502, "cdp_error", "Chrome did not return viewport metrics")
    }
    return Viewport(
        pageX = css?.num("pageX") ?: 0.0,
        pageY = css?.num("pageY") ?: 0.0,
        cssWidth = cssWidth,
        cssHeight = cssHeight,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
    )
}

private data class Viewport(
    val pageX: Double,
    val pageY: Double,
    val cssWidth: Double,
    val cssHeight: Double,
    val deviceWidth: Double,
    val deviceHeight: Double,
)

private class AxIndex(tree: JsonObject) {
    private val byId = HashMap<String, JsonObject>()
    private val parentOf = HashMap<String, String>()

    init {
        val nodes = tree["nodes"] as? JsonArray ?: JsonArray(emptyList())
        nodes.forEach(::add)
    }

    private fun add(element: JsonElement) {
        val node = element as? JsonObject ?: return
        val id = node.string("nodeId") ?: return
        byId[id] = node
        val children = node["childIds"] as? JsonArray ?: return
        for (child in children) parentId(id, child)
    }

    private fun parentId(id: String, child: JsonElement) {
        val childId = (child as? JsonPrimitive)?.contentOrNull ?: return
        parentOf[childId] = id
    }

    fun node(id: String): JsonObject? = byId[id]

    fun parent(id: String): String? = parentOf[id]
}

private fun textRole(node: JsonObject): Boolean = node.stringValue("role") in TEXT_ROLES

private fun rootRole(node: JsonObject): Boolean = node.stringValue("role") == "RootWebArea"

private fun JsonObject.backendId(): Int? {
    val value = this["backendDOMNodeId"] as? JsonPrimitive ?: return null
    return value.doubleOrNull?.toInt()
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.num(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringValue(name: String): String? {
    val value = (this[name] as? JsonObject)?.get("value") ?: return null
    return (value as? JsonPrimitive)?.contentOrNull
}

private val TEXT_ROLES = setOf("StaticText", "InlineTextBox")

private const val QUAD_COORDS = 8
private const val MIN_EDGE = 2
private const val MAX_HOPS = 40
