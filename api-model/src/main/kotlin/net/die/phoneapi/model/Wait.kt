package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Polymorphic on the `type` field. Browser conditions are evaluated against a CDP target. */
@Serializable
public sealed interface WaitCondition {
    @Serializable
    @SerialName("node")
    public data class Node(
        val selector: NodeSelector,
        /**
         * `present`, `absent`, `visible`, `enabled`, `disabled`, `checked`, `unchecked`, `focused`,
         * `selected`.
         */
        val state: String = "present",
    ) : WaitCondition

    @Serializable
    @SerialName("window")
    public data class Window(
        @SerialName("package") val packageName: String? = null,
        val titleContains: String? = null,
    ) : WaitCondition

    @Serializable @SerialName("ime") public data class Ime(val visible: Boolean) : WaitCondition

    /** No UI content changes for [quietMs]. */
    @Serializable
    @SerialName("idle")
    public data class Idle(val quietMs: Long = 500) : WaitCondition

    @Serializable @SerialName("screen") public data class Screen(val on: Boolean) : WaitCondition

    @Serializable
    @SerialName("keyguard")
    public data class Keyguard(val locked: Boolean) : WaitCondition

    @Serializable
    @SerialName("browser.url")
    public data class BrowserUrl(
        val target: String? = null,
        val equals: String? = null,
        val contains: String? = null,
        val regex: String? = null,
    ) : WaitCondition

    /**
     * `DOMContentLoaded`, `load`, `networkAlmostIdle`, `networkIdle`, `firstMeaningfulPaint`, ...
     */
    @Serializable
    @SerialName("browser.lifecycle")
    public data class BrowserLifecycle(val target: String? = null, val name: String) : WaitCondition

    @Serializable
    @SerialName("browser.networkIdle")
    public data class BrowserNetworkIdle(
        val target: String? = null,
        val maxInflight: Int = 0,
        val quietMs: Long = 500,
    ) : WaitCondition

    @Serializable
    @SerialName("browser.element")
    public data class BrowserElement(
        val target: String? = null,
        /** CSS selector, or XPath / plain text when [by] says so. */
        val selector: String,
        /** `css`, `xpath`, `text`. */
        val by: String = "css",
        /** `present`, `absent`, `visible`. */
        val state: String = "present",
    ) : WaitCondition

    @Serializable
    @SerialName("browser.request")
    public data class BrowserRequest(
        val target: String? = null,
        val urlContains: String? = null,
        val urlRegex: String? = null,
        val method: String? = null,
        val status: Int? = null,
    ) : WaitCondition

    @Serializable
    @SerialName("browser.dialog")
    public data class BrowserDialog(val target: String? = null) : WaitCondition

    @Serializable
    @SerialName("browser.newTarget")
    public data class BrowserNewTarget(val urlContains: String? = null) : WaitCondition

    @Serializable
    @SerialName("browser.log")
    public data class BrowserLog(
        val target: String? = null,
        val level: String? = null,
        val textContains: String? = null,
    ) : WaitCondition

    /** Layer tree unchanged for [quietMs] and no requests in flight. */
    @Serializable
    @SerialName("browser.settled")
    public data class BrowserSettled(val target: String? = null, val quietMs: Long = 500) :
        WaitCondition
}

@Serializable
public data class WaitRequest(
    val all: List<WaitCondition> = emptyList(),
    val any: List<WaitCondition> = emptyList(),
    val timeoutMs: Long = 10_000,
    /** After the conditions match, keep waiting until the UI is quiet for this long. */
    val settleMs: Long = 0,
    /** Include a fresh UI snapshot in the result. */
    val snapshot: Boolean = false,
    val snapshotFormat: SnapshotFormat = SnapshotFormat.COMPACT,
)

@Serializable
public data class WaitResult(
    val matched: Boolean,
    val timedOut: Boolean,
    val elapsedMs: Long,
    /** Indexes of satisfied conditions within `all` / `any`. */
    val matchedAll: List<Int> = emptyList(),
    val matchedAny: List<Int> = emptyList(),
    val snapshot: UiSnapshot? = null,
    val state: DeviceStateSummary? = null,
)
