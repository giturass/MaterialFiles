package me.zhanghai.android.files.filejob

import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/** Disk state survives process death; leases only describe work in this process. */
internal class OpenCacheStore(
    private val roots: () -> List<File>,
    private val now: () -> Long = System::currentTimeMillis,
    private val onRemove: (String) -> Unit = {},
    private val onError: (Exception) -> Unit = { it.printStackTrace() }
) {
    private val lock = Any()
    private val active = mutableMapOf<File, Int>()
    private val recentAccess = mutableMapOf<File, Long>()

    fun contains(file: File): Boolean = findEntry(file) != null

    fun create(root: File): Session = synchronized(lock) {
        val directory = File(managedDirectory(root), UUID.randomUUID().toString())
        Files.createDirectory(directory.toPath())
        try {
            val files = File(directory, "files")
            Files.createDirectory(files.toPath())
            active[directory] = 1
            Session(directory, files)
        } catch (e: Exception) {
            removeTree(directory)
            throw e
        }
    }

    /** Acquiring and retiring an entry share a lock, so cleanup cannot delete a new lease. */
    fun acquire(file: File): Closeable? = synchronized(lock) {
        val entry = findEntry(file) ?: return null
        validatePath(file)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)
            || (isManaged(entry) && (entry.name.startsWith(TRASH_PREFIX)
                || !Files.isRegularFile(File(entry, ACCESS_FILE).toPath(), NOFOLLOW_LINKS)))) {
            throw FileNotFoundException(file.toString())
        }
        touch(entry)
        active[entry] = (active[entry] ?: 0) + 1
        var closed = false
        Closeable {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    // Start a fresh grace period when the last reader/viewer finishes.
                    touch(entry)
                    release(entry)
                }
            }
        }
    }

    fun cleanup() {
        for (root in roots()) {
            if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) continue
            for (entry in root.listFiles().orEmpty()) {
                if (entry.name == MANAGED_DIRECTORY
                    && Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS)) {
                    entry.listFiles().orEmpty().forEach { cleanupEntry(root, it, true) }
                } else {
                    cleanupEntry(root, entry, false)
                }
            }
        }
    }

    private fun cleanupEntry(root: File, entry: File, managed: Boolean) {
        try {
            // Do not inspect an unfinished copy that could be completed before retirement.
            synchronized(lock) {
                if (active.containsKey(entry)) return
            }
            // Scan directory contents without holding the lease lock.
            val legacyTimestamp = if (managed) null else legacyTime(entry)
            val deleting = managed && entry.name.startsWith(TRASH_PREFIX)
            val hasPayload = !managed || deleting || hasPayload(entry)
            val retired = synchronized(lock) {
                if (active.containsKey(entry)) return
                if (deleting) {
                    entry
                } else {
                    // Metadata and empty directories alone do not need a grace period.
                    if (hasPayload) {
                        val diskTime = if (managed) readAccessTime(entry) else legacyTimestamp
                        val timestamp = listOfNotNull(diskTime, recentAccess[entry]).maxOrNull()
                        // An incomplete managed session has never been handed to another app.
                        if (timestamp != null && now() - timestamp < RETENTION_MILLIS) return
                    }
                    retire(root, entry)
                }
            }
            // Potentially large trees are removed without holding the lease lock.
            removeTree(retired)
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun findEntry(file: File): File? {
        val path = file.toPath().toAbsolutePath().normalize()
        for (root in roots()) {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            if (!path.startsWith(rootPath) || path == rootPath) continue
            val relative = rootPath.relativize(path)
            return if (relative.getName(0).toString() == MANAGED_DIRECTORY) {
                if (relative.nameCount < 2) null
                else rootPath.resolve(relative.subpath(0, 2)).toFile()
            } else {
                rootPath.resolve(relative.getName(0)).toFile()
            }
        }
        return null
    }

    private fun managedDirectory(root: File): File {
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(root.toPath())
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Invalid open cache root: $root")
        }
        val directory = File(root, MANAGED_DIRECTORY)
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(directory.toPath())
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Invalid open cache directory: $directory")
        }
        return directory
    }

    private fun isManaged(entry: File): Boolean = entry.parentFile.name == MANAGED_DIRECTORY

    private fun validatePath(file: File) {
        val path = file.toPath().toAbsolutePath().normalize()
        val root = roots().map { it.toPath().toAbsolutePath().normalize() }
            .first { path.startsWith(it) }
        var current = root
        if (Files.isSymbolicLink(current)) throw FileNotFoundException(file.toString())
        for (name in root.relativize(path)) {
            current = current.resolve(name)
            if (Files.isSymbolicLink(current)) throw FileNotFoundException(file.toString())
        }
    }

    /** Move away from public paths while locked; a concurrent acquire will then fail cleanly. */
    private fun retire(root: File, entry: File): File {
        if (isManaged(entry)) {
            try {
                onRemove(tagFor(entry))
            } catch (e: Exception) {
                onError(e)
            }
        }
        val trash = File(managedDirectory(root), TRASH_PREFIX + UUID.randomUUID())
        Files.move(entry.toPath(), trash.toPath(), ATOMIC_MOVE)
        recentAccess.remove(entry)
        return trash
    }

    private fun readAccessTime(entry: File): Long? {
        val metadata = File(entry, ACCESS_FILE)
        if (!Files.exists(metadata.toPath(), NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(metadata.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Invalid open cache metadata: $metadata")
        }
        // Invalid metadata is not evidence that a file is safe to delete.
        return metadata.readText().toLongOrNull()
            ?: throw IOException("Invalid open cache timestamp: $metadata")
    }

    private fun writeAccessTime(entry: File) {
        validatePath(entry)
        val temporary = File(entry, "$ACCESS_FILE.tmp")
        if (Files.isSymbolicLink(temporary.toPath())) {
            throw IOException("Invalid open cache metadata: $temporary")
        }
        temporary.writeText(now().toString())
        Files.move(
            temporary.toPath(), File(entry, ACCESS_FILE).toPath(), ATOMIC_MOVE, REPLACE_EXISTING
        )
    }

    private fun touch(entry: File) {
        recentAccess[entry] = now()
        try {
            if (isManaged(entry)) {
                writeAccessTime(entry)
            } else {
                // Legacy files have no metadata. Refresh their timestamp on actual use.
                Files.setLastModifiedTime(entry.toPath(), FileTime.fromMillis(now()))
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun legacyTime(entry: File): Long {
        var time = 0L
        Files.walkFileTree(entry.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                time = maxOf(time, attributes.lastModifiedTime().toMillis())
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                time = maxOf(time, attributes.lastModifiedTime().toMillis())
                return FileVisitResult.CONTINUE
            }
        })
        return time
    }

    private fun hasPayload(entry: File): Boolean {
        val entryPath = entry.toPath()
        var found = false
        Files.walkFileTree(entryPath, object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                // Only the session's own metadata is bookkeeping, even if payload names match.
                if (path.parent == entryPath && attributes.isRegularFile
                    && (path.fileName.toString() == ACCESS_FILE
                        || path.fileName.toString() == "$ACCESS_FILE.tmp")) {
                    return FileVisitResult.CONTINUE
                }
                found = true
                return FileVisitResult.TERMINATE
            }
        })
        return found
    }

    private fun release(entry: File) {
        val count = checkNotNull(active[entry])
        if (count == 1) active.remove(entry) else active[entry] = count - 1
    }

    private fun removeTree(directory: File) {
        try {
            // walkFileTree does not follow symbolic links, including a symlink at the root.
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(path: Path, exception: IOException?): FileVisitResult {
                    if (exception != null) throw exception
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            onError(e)
        }
    }

    inner class Session internal constructor(
        private val directory: File,
        val files: File
    ) : Closeable {
        val notificationTag: String get() = tagFor(directory)
        private var retained = false
        private var closed = false

        fun prepareToOpen() = synchronized(lock) {
            check(!closed)
            writeAccessTime(directory)
        }

        fun retain() = synchronized(lock) {
            check(!closed && File(directory, ACCESS_FILE).isFile)
            retained = true
        }

        override fun close() {
            val retired = synchronized(lock) {
                if (closed) return
                closed = true
                if (retained) touch(directory)
                release(directory)
                if (retained || active.containsKey(directory)) return
                try {
                    // If deleting fails, the missing marker lets the next cleanup retry at once.
                    Files.deleteIfExists(File(directory, ACCESS_FILE).toPath())
                    recentAccess.remove(directory)
                    retire(directory.parentFile.parentFile, directory)
                } catch (e: Exception) {
                    onError(e)
                    null
                }
            }
            retired?.let(::removeTree)
        }
    }

    companion object {
        const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        private const val MANAGED_DIRECTORY = ".managed-v1"
        private const val ACCESS_FILE = "last-used"
        private const val TRASH_PREFIX = ".deleting-"

        private fun tagFor(entry: File): String = "open-cache:${entry.name}"
    }
}
