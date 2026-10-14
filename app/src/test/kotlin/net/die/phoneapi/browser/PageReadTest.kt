package net.die.phoneapi.browser

import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.FindKind
import net.die.phoneapi.model.ReadFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageReadTest {
    private val blocks =
        listOf(
            """{"k":"h","l":1,"r":[{"t":"Title"}]}""",
            """{"k":"p","r":[{"t":" Some "},{"t":"bold","s":"b"},{"t":" text with "},""" +
                """{"t":"a link","h":"https://example.com/b"},{"t":" and "},""" +
                """{"t":"another","h":"https://other.org/c"},{"t":". "}]}""",
            """{"k":"h","l":2,"r":[{"t":"Steps"}]}""",
            """{"k":"li","d":1,"o":1,"r":[{"t":"First"}]}""",
            """{"k":"li","d":2,"r":[{"t":"Nested"}]}""",
            """{"k":"tr","t":0,"c":["Name","Value"],"th":true}""",
            """{"k":"tr","t":0,"c":["a|b","1"]}""",
            """{"k":"p","q":1,"r":[{"t":"Quoted"}]}""",
            """{"k":"pre","x":"code()"}""",
            """{"k":"p","r":[{"t":"   "}]}""",
        )

    private val extract =
        ApiJson.decodeFromString(
            PageExtract.serializer(),
            """{"title":"Doc","url":"https://example.com/a","readyState":"complete",""" +
                """"status":200,"pageChars":900,"links":2,"tables":1,"forms":0,""" +
                """"extracted":"article","blocks":[${blocks.joinToString(",")}]}""",
        )

    @Test
    fun `renders markdown`() {
        val page = renderPage(extract.blocks, ReadFormat.MARKDOWN)
        assertEquals(
            listOf(
                    "# Title",
                    "",
                    "Some **bold** text with a link and another.",
                    "",
                    "## Steps",
                    "",
                    "1. First",
                    "  - Nested",
                    "",
                    "| Name | Value |",
                    "| --- | --- |",
                    "| a\\|b | 1 |",
                    "",
                    "> Quoted",
                    "",
                    "```",
                    "code()",
                    "```",
                )
                .joinToString("\n"),
            page.text,
        )
        assertEquals(listOf(0, page.text.indexOf("## Steps")), page.headings.map { it.offset })
        val link = page.links.first()
        assertEquals("a link", link.text)
        assertEquals("a link", page.text.substring(link.offset, link.offset + link.text.length))
    }

    @Test
    fun `renders text without markup`() {
        val page = renderPage(extract.blocks, ReadFormat.TEXT)
        val start = listOf("Title", "", "Some bold text with a link and another.", "", "Steps")
        assertTrue(page.text.startsWith(start.joinToString("\n")))
        assertTrue("Name | Value\na|b | 1" in page.text)
        assertFalse("```" in page.text)
    }

    @Test
    fun `reads a slice at a time`() {
        val whole = readPage(extract, BrowserReadRequest())
        assertFalse(whole.truncated)
        assertNull(whole.nextOffset)
        assertEquals(whole.totalChars, whole.returnedChars)
        assertEquals(200, whole.status)
        assertFalse(whole.sparse)

        val first = readPage(extract, BrowserReadRequest(maxChars = 12))
        assertTrue(first.truncated)
        assertEquals("# Title\n\n", first.content)
        val next = readPage(extract, BrowserReadRequest(offset = first.nextOffset!!, maxChars = 30))
        assertTrue(next.content!!.startsWith("Some **bold**"))
        assertEquals(first.version, next.version)
        assertThrows(ApiException::class.java) {
            readPage(extract, BrowserReadRequest(offset = whole.totalChars + 1))
        }
    }

    @Test
    fun `outline has only headings`() {
        val outline = readPage(extract, BrowserReadRequest(outline = true))
        assertNull(outline.content)
        assertEquals(listOf("Title", "Steps"), outline.headings!!.map { it.text })
        assertEquals(listOf(1, 2), outline.headings!!.map { it.level })
        assertEquals(2, outline.links)
    }

    @Test
    fun `cuts at a line or a space`() {
        assertEquals(6, cutPoint("abcde\nfghij", 0, 8))
        assertEquals(6, cutPoint("abcde fghij", 0, 8))
        assertEquals(4, cutPoint("abcdefghij", 0, 4))
        assertEquals(3, cutPoint("abc😀def", 0, 4))
        assertEquals(10, cutPoint("abcdefghij", 2, 100))
    }

    @Test
    fun `finds text in context`() {
        val result = findInPage(extract, BrowserFindRequest(query = "FIRST", contextChars = 5))
        assertEquals(1, result.total)
        val match = result.matches!!.single()
        assertEquals("First", match.match)
        assertEquals("Steps", match.heading)
        assertEquals("\n\n1. ", match.before)
        val page = renderPage(extract.blocks, ReadFormat.MARKDOWN)
        assertEquals(page.text.indexOf("First"), match.offset)

        val regex = findInPage(extract, BrowserFindRequest(query = "n[a-z]+d", regex = true))
        assertEquals(listOf("Nested"), regex.matches!!.map { it.match })
        assertThrows(ApiException::class.java) {
            findInPage(extract, BrowserFindRequest(query = "(", regex = true))
        }
        assertThrows(ApiException::class.java) { findInPage(extract, BrowserFindRequest()) }
    }

    @Test
    fun `lists links with filters`() {
        val all = findInPage(extract, BrowserFindRequest(kind = FindKind.LINKS))
        assertEquals(2, all.total)
        assertEquals(listOf(true, false), all.links!!.map { it.sameOrigin })
        val other =
            findInPage(extract, BrowserFindRequest(kind = FindKind.LINKS, hrefContains = "OTHER"))
        assertEquals(listOf("another"), other.links!!.map { it.text })
        val text = findInPage(extract, BrowserFindRequest(kind = FindKind.LINKS, query = "link"))
        assertEquals(listOf("https://example.com/b"), text.links!!.map { it.href })
    }
}
