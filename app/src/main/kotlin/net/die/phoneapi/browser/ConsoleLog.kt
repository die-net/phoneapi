package net.die.phoneapi.browser

import java.util.Collections
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.die.phoneapi.model.ConsoleEntry
import net.die.phoneapi.model.ConsoleResult

/**
 * Collects a page's console and browser log entries for [timeoutMs], skipping the replayed ones.
 */
internal suspend fun collectLogs(session: CdpSession, timeoutMs: Long): ConsoleResult {
    val entries = Collections.synchronizedList(mutableListOf<ConsoleEntry>())
    coroutineScope {
        val job = launch { session.events.collect { event -> appendLog(entries, event) } }
        try {
            session.call("Runtime.enable")
            session.call("Log.enable")
            // Enabling replays Chrome's buffer. The wait starts after that.
            synchronized(entries) { entries.clear() }
            delay(timeoutMs)
        } finally {
            job.cancel()
        }
    }
    return ConsoleResult(synchronized(entries) { entries.toList() })
}

private fun appendLog(entries: MutableList<ConsoleEntry>, event: CdpEvent) {
    when (event.method) {
        "Runtime.consoleAPICalled" -> {
            val call = event.params.decodeCdp<ConsoleCall>() ?: return
            val text = consoleText(call.args) ?: return
            entries += ConsoleEntry(call.type ?: "log", text)
        }
        "Log.entryAdded" -> {
            val entry = event.params.decodeCdp<LogAdded>()?.entry ?: return
            // console.log also arrives as Runtime.consoleAPICalled.
            if (entry.source == "console-api") return
            val text = entry.text ?: return
            entries += ConsoleEntry(entry.level ?: "info", text)
        }
    }
}

private fun consoleText(args: List<ConsoleArg>): String? {
    val text = args.mapNotNull(::argText).joinToString(" ").trim()
    return text.ifEmpty { null }
}

private fun argText(arg: ConsoleArg): String? {
    val value = arg.value
    if (value is JsonPrimitive) return value.contentOrNull
    return arg.unserializableValue ?: arg.description
}

@Serializable private data class LogAdded(val entry: LogLine? = null)

@Serializable
private data class LogLine(
    val text: String? = null,
    val level: String? = null,
    val source: String? = null,
)

@Serializable
private data class ConsoleCall(
    val type: String? = null,
    val args: List<ConsoleArg> = emptyList(),
)

@Serializable
private data class ConsoleArg(
    val value: JsonElement? = null,
    val description: String? = null,
    val unserializableValue: String? = null,
)
