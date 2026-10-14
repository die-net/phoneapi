package net.die.phoneapi.helper.tree

import net.die.phoneapi.model.UiNode
import net.die.phoneapi.model.UiWindow

/**
 * Masks secrets in snapshot windows: the text of password fields and one-time-code fields, and
 * anything that looks like a card number. This keeps secrets out of an agent's context in the
 * outline. It is not a security boundary, since a screenshot still shows them.
 */
internal class Redactor {
    /** How many values were masked. */
    var count = 0
        private set

    fun windows(windows: List<UiWindow>): List<UiWindow> = windows.map { window ->
        window.root?.let { window.copy(root = node(it)) } ?: window
    }

    private fun node(node: UiNode): UiNode {
        val children = node.children.map(::node)
        val text = node.text?.let { text(node, it) }
        val desc = node.desc?.let(::cards)
        if (text == node.text && desc == node.desc && children == node.children) return node
        return node.copy(text = text, desc = desc, children = children)
    }

    private fun text(node: UiNode, text: String): String {
        val secret = UiStates.PASSWORD in node.states || codeField(node)
        if (!secret) return cards(text)
        if (text.isBlank() || text == node.hint) return text
        count++
        return MASK
    }

    private fun cards(text: String): String =
        CARD.replace(text) { match ->
            if (luhn(match.value.filter(Char::isDigit))) {
                count++
                CARD_MASK
            } else {
                match.value
            }
        }

    private fun codeField(node: UiNode): Boolean {
        if (UiStates.EDITABLE !in node.states && node.role != "edit") return false
        return listOfNotNull(node.id, node.hint, node.desc).any { CODE_FIELD.containsMatchIn(it) }
    }

    companion object {
        const val MASK = "[redacted]"
        const val CARD_MASK = "[card number]"
        private val CARD = Regex("""(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)""")
        private val CODE_FIELD =
            Regex(
                """otp|one[ _-]?time|passcode|2fa|mfa|""" +
                    """(verification|security|confirmation|sign[ _-]?in|login|access|auth\w*|sms)[ _-]?code""",
                RegexOption.IGNORE_CASE,
            )

        /** Whether [digits] passes the Luhn check card numbers carry. */
        fun luhn(digits: String): Boolean {
            var sum = 0
            for ((i, ch) in digits.reversed().withIndex()) {
                var d = ch - '0'
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }
    }
}
