package net.die.phoneapi.power

import net.die.phoneapi.model.Rect as ModelRect

/** The digit keys (and submit key, if the keyguard shows one) of a PIN bouncer. */
internal data class Keypad(val digits: Map<Char, ModelRect>, val submit: ModelRect?)

/** Whether every digit of [pin] has a key to tap. */
internal fun Keypad.covers(pin: String): Boolean = pin.isNotEmpty() && pin.all { it in digits }

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
