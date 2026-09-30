package net.die.phoneapi.input

import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyMatcherTest {
    private fun key(label: String, id: String? = null) = ImeKey(label, id, Rect(0, 0, 10, 10))

    private val gboardId = "com.google.android.inputmethod.latin:id/key_pos_"

    private val letters =
        ("qwertyuiopasdfghjklzxcvbnm".map { key(it.toString()) } +
            listOf(
                key("Shift", gboardId + "shift"),
                key("Delete", gboardId + "del"),
                key("Symbol keyboard", gboardId + "switch_to_symbol"),
                key(","),
                key("Space", gboardId + "space"),
                key("."),
                key("Search", gboardId + "ime_action"),
            ))

    private val symbols =
        ("1234567890@#$".map { key(it.toString()) } +
            listOf(
                key("underline"),
                key("Dash"),
                key("Left parenthesis"),
                key("Question mark"),
                key("More symbols", gboardId + "shift"),
                key("Letter keyboard", gboardId + "back_to_prime"),
            ))

    @Test
    fun `finds literal and spoken keys`() {
        assertEquals("q", KeyMatcher.forChar(letters, 'q')?.label)
        assertEquals("Dash", KeyMatcher.forChar(symbols, '-')?.label)
        assertEquals("Question mark", KeyMatcher.forChar(symbols, '?')?.label)
        assertEquals("underline", KeyMatcher.forChar(symbols, '_')?.label)
        assertNull(KeyMatcher.forChar(letters, '7'))
    }

    @Test
    fun `detects case mismatch`() {
        assertNull(KeyMatcher.forChar(letters, 'Q'))
        assertEquals("q", KeyMatcher.otherCase(letters, 'Q')?.label)
    }

    @Test
    fun `finds modifier and page keys`() {
        assertEquals("Shift", KeyMatcher.shift(letters)?.label)
        assertNull(KeyMatcher.shift(symbols))
        assertEquals("Space", KeyMatcher.space(letters)?.label)
        assertEquals("Delete", KeyMatcher.delete(letters)?.label)
        assertEquals("Search", KeyMatcher.action(letters)?.label)
        assertEquals("Symbol keyboard", KeyMatcher.symbolsPage(letters)?.label)
        assertEquals("More symbols", KeyMatcher.moreSymbolsPage(symbols)?.label)
        assertEquals("Letter keyboard", KeyMatcher.lettersPage(symbols)?.label)
    }

    @Test
    fun `recognizes a keyboard`() {
        assertTrue(KeyMatcher.looksLikeKeyboard(letters))
        assertTrue(!KeyMatcher.looksLikeKeyboard(listOf(key("Use voice typing"))))
    }
}
