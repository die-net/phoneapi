package net.die.phoneapi.browser

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.input.CdpContact
import net.die.phoneapi.input.TouchPhase
import net.die.phoneapi.input.TouchSample
import net.die.phoneapi.input.touchTimeline
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.TimedPoint

/** Plays pointer paths on the open page with `Input.dispatchTouchEvent`. */
internal suspend fun dispatchTouchPath(
    cdp: CdpSession,
    pointers: List<List<TimedPoint>>,
    contact: CdpContact,
) {
    val samples = touchTimeline(pointers)
    val active = LinkedHashMap<Int, Point>()
    var last = 0L
    var index = 0
    while (index < samples.size) {
        val at = samples[index].tMs
        val wait = at - last
        if (wait > 0) delay(wait)
        last = at
        val batch = ArrayList<TouchSample>()
        while (index < samples.size && samples[index].tMs == at) {
            batch += samples[index]
            index++
        }
        playBatch(cdp, active, batch, contact)
    }
}

/** `Input.insertText`, with an optional select-all clear and Enter. */
internal suspend fun dispatchInsertText(
    cdp: CdpSession,
    text: String,
    clear: Boolean,
    submit: Boolean,
) {
    if (clear) {
        dispatchDomKey(cdp, domKey("A", CTRL), cdpModifiers(CTRL), longPress = false)
        dispatchDomKey(cdp, domKey("DEL", 0), modifiers = 0, longPress = false)
    }
    if (text.isNotEmpty()) {
        cdp.call("Input.insertText", buildJsonObject { put("text", text) })
    }
    if (submit) {
        dispatchDomKey(cdp, domKey("ENTER", 0), modifiers = 0, longPress = false)
    }
}

internal suspend fun dispatchDomKey(
    cdp: CdpSession,
    key: DomKey,
    modifiers: Int,
    longPress: Boolean,
) {
    val withText = key.text != null && modifiers == 0
    val down = if (withText) "keyDown" else "rawKeyDown"
    cdp.call("Input.dispatchKeyEvent", keyEvent(down, key, modifiers, withText))
    if (longPress) delay(LONG_PRESS_MS)
    cdp.call("Input.dispatchKeyEvent", keyEvent("keyUp", key, modifiers, withText = false))
}

private suspend fun playBatch(
    cdp: CdpSession,
    active: LinkedHashMap<Int, Point>,
    batch: List<TouchSample>,
    contact: CdpContact,
) {
    val starting = batch.filter { it.phase == TouchPhase.DOWN }
    val moving = batch.filter { it.phase == TouchPhase.MOVE }
    val ending = batch.filter { it.phase == TouchPhase.UP }
    val wasEmpty = active.isEmpty()
    for (sample in starting) active[sample.pointer] = Point(sample.x, sample.y)
    if (starting.isNotEmpty()) {
        val points =
            if (wasEmpty) active.entries.map { it.key to it.value }
            else starting.map { it.pointer to Point(it.x, it.y) }
        sendTouch(cdp, "touchStart", points, contact)
    }
    for (sample in moving) active[sample.pointer] = Point(sample.x, sample.y)
    if (moving.isNotEmpty()) {
        sendTouch(cdp, "touchMove", active.entries.map { it.key to it.value }, contact)
    }
    for (sample in ending) active.remove(sample.pointer)
    if (ending.isEmpty()) return
    if (active.isEmpty()) sendTouch(cdp, "touchEnd", emptyList(), contact)
    else sendTouch(cdp, "touchMove", active.entries.map { it.key to it.value }, contact)
}

private suspend fun sendTouch(
    cdp: CdpSession,
    type: String,
    points: List<Pair<Int, Point>>,
    contact: CdpContact,
) {
    cdp.call(
        "Input.dispatchTouchEvent",
        buildJsonObject {
            put("type", type)
            put("touchPoints", JsonArray(points.map { touchPoint(it.first, it.second, contact) }))
        },
    )
}

private fun touchPoint(id: Int, point: Point, contact: CdpContact): JsonObject = buildJsonObject {
    put("x", point.x)
    put("y", point.y)
    put("radiusX", contact.radiusX)
    put("radiusY", contact.radiusY)
    put("rotationAngle", contact.rotationAngle)
    put("force", contact.force)
    put("id", id)
}

private fun keyEvent(type: String, key: DomKey, modifiers: Int, withText: Boolean): JsonObject =
    buildJsonObject {
        put("type", type)
        put("key", key.key)
        put("code", key.code)
        put("windowsVirtualKeyCode", key.virtualKey)
        put("nativeVirtualKeyCode", key.virtualKey)
        put("modifiers", modifiers)
        if (withText && key.text != null) {
            put("text", key.text)
            put("unmodifiedText", key.text)
        }
    }

/** Android META_CTRL_ON, so select-all does not depend on [android.view.KeyEvent]. */
private const val CTRL = 4_096

private const val LONG_PRESS_MS = 400L
