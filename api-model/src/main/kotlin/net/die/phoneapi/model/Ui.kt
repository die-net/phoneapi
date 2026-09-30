package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class UiNode(
    /** Short handle (`e12`) valid until the node disappears; pass back to actions. */
    val ref: String,
    /** Simplified role derived from className / roleDescription, e.g. `button`, `text`, `edit`. */
    val role: String,
    val className: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    /** Resource id without the package prefix when it matches the window's package. */
    val id: String? = null,
    val bounds: Rect,
    /**
     * e.g. `clickable`, `checked`, `selected`, `focused`, `disabled`, `editable`, `password`,
     * `scrollable`, `obscured`.
     */
    val states: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val children: List<UiNode> = emptyList(),
)

@Serializable
public data class UiWindow(
    val id: Int,
    /**
     * `application`, `input_method`, `system`, `accessibility_overlay`, `split_divider`,
     * `magnification`.
     */
    val type: String,
    val title: String? = null,
    @SerialName("package") val packageName: String? = null,
    val layer: Int,
    val focused: Boolean,
    val active: Boolean,
    val bounds: Rect,
    val root: UiNode? = null,
)

@Serializable
public data class UiSnapshot(
    /** Monotonic counter bumped on every observed UI change. */
    val seq: Long,
    val timestampMs: Long,
    val state: DeviceStateSummary,
    val windows: List<UiWindow>,
    /** Token-efficient outline, present when `format=compact`. */
    val compact: String? = null,
)

@Serializable
public enum class SnapshotFormat {
    @SerialName("compact") COMPACT,
    @SerialName("json") JSON,
    @SerialName("both") BOTH,
}

/** All non-null fields must match. String matches are case-insensitive unless noted. */
@Serializable
public data class NodeSelector(
    val ref: String? = null,
    val text: String? = null,
    val textContains: String? = null,
    /** Case-sensitive regular expression over text, falling back to content description. */
    val textRegex: String? = null,
    val desc: String? = null,
    val descContains: String? = null,
    val id: String? = null,
    val role: String? = null,
    @SerialName("package") val packageName: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    /** Pick the n-th match (0-based) when several nodes match. */
    val index: Int? = null,
)

@Serializable
public data class FindRequest(
    val selector: NodeSelector,
    val limit: Int = 20,
    val includeInvisible: Boolean = false,
)

@Serializable public data class FindResult(val seq: Long, val matches: List<UiNode>)

@Serializable
public enum class ActionMode {
    /** Perform the action with real touch input at the node's bounds where possible. */
    @SerialName("real") REAL,
    /** Use AccessibilityNodeInfo.performAction directly (no touch events are generated). */
    @SerialName("semantic") SEMANTIC,
}

@Serializable
public data class NodeActionRequest(
    /**
     * `click`, `longClick`, `focus`, `clearFocus`, `select`, `setText`, `scrollForward`,
     * `scrollBackward`, `expand`, `collapse`, `dismiss`, `showOnScreen`, `imeEnter`.
     */
    val action: String,
    val mode: ActionMode = ActionMode.REAL,
    val text: String? = null,
    val force: Boolean = false,
    val autoWake: Boolean = true,
)
