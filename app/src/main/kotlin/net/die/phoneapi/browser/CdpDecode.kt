package net.die.phoneapi.browser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import net.die.phoneapi.core.ApiJson

internal inline fun <reified T> JsonElement.decodeCdp(): T? =
    try {
        ApiJson.decodeFromJsonElement<T>(this)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
