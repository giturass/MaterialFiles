package me.zhanghai.android.files.httpserver

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption.NOFOLLOW_LINKS
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption.CREATE_NEW
import java8.nio.file.StandardOpenOption.WRITE
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.ftpserver.LocalServer
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.isDirectory
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.readAttributes
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64

/** HTTP transport over the same storage providers used by the file manager. */
class HttpServer(
    username: String,
    password: String?,
    port: Int,
    homeDirectory: Path,
    private val writable: Boolean
) : NanoHTTPD(port), LocalServer {
    private val root = homeDirectory.toAbsolutePath().normalize()
    private val credentials = password?.let { "$username:$it".toByteArray(Charsets.UTF_8) }
    private val changingPaths = mutableSetOf<Path>()

    override fun start() {
        require(root.isDirectory()) { "根文件夹不存在或无法访问" }
        super.start(30_000, false)
    }

    override fun stop() {
        super.stop()
    }

    // Keep Content-Length and byte ranges stable, including for HEAD requests.
    override fun useGzipWhenAccepted(response: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            serveRequest(session)
        } catch (e: RequestException) {
            message(e.status, e.message.orEmpty())
        } catch (e: NoSuchFileException) {
            message(Status.NOT_FOUND, "文件不存在")
        } catch (e: FileAlreadyExistsException) {
            message(Status.CONFLICT, "同名文件或文件夹已存在，请使用其他名称")
        } catch (e: AccessDeniedException) {
            message(Status.FORBIDDEN, "无法访问此文件")
        } catch (e: SecurityException) {
            message(Status.FORBIDDEN, "无法访问此文件")
        } catch (e: IllegalArgumentException) {
            message(Status.BAD_REQUEST, "无效请求")
        } catch (e: UnsupportedOperationException) {
            message(Status.FORBIDDEN, "此存储不支持该操作")
        } catch (e: IOException) {
            e.printStackTrace()
            message(Status.INTERNAL_ERROR, "文件操作失败")
        }
        return response.apply {
            // NanoHTTPD 2.3.1 still sends fixed-length response bodies for HEAD. Keep the
            // original Content-Length, but suppress the data for directories and errors too.
            if (session.method == Method.HEAD) {
                data.close()
                data = ByteArrayInputStream(ByteArray(0))
            }
            // Rejected requests may have an unread body. Never interpret it as another request.
            closeConnection(true)
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }
    }

    private fun serveRequest(session: IHTTPSession): Response {
        if (!isAuthenticated(session.headers["authorization"])) {
            return message(Status.UNAUTHORIZED, "请输入服务器用户名和密码").apply {
                addHeader("WWW-Authenticate", "Basic realm=\"Material Files\", charset=\"UTF-8\"")
            }
        }
        if (session.method !in listOf(Method.GET, Method.HEAD, Method.PUT, Method.POST, Method.DELETE)) {
            return message(Status.METHOD_NOT_ALLOWED, "不支持此方法").apply {
                addHeader("Allow", if (writable) "GET, HEAD, PUT, POST, DELETE" else "GET, HEAD")
            }
        }
        if (session.headers.containsKey("expect")) {
            throw RequestException(Status.EXPECTATION_FAILED, "不支持 Expect 请求头")
        }
        // NanoHTTPD has already percent-decoded the URI. Do not decode it a second time.
        val segments = HttpServerPaths.segments(session.uri)
        if (session.method in listOf(Method.PUT, Method.POST, Method.DELETE)) {
            checkWriteRequest(session)
            return when (session.method) {
                Method.PUT -> withChanges(listOf(segments)) {
                    upload(session, resolve(segments, true), segments)
                }
                Method.POST -> manage(session, segments)
                else -> {
                    require(segments.isNotEmpty()) { "不能删除根文件夹" }
                    withChanges(listOf(segments)) {
                        deleteTree(resolve(segments))
                        message(Status.OK, "删除完成")
                    }
                }
            }
        }
        val path = resolve(segments)
        val attributes = path.readAttributes(BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        // A configured root may itself be an alias such as /sdcard.
        if (attributes.isDirectory || (path == root && root.isDirectory())) {
            if (!session.uri.endsWith('/')) {
                return message(Status.REDIRECT, "").apply {
                    addHeader("Location", HttpServerPaths.url(segments, true))
                }
            }
            return directory(path, segments)
        }
        if (!attributes.isRegularFile) {
            throw RequestException(Status.FORBIDDEN, "仅支持普通文件和文件夹")
        }
        return download(session, path, attributes.size())
    }

    private fun isAuthenticated(authorization: String?): Boolean {
        val expected = credentials ?: return true
        if (authorization == null || !authorization.startsWith("Basic ", ignoreCase = true)) {
            return false
        }
        val supplied = try {
            Base64.getDecoder().decode(authorization.substring(6).trim())
        } catch (e: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(expected, supplied)
    }

    private fun resolve(segments: List<String>, allowMissingLeaf: Boolean = false): Path {
        var path = root
        for ((index, segment) in segments.withIndex()) {
            path = path.resolve(segment)
            if (!path.normalize().startsWith(root)) {
                throw RequestException(Status.FORBIDDEN, "路径超出根文件夹")
            }
            val attributes = try {
                path.readAttributes(BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                if (allowMissingLeaf && index == segments.lastIndex) {
                    return path
                }
                throw e
            }
            // Providers do not implement toRealPath(). Reject links at every level instead.
            if (attributes.isSymbolicLink) {
                throw RequestException(Status.FORBIDDEN, "不允许访问符号链接")
            }
            if (index != segments.lastIndex && !attributes.isDirectory) {
                throw RequestException(Status.NOT_FOUND, "文件夹不存在")
            }
        }
        return path
    }

    private fun directory(path: Path, segments: List<String>): Response {
        val entries = path.newDirectoryStream().use { stream ->
            stream.mapNotNull { child ->
                val attributes = try {
                    child.readAttributes(BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    return@mapNotNull null
                }
                if (!attributes.isDirectory && !attributes.isRegularFile) {
                    return@mapNotNull null
                }
                HttpServerPage.Entry(
                    child.fileName.toString(), attributes.isDirectory, attributes.size(),
                    attributes.lastModifiedTime().toMillis()
                )
            }.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() }))
        }
        return newFixedLengthResponse(
            Status.OK, "text/html; charset=utf-8", HttpServerPage.render(segments, entries, writable)
        ).apply {
            addHeader(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
                    "connect-src 'self'; img-src blob:; base-uri 'none'; " +
                    "frame-ancestors 'none'; form-action 'none'"
            )
        }
    }

    private fun download(session: IHTTPSession, path: Path, size: Long): Response {
        // A Range on HEAD must be ignored; we do not advertise validators for If-Range.
        val rangeHeader = session.headers["range"].takeIf {
            session.method == Method.GET && !session.headers.containsKey("if-range")
        }
        val range = try {
            HttpByteRange.parse(rangeHeader, size)
        } catch (e: IllegalArgumentException) {
            return message(Status.RANGE_NOT_SATISFIABLE, "请求范围无效").apply {
                addHeader("Content-Range", "bytes */$size")
            }
        }
        val input = if (session.method == Method.HEAD) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            path.newInputStream(NOFOLLOW_LINKS).also { stream ->
                try {
                    var remaining = range?.start ?: 0
                    while (remaining > 0) {
                        val skipped = stream.skip(remaining)
                        if (skipped > 0) {
                            remaining -= skipped
                        } else {
                            if (stream.read() == -1) {
                                throw EOFException()
                            }
                            --remaining
                        }
                    }
                } catch (e: Exception) {
                    stream.close()
                    throw e
                }
            }
        }
        return newFixedLengthResponse(
            if (range != null) Status.PARTIAL_CONTENT else Status.OK,
            "application/octet-stream", input, range?.length ?: size
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader(
                "Content-Disposition",
                "attachment; filename*=UTF-8''${HttpServerPaths.encode(path.fileName.toString())}"
            )
            if (range != null) {
                addHeader("Content-Range", "bytes ${range.start}-${range.end}/$size")
            }
        }
    }

    private fun upload(session: IHTTPSession, path: Path, segments: List<String>): Response {
        if (segments.isEmpty() || session.uri.endsWith('/')) {
            throw RequestException(Status.BAD_REQUEST, "请选择文件名")
        }
        if (session.headers.containsKey("transfer-encoding")
            || session.headers.containsKey("content-range")
        ) {
            throw RequestException(Status.BAD_REQUEST, "请上传完整文件并提供 Content-Length")
        }
        val length = session.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw RequestException(Status.LENGTH_REQUIRED, "缺少有效的 Content-Length")
        var created = false
        try {
            path.newOutputStream(WRITE, CREATE_NEW).use { output ->
                created = true
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val count = session.inputStream.read(
                        buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()
                    )
                    if (count == -1) {
                        throw EOFException("Incomplete upload")
                    }
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        } catch (e: Exception) {
            if (created) {
                runCatching { path.deleteIfExists() }.onFailure { e.addSuppressed(it) }
            }
            throw e
        }
        return message(Status.CREATED, "上传完成").apply {
            addHeader("Location", HttpServerPaths.url(segments, false))
        }
    }

    private fun checkWriteRequest(session: IHTTPSession) {
        if (!writable) {
            throw RequestException(Status.FORBIDDEN, "服务器未允许写入")
        }
        val origin = session.headers["origin"]
        if (origin != null && origin != "http://${session.headers["host"]}") {
            throw RequestException(Status.FORBIDDEN, "不允许跨站修改文件")
        }
        // POST can otherwise be sent by a cross-site form, even without an Origin header.
        // No CORS permission is granted for this custom header or any write method.
        if (session.method != Method.PUT && session.headers["x-material-files"] != "1") {
            throw RequestException(Status.FORBIDDEN, "缺少文件管理请求标识")
        }
    }

    private fun manage(session: IHTTPSession, segments: List<String>): Response {
        val action = session.parameters["action"]?.singleOrNull()
        require(action == "mkdir" || action == "rename")
        val name = session.parameters["name"]?.singleOrNull()
            ?: throw RequestException(Status.BAD_REQUEST, "请输入名称")
        require(name.isNotBlank() && HttpServerPaths.segments("/$name") == listOf(name))
        if (action == "mkdir") {
            val targetSegments = segments + name
            return withChanges(listOf(targetSegments)) {
                val parent = resolve(segments)
                require(parent.isDirectory(NOFOLLOW_LINKS) || (parent == root && root.isDirectory()))
                val target = resolve(targetSegments, true)
                requireMissing(target)
                target.createDirectory()
                message(Status.CREATED, "文件夹已创建").apply {
                    addHeader("Location", HttpServerPaths.url(targetSegments, true))
                }
            }
        }
        require(segments.isNotEmpty()) { "不能重命名根文件夹" }
        require(name != segments.last()) { "名称未更改" }
        val targetSegments = segments.dropLast(1) + name
        return withChanges(listOf(segments, targetSegments)) {
            val source = resolve(segments)
            val target = resolve(targetSegments, true)
            requireMissing(target)
            val attributes = source.readAttributes(BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.isRegularFile || attributes.isDirectory)
            source.moveTo(target)
            message(Status.OK, "重命名完成").apply {
                addHeader("Location", HttpServerPaths.url(targetSegments, attributes.isDirectory))
            }
        }
    }

    private fun requireMissing(path: Path) {
        try {
            path.readAttributes(BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            return
        }
        throw FileAlreadyExistsException(path.toString())
    }

    private fun deleteTree(path: Path) {
        // Iterative postorder traversal also works for providers that only delete empty folders.
        // Read without following links; a nested link is removed, never traversed.
        val pending = ArrayDeque<Pair<Path, Boolean>>()
        pending.addLast(path to false)
        while (pending.isNotEmpty()) {
            val (current, visited) = pending.removeLast()
            val attributes = current.readAttributes(BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isDirectory && !attributes.isSymbolicLink && !visited) {
                pending.addLast(current to true)
                current.newDirectoryStream().use { children ->
                    for (child in children) {
                        pending.addLast(child to false)
                    }
                }
            } else {
                current.delete()
            }
        }
    }

    private fun <T> withChanges(segments: List<List<String>>, action: () -> T): T {
        val paths = segments.map { it.fold(root) { path, name -> path.resolve(name) } }
        synchronized(changingPaths) {
            // Reserve source, destination and descendants until remote writes/cleanup finish.
            // Sibling uploads can run concurrently; moving/deleting their parent cannot.
            if (paths.any { path -> changingPaths.any { it.startsWith(path) || path.startsWith(it) } }) {
                throw RequestException(Status.CONFLICT, "文件或文件夹正在修改，请稍后重试")
            }
            changingPaths.addAll(paths)
        }
        try {
            return action()
        } finally {
            synchronized(changingPaths) { changingPaths.removeAll(paths.toSet()) }
        }
    }

    private fun message(status: Status, text: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", text)

    private class RequestException(val status: Status, message: String) : IOException(message)
}
