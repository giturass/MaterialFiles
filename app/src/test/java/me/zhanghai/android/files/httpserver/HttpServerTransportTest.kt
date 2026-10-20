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

    private fun newTransport(password: String? = null): Transport =
        Transport(HttpServer("user", password, 0, fileSystem.getPath("/"), true))

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

    /** Runs NanoHTTPD's request parser and response writer without Android or a network port. */
    private class Transport(private val server: HttpServer) : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response = server.serve(session)
        override fun useGzipWhenAccepted(response: Response): Boolean = false

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
