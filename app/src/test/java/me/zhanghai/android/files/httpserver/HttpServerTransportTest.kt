package me.zhanghai.android.files.httpserver

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpServerTransportTest {
    private val fileSystem = HttpServerTestFileSystem()

    private fun newTransport(password: String? = null, writable: Boolean = true): Transport =
        Transport(HttpServer("user", password, 0, fileSystem.getPath("/"), writable))

    @Test
    fun headKeepsGetHeadersWithoutSendingDirectoryOrErrorBodies() {
        val transport = newTransport()
        val cases = listOf(
            "/" to 200, "/folder/" to 200, "/missing" to 404,
            "/../secret" to 400, "/folder" to 301
        )
        for ((path, status) in cases) {
            val get = transport.request("GET", path)
            val head = transport.request("HEAD", path)
            assertEquals(status, head.status)
            assertEquals(get.headers["content-length"], head.headers["content-length"])
            assertEquals(get.headers["content-type"], head.headers["content-type"])
            assertEquals(get.headers["location"], head.headers["location"])
            assertTrue("HEAD $path must not send a body", head.body.isEmpty())
        }
        val authenticated = newTransport("password")
        val get = authenticated.request("GET", "/")
        val head = authenticated.request("HEAD", "/")
        assertEquals(401, head.status)
        assertEquals(get.headers["www-authenticate"], head.headers["www-authenticate"])
        assertEquals(get.headers["content-length"], head.headers["content-length"])
        assertTrue(head.body.isEmpty())
    }

    @Test
    fun fileHeadIgnoresRangeAndGetStillTransfersTheRequestedBytes() {
        fileSystem.files["/file"] = "0123456789".toByteArray()
        val transport = newTransport()
        val headers = mapOf("Range" to "bytes=2-5")
        val head = transport.request("HEAD", "/file", headers)
        assertEquals(200, head.status)
        assertEquals("10", head.headers["content-length"])
        assertTrue(head.body.isEmpty())
        val get = transport.request("GET", "/file", headers)
        assertEquals(206, get.status)
        assertEquals("bytes 2-5/10", get.headers["content-range"])
        assertArrayEquals("2345".toByteArray(), get.body)
    }

    @Test
    fun concurrentUploadsCannotOverwriteTheSameRemoteFile() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        fileSystem.onOpen = { name -> if (name == "/same") opened.countDown() }
        val transport = newTransport()
        val executor = Executors.newSingleThreadExecutor()
        val body = object : InputStream() {
            private val input = "first".byteInputStream()
            override fun read(): Int {
                check(release.await(5, TimeUnit.SECONDS)) { "Upload was not released" }
                return input.read()
            }
        }
        val first = executor.submit<RawResponse> {
            transport.request("PUT", "/same", mapOf("Content-Length" to "5"), body)
        }
        try {
            assertTrue("First upload did not open its output", opened.await(5, TimeUnit.SECONDS))
            assertFalse(fileSystem.files.containsKey("/same"))
            // The repeated slash resolves to the same path and must share its reservation.
            assertEquals(409, transport.put("//same", "second").status)
            assertEquals(201, transport.put("/other", "independent").status)
            release.countDown()
            assertEquals(201, first.get(5, TimeUnit.SECONDS).status)
            assertArrayEquals("first".toByteArray(), fileSystem.files["/same"])
            assertEquals(409, transport.put("/same", "third").status)
            assertArrayEquals("first".toByteArray(), fileSystem.files["/same"])
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun failedUploadsRemovePartialFilesAndReleaseTheirReservation() {
        val transport = newTransport()
        val response = transport.request(
            "PUT", "/retry", mapOf("Content-Length" to "10"), "short".byteInputStream()
        )
        assertEquals(500, response.status)
        assertFalse(fileSystem.files.containsKey("/retry"))
        assertEquals(201, transport.put("/retry", "complete").status)
        assertArrayEquals("complete".toByteArray(), fileSystem.files["/retry"])
        assertEquals(409, transport.put("/retry", "conflict").status)
        fileSystem.files.remove("/retry")
        assertEquals(201, transport.put("/retry", "after conflict").status)
    }

    @Test
    fun createsRenamesAndDeletesNestedFoldersWithEncodedNames() {
        val transport = newTransport()
        val name = "中文 空格+%?#&"
        val url = HttpServerPaths.url(listOf(name), true)
        assertEquals(201, transport.manage("/", "mkdir", name).status)
        assertEquals(200, transport.request("GET", url).status)
        assertEquals(201, transport.manage(url, "mkdir", "nested").status)
        assertEquals(201, transport.put(url + "nested/file.txt", "contents").status)
        val renamed = transport.manage(url, "rename", "%2e%2e")
        assertEquals(200, renamed.status)
        assertEquals("/%252e%252e/", renamed.headers["location"])
        assertEquals(404, transport.request("GET", url).status)
        assertEquals(200, transport.request("GET", "/%252e%252e/nested/file.txt").status)
        assertEquals(200, transport.delete("/%252e%252e/").status)
        assertFalse(fileSystem.directories.any { it.startsWith("/%2e%2e") })
        assertTrue(fileSystem.files.isEmpty())
    }

    @Test
    fun conflictsNeverReplaceFilesOrFoldersAndReservationsAreReleased() {
        val transport = newTransport()
        transport.put("/first", "first")
        transport.put("/second", "second")
        assertEquals(409, transport.manage("/first", "rename", "second").status)
        assertEquals(409, transport.manage("/first", "rename", "folder").status)
        assertEquals(409, transport.manage("/", "mkdir", "first").status)
        assertEquals(409, transport.manage("/", "mkdir", "folder").status)
        assertArrayEquals("first".toByteArray(), fileSystem.files["/first"])
        assertArrayEquals("second".toByteArray(), fileSystem.files["/second"])
        assertEquals(200, transport.manage("/first", "rename", "renamed").status)
        assertEquals(200, transport.delete("/renamed").status)
        assertEquals(404, transport.delete("/renamed").status)
        assertEquals(201, transport.manage("/", "mkdir", "renamed").status)
        assertEquals(201, transport.put("/first", "new file").status)
    }

    @Test
    fun rejectsRootMutationsInvalidNamesAndAmbiguousParameters() {
        val transport = newTransport()
        assertEquals(400, transport.delete("/").status)
        assertEquals(400, transport.manage("/", "rename", "root").status)
        for (name in listOf("", " ", ".", "..", "a/b", "/outside", "a\\b", "a\u0000b")) {
            assertEquals(name, 400, transport.manage("/", "mkdir", name).status)
            assertEquals(name, 400, transport.manage("/folder/", "rename", name).status)
        }
        val headers = mapOf("X-Material-Files" to "1")
        assertEquals(400, transport.request("POST", "/?action=mkdir&name=a&name=b", headers).status)
        assertEquals(400, transport.request("POST", "/?action=mkdir&action=rename&name=a", headers).status)
        assertEquals(400, transport.manage("/", "unknown", "name").status)
        assertEquals(404, transport.manage("/missing/", "mkdir", "child").status)
        assertEquals(setOf("/", "/folder"), fileSystem.directories)
    }

    @Test
    fun allManagementMethodsRequireWriteAccessAuthenticationAndSameOrigin() {
        fileSystem.files["/file"] = "original".toByteArray()
        val requests = listOf(
            "POST" to "/?action=mkdir&name=new",
            "POST" to "/file?action=rename&name=new",
            "DELETE" to "/file",
            "PUT" to "/new"
        )
        val readOnly = newTransport(writable = false)
        val authenticated = newTransport(password = "password")
        val transport = newTransport()
        for ((method, path) in requests) {
            val headers = mapOf("X-Material-Files" to "1", "Content-Length" to "0")
            assertEquals(403, readOnly.request(method, path, headers).status)
            assertEquals(401, authenticated.request(method, path, headers).status)
            assertEquals(403, transport.request(method, path, headers + ("Origin" to "http://foreign")).status)
            if (method != "PUT") assertEquals(403, transport.request(method, path).status)
        }
        val readOnlyOptions = readOnly.request("OPTIONS", "/")
        assertEquals(405, readOnlyOptions.status)
        assertEquals("GET, HEAD", readOnlyOptions.headers["allow"])
        assertEquals("GET, HEAD, PUT, POST, DELETE", transport.request("OPTIONS", "/").headers["allow"])
        assertArrayEquals("original".toByteArray(), fileSystem.files["/file"])
        val authorization = "Basic " + java.util.Base64.getEncoder().encodeToString("user:password".toByteArray())
        assertEquals(201, authenticated.request(
            "POST", "/?action=mkdir&name=authenticated",
            mapOf("X-Material-Files" to "1", "Origin" to "http://localhost", "Authorization" to authorization)
        ).status)
    }

    @Test
    fun deletionRemovesNestedLinksWithoutTraversingThem() {
        fileSystem.symbolicLinks.add("/folder/link")
        fileSystem.files["/outside"] = "untouched".toByteArray()
        val transport = newTransport()
        assertEquals(403, transport.delete("/folder/link").status)
        assertEquals(403, transport.manage("/folder/link/", "mkdir", "child").status)
        assertEquals(403, transport.manage("/folder/link", "rename", "renamed").status)
        assertEquals(400, transport.manage("/outside", "rename", "folder/link").status)
        assertEquals(200, transport.delete("/folder/").status)
        assertTrue(fileSystem.symbolicLinks.isEmpty())
        assertArrayEquals("untouched".toByteArray(), fileSystem.files["/outside"])
    }

    @Test
    fun activeUploadPreventsChangingItsParentOrReplacingItsDestination() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        fileSystem.onOpen = { name -> if (name == "/folder/slow") opened.countDown() }
        val transport = newTransport()
        val executor = Executors.newSingleThreadExecutor()
        val body = object : InputStream() {
            private val input = "upload".byteInputStream()
            override fun read(): Int {
                check(release.await(5, TimeUnit.SECONDS))
                return input.read()
            }
        }
        val upload = executor.submit<RawResponse> {
            transport.request("PUT", "/folder/slow", mapOf("Content-Length" to "6"), body)
        }
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertEquals(409, transport.delete("/folder/").status)
            assertEquals(409, transport.delete("/folder/slow").status)
            assertEquals(409, transport.manage("/folder/", "rename", "renamed").status)
            assertEquals(409, transport.manage("/folder/", "mkdir", "slow").status)
            assertEquals(201, transport.put("/folder/sibling", "sibling").status)
            assertEquals(409, transport.manage("/folder/sibling", "rename", "slow").status)
            assertEquals(201, transport.manage("/", "mkdir", "independent").status)
            release.countDown()
            assertEquals(201, upload.get(5, TimeUnit.SECONDS).status)
            assertEquals(200, transport.manage("/folder/", "rename", "renamed").status)
            assertArrayEquals("upload".toByteArray(), fileSystem.files["/renamed/slow"])
            assertEquals(200, transport.delete("/renamed/").status)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun previewReadsStayBoundedAndDownloadsKeepTheirAttachmentHeaders() {
        fileSystem.files["/page.html"] = ByteArray(300_000) { 'x'.code.toByte() }
        fileSystem.files["/photo.png"] = byteArrayOf(1, 2, 3)
        val transport = newTransport()
        val text = transport.request("GET", "/page.html", mapOf("Range" to "bytes=0-262144"))
        assertEquals(206, text.status)
        assertEquals(262_145, text.body.size)
        assertEquals("bytes 0-262144/300000", text.headers["content-range"])
        for (path in listOf("/page.html", "/photo.png")) {
            val response = transport.request("GET", path)
            assertEquals("application/octet-stream", response.headers["content-type"])
            assertTrue(response.headers["content-disposition"]!!.startsWith("attachment;"))
            assertEquals("nosniff", response.headers["x-content-type-options"])
        }
        val page = transport.request("GET", "/")
        assertTrue(page.headers["content-security-policy"]!!.contains("img-src blob:"))
        assertFalse(page.headers.containsKey("access-control-allow-origin"))
    }

    /** Runs NanoHTTPD's request parser and response writer without Android or a network port. */
    private class Transport(private val server: HttpServer) : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response = server.serve(session)
        override fun useGzipWhenAccepted(response: Response): Boolean = false

        fun manage(path: String, action: String, name: String): RawResponse = request(
            "POST", "$path?action=$action&name=${HttpServerPaths.encode(name)}",
            mapOf("X-Material-Files" to "1")
        )

        fun delete(path: String): RawResponse =
            request("DELETE", path, mapOf("X-Material-Files" to "1"))

        fun put(path: String, text: String): RawResponse {
            val bytes = text.toByteArray()
            return request(
                "PUT", path, mapOf("Content-Length" to bytes.size.toString()), bytes.inputStream()
            )
        }

        fun request(
            method: String,
            path: String,
            headers: Map<String, String> = emptyMap(),
            body: InputStream = "".byteInputStream()
        ): RawResponse {
            val header = buildString {
                append("$method $path HTTP/1.1\r\nHost: localhost\r\n")
                for ((name, value) in headers) append("$name: $value\r\n")
                append("\r\n")
            }
            val output = ByteArrayOutputStream()
            val tempFileManager = object : TempFileManager {
                override fun clear() {}
                override fun createTempFile(filenameHint: String?): TempFile =
                    error("Streaming requests must not create temporary files")
            }
            SequenceInputStream(header.byteInputStream(), body).use { input ->
                try {
                    HTTPSession(tempFileManager, input, output).execute()
                } catch (e: SocketException) {
                    // This is how NanoHTTPD ends a response with Connection: close.
                    if (e.message != "NanoHttpd Shutdown") throw e
                }
            }
            val bytes = output.toByteArray()
            val text = bytes.toString(Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            check(split >= 0) { "Missing HTTP response headers" }
            val lines = text.substring(0, split).split("\r\n")
            return RawResponse(
                lines.first().split(' ')[1].toInt(),
                lines.drop(1).associate {
                    it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                },
                bytes.copyOfRange(split + 4, bytes.size)
            )
        }
    }

    private data class RawResponse(
        val status: Int, val headers: Map<String, String>, val body: ByteArray
    )
}
