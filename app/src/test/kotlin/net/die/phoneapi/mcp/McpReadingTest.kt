package net.die.phoneapi.mcp

import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.PageHeading
import net.die.phoneapi.model.PageLink
import net.die.phoneapi.model.TextMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McpReadingTest {
    private val read =
        BrowserReadResult(
            title = "Doc",
            url = "https://example.com/a",
            published = "2026-01-02",
            readyState = "complete",
            status = 200,
            extracted = "article",
            version = "0badf00d",
            totalChars = 120,
            offset = 0,
            returnedChars = 8,
            nextOffset = 8,
            truncated = true,
            content = "# Title\n",
            links = 3,
        )

    @Test
    fun `read has a header`() {
        assertEquals(
            listOf(
                    """# title="Doc" url=https://example.com/a published=2026-01-02 status=200""",
                    "# article content, chars 0-8 of 120, version 0badf00d; nextOffset=8",
                    "",
                    "# Title",
                    "",
                )
                .joinToString("\n"),
            readText(read),
        )
    }

    @Test
    fun `read text flags a blocked page`() {
        val blocked =
            read.copy(blocked = "captcha", sparse = true, readyState = "loading", nextOffset = null)
        assertEquals(
            listOf(
                "# blocked: captcha",
                "# sparse: under 200 characters of visible text; it may still be loading",
                "# readyState=loading: the page is still loading",
                "# article content, chars 0-8 of 120, version 0badf00d; end",
            ),
            readText(blocked).lines().subList(1, 5),
        )
    }

    @Test
    fun `outline reports a cut`() {
        val outline =
            read.copy(
                content = null,
                headings = listOf(PageHeading(1, "Title", 0), PageHeading(2, "Steps", 40)),
            )
        val lines = outlineText(outline, maxChars = 12).lines()
        assertEquals(
            "# article content, 120 chars, version 0badf00d; 2 headings, @ is each one's offset. " +
                "On the page: 3 links, 0 tables, 0 forms",
            lines[1],
        )
        assertEquals("# showing 1 of 2 headings; raise maxChars for more", lines[2])
        assertEquals("@0 # Title", lines[3])
    }

    @Test
    fun `one line per result`() {
        val found =
            BrowserFindResult(
                extracted = "page",
                version = "1",
                totalChars = 50,
                total = 4,
                matches = listOf(TextMatch(12, "Steps", "a\nb ", "First", " c")),
            )
        assertEquals("""@12 under "Steps": …a b «First» c…""", matchesText(found).lines()[2])
        val links =
            found.copy(
                matches = null,
                links = listOf(PageLink("a link", "https://example.com/b", 9, true)),
            )
        assertEquals(
            """@9 "a link" https://example.com/b same-origin""",
            linksText(links).lines()[2],
        )
    }
}
