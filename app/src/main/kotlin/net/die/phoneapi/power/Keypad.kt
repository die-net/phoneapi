package net.die.phoneapi.power

import net.die.phoneapi.model.Rect as ModelRect

/** The digit keys (and submit key, if the keyguard shows one) of a PIN bouncer. */
internal data class Keypad(val digits: Map<Char, ModelRect>, val submit: ModelRect?)

/** Whether every digit of [pin] has a key to tap. */
internal fun Keypad.covers(pin: String): Boolean = pin.isNotEmpty() && pin.all { it in digits }
