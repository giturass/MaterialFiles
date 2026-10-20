package me.zhanghai.android.files.httpserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class HttpServerProtocolTest {
    @Test
    fun fileNamesRoundTripThroughUrls() {
        val names = listOf("中文 空格+%?#&.txt", "100%25.txt", "a\"'<>*.txt")
        val url = HttpServerPaths.url(names, false)
        assertFalse(url.contains('+'))
        assertFalse(url.contains('?'))
        assertFalse(url.contains('#'))
        assertEquals(names, HttpServerPaths.segments(URLDecoder.decode(url, "UTF-8")))
        assertEquals("/", HttpServerPaths.url(emptyList(), true))
        assertEquals("/folder/", HttpServerPaths.url(listOf("folder"), true))
    }

    @Test
    fun rejectsTraversalAndInvalidPaths() {
        val paths = listOf("../secret", "/../secret", "/a/../../secret", "/./a", "/a\\b", "/a\u0000b")
        for (path in paths) {
            assertThrows(IllegalArgumentException::class.java) { HttpServerPaths.segments(path) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpServerPaths.segments(URLDecoder.decode("/%2e%2e/secret", "UTF-8"))
        }
        // A literal percent sequence in a filename must never be decoded again by the server.
        assertEquals(listOf("%2e%2e"), HttpServerPaths.segments("/%2e%2e"))
    }

    @Test
    fun supportsExplicitOpenEndedAndSuffixRanges() {
        assertEquals(HttpByteRange(2, 5), HttpByteRange.parse("bytes=2-5", 10))
        assertEquals(HttpByteRange(2, 9), HttpByteRange.parse("bytes=2-", 10))
        assertEquals(HttpByteRange(7, 9), HttpByteRange.parse("bytes=-3", 10))
        assertEquals(HttpByteRange(0, 9), HttpByteRange.parse("bytes=-30", 10))
        assertEquals(HttpByteRange(2, 9), HttpByteRange.parse("bytes=2-30", 10))
        assertEquals(4L, HttpByteRange.parse("bytes=2-5", 10)!!.length)
    }

    @Test
    fun rejectsUnsatisfiableAndOverflowedRanges() {
        val ranges = listOf(
            "bytes=10-", "bytes=6-2", "bytes=-0", "bytes=-", "bytes=a-2",
            "bytes=9223372036854775808-", "bytes=0-9223372036854775808"
        )
        for (range in ranges) {
            assertThrows(IllegalArgumentException::class.java) { HttpByteRange.parse(range, 10) }
        }
        assertThrows(IllegalArgumentException::class.java) { HttpByteRange.parse("bytes=0-", 0) }
        assertNull(HttpByteRange.parse(null, 0))
    }

    @Test
    fun handlesLargeFilesWithoutIntegerOverflow() {
        val size = 5_000_000_000L
        assertEquals(
            HttpByteRange(4_000_000_000, size - 1), HttpByteRange.parse("bytes=4000000000-", size)
        )
        assertEquals(
            HttpByteRange(0, Long.MAX_VALUE - 1), HttpByteRange.parse("bytes=0-", Long.MAX_VALUE)
        )
    }

    @Test
    fun ignoresUnsupportedRangeUnitsAndMultipartRanges() {
        assertNull(HttpByteRange.parse("items=0-1", 10))
        assertNull(HttpByteRange.parse("bytes=0-1,4-5", 10))
    }

    @Test
    fun listingEscapesNamesAndEncodesLinks() {
        val name = "<img src=x onerror=alert(1)>&\".txt"
        val page = HttpServerPage.render(
            listOf("<script>alert(2)</script>"), listOf(HttpServerPage.Entry(name, false, 10)), false
        )
        assertFalse(page.contains("<img"))
        assertFalse(page.contains("<script>"))
        assertTrue(page.contains("&lt;img src=x onerror=alert(1)&gt;&amp;&quot;.txt"))
        assertTrue(page.contains(HttpServerPaths.encode(name)))
        assertFalse(page.contains("id=\"upload\""))
        assertTrue(page.contains("只读模式"))
    }

    @Test
    fun rootListingHasNoParentAndWritableListingOffersUploads() {
        val rootPage = HttpServerPage.render(emptyList(), emptyList(), true)
        assertFalse(rootPage.contains("上一级"))
        assertTrue(rootPage.contains("此文件夹为空"))
        assertTrue(rootPage.contains("id=\"upload\""))
        val childPage = HttpServerPage.render(listOf("a", "b"), emptyList(), false)
        assertTrue(childPage.contains("href=\"/a/\""))
    }
}
