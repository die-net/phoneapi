package net.die.phoneapi.power

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import net.die.phoneapi.a11y.NodeCompat
import net.die.phoneapi.a11y.toModel
import net.die.phoneapi.model.Rect as ModelRect

/** The digit keys (and submit key, if the keyguard shows one) of a PIN bouncer. */
internal data class Keypad(val digits: Map<Char, ModelRect>, val submit: ModelRect?)

/** Whether every digit of [pin] has a key to tap. */
internal fun Keypad.covers(pin: String): Boolean = pin.isNotEmpty() && pin.all { it in digits }

/** Reads the lock screen's PIN keypad out of the accessibility tree. */
internal object PinPad {
    private const val MAX_NODES = 400
    private const val MIN_KEY_PX = 16

    /** The keypad currently on screen; empty when the bouncer isn't showing. Blocking IPC. */
    fun scan(service: AccessibilityService): Keypad {
        val windows = service.windows
        try {
            val digits = HashMap<Char, ModelRect>()
            var submit: ModelRect? = null
            for (window in windows.sortedByDescending { it.layer }) {
                val root = window.root ?: continue
                for (key in collect(root)) {
                    val digit = PinKeys.digitOf(key.id, key.text, key.desc)
                    if (digit != null) digits.putIfAbsent(digit, key.bounds)
                    else if (submit == null && PinKeys.isSubmit(key.id, key.text, key.desc)) {
                        submit = key.bounds
                    }
                }
            }
            return Keypad(digits, submit)
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private data class Candidate(
        val id: String?,
        val text: String?,
        val desc: String?,
        val bounds: ModelRect,
    )

    private fun collect(root: AccessibilityNodeInfo): List<Candidate> {
        val out = ArrayList<Candidate>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val bounds = Rect()
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (node.isVisibleToUser) {
                if (node.isClickable) {
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() >= MIN_KEY_PX && bounds.height() >= MIN_KEY_PX) {
                        out +=
                            Candidate(
                                id = node.viewIdResourceName,
                                text = node.text?.toString(),
                                desc = node.contentDescription?.toString(),
                                bounds = bounds.toModel(),
                            )
                    }
                }
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
            }
            NodeCompat.recycle(node)
        }
        stack.forEach(NodeCompat::recycle)
        return out
    }
}

/**
 * Classifies lock-screen keys from their resource id and labels. AOSP's bouncer uses
 * `com.android.systemui:id/key1`..`key0` with the digit as the content description; OEM skins
 * usually keep the labels even when the ids differ, and some add a letter group ("2 ABC").
 */
internal object PinKeys {
    private val ID_DIGIT = Regex("""key([0-9])$""")
    private val LABEL_DIGIT = Regex("""^([0-9])(\s.*)?$""")
    private val SUBMIT_LABELS = setOf("enter", "ok", "done", "submit", "confirm", "unlock")

    /** The digit a key enters, or null when it isn't a digit key. */
    fun digitOf(id: String?, text: String?, desc: String?): Char? {
        val byId = id?.substringAfterLast('/')?.let(ID_DIGIT::find)
        if (byId != null) return byId.groupValues[1][0]
        for (label in listOfNotNull(desc, text)) {
            val byLabel = LABEL_DIGIT.find(label.trim())
            if (byLabel != null) return byLabel.groupValues[1][0]
        }
        return null
    }

    fun isSubmit(id: String?, text: String?, desc: String?): Boolean {
        val name = id?.substringAfterLast('/')
        if (name == "key_enter" || name == "eca_submit") return true
        return listOfNotNull(desc, text).any { it.trim().lowercase() in SUBMIT_LABELS }
    }
}
