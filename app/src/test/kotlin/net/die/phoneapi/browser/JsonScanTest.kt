package net.die.phoneapi.browser

import java.util.zip.CRC32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonScanTest {
    private val json = """ { "a b" : [ 1 , "x \"y\" z" , true ] , "cA" : { } , "d" : null } """

    @Test
    fun `walks values in place`() {
        val scan = JsonScan(json)
        val root = scan.space(0)
        assertEquals(json.trimEnd().length, scan.end(root))
        val keys = ArrayList<String>()
        scan.members(root) { start, end, _ ->
            keys += scan.string(start, end)
            true
        }
        assertEquals(listOf("a b", "cA", "d"), keys)
        assertEquals(3, scan.count(root))
    }

    @Test
    fun `writes compact JSON`() {
        val scan = JsonScan(json)
        val out = BuilderSink(Utf8Crc())
        scan.compact(scan.space(0), out)
        val compact = """{"a b":[1,"x \"y\" z",true],"cA":{},"d":null}"""
        assertEquals(compact, out.text.substring(0))
        assertEquals(compact.length, scan.compactLength(scan.space(0)))
    }

    @Test
    fun `decodes strings and keys`() {
        val scan = JsonScan(""""tab\there é😀"""")
        assertEquals("tab\there é😀", scan.string(0, scan.text.length))
        assertEquals("tab", scan.string(0, scan.text.length, 3))
        val keys = JsonScan("""{"cA":1,"plain":2}""")
        val spans = ArrayList<Pair<Int, Int>>()
        keys.members(0) { start, end, _ ->
            spans += start to end
            true
        }
        assertTrue(keys.keyIs(spans[0].first, spans[0].second, "cA"))
        assertTrue(keys.keyIs(spans[1].first, spans[1].second, "plain"))
        assertFalse(keys.keyIs(spans[1].first, spans[1].second, "plai"))
    }

    @Test
    fun `refuses text that is not JSON`() {
        assertThrows(IllegalArgumentException::class.java) { JsonScan("""{"a" 1}""").count(0) }
        assertThrows(IllegalArgumentException::class.java) { JsonScan("""["a"""").end(0) }
        assertThrows(IllegalArgumentException::class.java) { JsonScan("""[1 2]""").count(0) }
    }

    @Test
    fun `prints JSON with line paths`() {
        val scan = JsonScan("""{"a":[1,{"b/c":"x"}],"e":{},"f":[]}""")
        val out = BuilderSink(Utf8Crc())
        val paths = ArrayList<String>()
        JsonPrinter(scan, out) { _, stack ->
                paths += stack.path("blob")
                true
            }
            .print(0)
        assertEquals(
            listOf(
                    "{",
                    """  "a": [""",
                    "    1,",
                    "    {",
                    """      "b/c": "x"""",
                    "    }",
                    "  ],",
                    """  "e": {},""",
                    """  "f": []""",
                    "}",
                )
                .joinToString("\n"),
            out.text.substring(0),
        )
        assertEquals(
            listOf(
                "blob",
                "blob/a",
                "blob/a/0",
                "blob/a/1",
                "blob/a/1/b~1c",
                "blob/a/1",
                "blob/a",
                "blob/e",
                "blob/f",
                "blob",
            ),
            paths,
        )
    }

    @Test
    fun `stops printing when told`() {
        val scan = JsonScan("""[1,2,3,4]""")
        val seen = ArrayList<Int>()
        JsonPrinter(scan, NullSink) { line, _ ->
                seen += line
                line < 2
            }
            .print(0)
        assertEquals(listOf(0, 1, 2, 3), seen.take(4))
        assertTrue(seen.size < 6)
    }

    @Test
    fun `refuses JSON nested too deeply`() {
        val deep = "[".repeat(300) + "]".repeat(300)
        assertThrows(IllegalArgumentException::class.java) {
            JsonPrinter(JsonScan(deep), NullSink).print(0)
        }
    }

    @Test
    fun `hashes as RenderedPage does`() {
        val texts =
            listOf(
                "plain",
                "é and 漢字",
                "emoji 😀 here",
                "lone \ud83d high",
                "lone \ude00 low",
                "end \ud83d",
            )
        for (text in texts) {
            val whole = CRC32().apply { update(text.toByteArray()) }.value
            val hash = Utf8Crc()
            // Split inside the emoji's surrogate pair, too.
            val cut = text.length / 2 + 3
            hash.update(text, 0, minOf(cut, text.length))
            hash.update(text, minOf(cut, text.length), text.length)
            assertEquals(whole.toString(16).padStart(8, '0'), hash.value(), text)
        }
    }

    @Test
    fun `window keeps only its slice`() {
        val text = "abcdefghij".repeat(1_000)
        val window = WindowSink(4_995, 11)
        var at = 0
        while (at < text.length) {
            val end = minOf(at + 7, text.length)
            window.append(text, at, end)
            at = end
        }
        assertEquals(text.length, window.length)
        assertEquals(text.substring(4_995, 5_006), window.kept.substring(0))
        assertEquals(Utf8Crc().apply { update(text, 0, text.length) }.value(), window.hash.value())
    }
}
