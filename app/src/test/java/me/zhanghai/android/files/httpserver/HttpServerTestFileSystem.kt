package me.zhanghai.android.files.httpserver

import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption.CREATE_NEW
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.FileTime
import java8.nio.file.spi.FileSystemProvider
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/** Models a remote provider whose new files become visible only when the writer closes. */
internal class HttpServerTestFileSystem : FileSystem() {
    val files = ConcurrentHashMap<String, ByteArray>()
    var onOpen: (String) -> Unit = {}
    private val directories = setOf("/", "/folder")

    private val provider = object : FileSystemProvider() {
        override fun getScheme(): String = "http-test"

        override fun newInputStream(path: Path, vararg options: OpenOption): InputStream =
            (files[path.toString()] ?: throw NoSuchFileException(path.toString())).inputStream()

        override fun newOutputStream(path: Path, vararg options: OpenOption): OutputStream {
            val name = path.toString()
            if (CREATE_NEW in options && (files.containsKey(name) || name in directories)) {
                throw FileAlreadyExistsException(name)
            }
            onOpen(name)
            return object : ByteArrayOutputStream() {
                override fun close() {
                    files[name] = toByteArray()
                    super.close()
                }
            }
        }

        override fun newDirectoryStream(
            dir: Path, filter: DirectoryStream.Filter<in Path>
        ): DirectoryStream<Path> {
            val children = (directories + files.keys).filter {
                Paths.get(it).parent?.toString() == dir.toString()
            }.map { this@HttpServerTestFileSystem.getPath(it) }.filter { filter.accept(it) }
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = children.toMutableList().iterator()
                override fun close() {}
            }
        }

        override fun <A : BasicFileAttributes> readAttributes(
            path: Path, type: Class<A>, vararg options: LinkOption
        ): A {
            val name = path.toString()
            val directory = name in directories
            val bytes = files[name]
            if (!directory && bytes == null) {
                throw NoSuchFileException(name)
            }
            return type.cast(object : BasicFileAttributes {
                override fun isDirectory(): Boolean = directory
                override fun isRegularFile(): Boolean = !directory
                override fun isSymbolicLink(): Boolean = false
                override fun isOther(): Boolean = false
                override fun size(): Long = bytes?.size?.toLong() ?: 0
                override fun fileKey(): Any = name
                override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)
                override fun lastAccessTime(): FileTime = lastModifiedTime()
                override fun creationTime(): FileTime = lastModifiedTime()
            })
        }

        override fun delete(path: Path) {
            if (files.remove(path.toString()) == null) {
                throw NoSuchFileException(path.toString())
            }
        }

        override fun newFileSystem(uri: URI, env: Map<String, *>): Nothing = unsupported()
        override fun getFileSystem(uri: URI): FileSystem = this@HttpServerTestFileSystem
        override fun getPath(uri: URI): Path = this@HttpServerTestFileSystem.getPath(uri.path)
        override fun newByteChannel(
            path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>
        ): Nothing = unsupported()
        override fun createDirectory(path: Path, vararg attrs: FileAttribute<*>): Nothing = unsupported()
        override fun copy(source: Path, target: Path, vararg options: CopyOption): Nothing = unsupported()
        override fun move(source: Path, target: Path, vararg options: CopyOption): Nothing = unsupported()
        override fun isSameFile(path: Path, other: Path): Boolean = path == other
        override fun isHidden(path: Path): Boolean = false
        override fun getFileStore(path: Path): Nothing = unsupported()
        override fun checkAccess(path: Path, vararg modes: AccessMode): Nothing = unsupported()
        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path, type: Class<V>, vararg options: LinkOption
        ): V? = null
        override fun readAttributes(
            path: Path, attributes: String, vararg options: LinkOption
        ): Nothing = unsupported()
        override fun setAttribute(
            path: Path, attribute: String, value: Any, vararg options: LinkOption
        ): Nothing = unsupported()
    }

    override fun getPath(first: String, vararg more: String): Path {
        val nioPath = Paths.get(first, *more).normalize()
        return Proxy.newProxyInstance(Path::class.java.classLoader, arrayOf(Path::class.java)) {
                _, method, args ->
            when (method.name) {
                "toString" -> nioPath.toString()
                "getFileSystem" -> this
                "toAbsolutePath" -> getPath(nioPath.toAbsolutePath().toString())
                "normalize" -> getPath(nioPath.toString())
                "resolve" -> getPath(nioPath.resolve(args!![0].toString()).toString())
                "getFileName" -> nioPath.fileName?.let { getPath(it.toString()) }
                "startsWith" -> nioPath.startsWith(args!![0].toString())
                "hashCode" -> nioPath.hashCode()
                "equals" -> (args!![0] as? Path)?.let {
                    it.fileSystem === this && it.toString() == nioPath.toString()
                } == true
                else -> error("Unexpected Path method: ${method.name}")
            }
        } as Path
    }

    override fun provider(): FileSystemProvider = provider
    override fun isOpen(): Boolean = true
    override fun isReadOnly(): Boolean = false
    override fun getSeparator(): String = "/"
    override fun getRootDirectories(): Iterable<Path> = listOf(getPath("/"))
    override fun supportedFileAttributeViews(): Set<String> = setOf("basic")
    override fun close() {}
    override fun getFileStores(): Nothing = unsupported()
    override fun getPathMatcher(syntaxAndPattern: String): Nothing = unsupported()
    override fun getUserPrincipalLookupService(): Nothing = unsupported()
    override fun newWatchService(): Nothing = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}
