package me.zhanghai.android.files.filejob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OpenCacheStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var internal: File
    private lateinit var external: File
    private lateinit var store: OpenCacheStore
    private var time = 1_700_000_000_000L
    private val removed = mutableListOf<String>()
    private val errors = mutableListOf<Exception>()

    @Before
    fun setUp() {
        internal = File(temporary.newFolder("internal"), "open_cache")
        external = File(temporary.newFolder("external"), "open_cache")
        store = newStore()
    }

    private fun newStore(onRemove: (String) -> Unit = { removed += it }) = OpenCacheStore(
        { listOf(internal, external) }, { time }, onRemove, { errors += it }
    )

    private fun ready(root: File = internal, name: String = "中文 test.txt"): File {
        store.create(root).use { session ->
            val file = File(session.files, name).apply { writeText("preview") }
            session.prepareToOpen()
            session.retain()
            return file
        }
    }

    @Test
    fun expiresAt24HoursAndCancelsItsNotification() {
        val file = ready()
        time += TimeUnit.HOURS.toMillis(24) - 1
        store.cleanup()
        assertTrue(file.exists())
        assertTrue(removed.isEmpty())
        time++
        store.cleanup()
        assertFalse(file.exists())
        assertTrue(file.parentFile.parentFile.parentFile.list()!!.isEmpty())
        assertEquals(listOf("open-cache:${file.parentFile.parentFile.name}"), removed)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun missingPayloadRemovesEmptyDirectoriesAndMetadataImmediately() {
        val tags = mutableListOf<String>()
        for (root in listOf(internal, external)) {
            val file = ready(root)
            val directory = file.parentFile.parentFile
            tags += "open-cache:${directory.name}"
            assertTrue(file.delete())
            assertTrue(File(file.parentFile, "nested/empty").mkdirs())
            File(directory, "last-used.tmp").writeText(time.toString())
            store.cleanup()
            assertFalse(directory.exists())
            assertTrue(directory.parentFile.list()!!.isEmpty())
        }
        assertEquals(tags, removed)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun metadataAloneDoesNotKeepAnEmptySession() {
        for (metadata in listOf(time.toString(), "broken")) {
            val file = ready()
            val directory = file.parentFile.parentFile
            assertTrue(file.delete())
            assertTrue(file.parentFile.delete())
            File(directory, "last-used").writeText(metadata)
            store.cleanup()
            assertFalse(directory.exists())
            assertTrue(directory.parentFile.list()!!.isEmpty())
        }
        assertEquals(2, removed.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun activeEmptyCopiesArePreservedAndCanFinish() {
        val session = store.create(internal)
        store.cleanup()
        assertTrue(session.files.isDirectory)
        val file = File(session.files, "preview.txt").apply { writeText("preview") }
        session.prepareToOpen()
        session.retain()
        session.close()
        store.cleanup()
        assertEquals("preview", file.readText())
        assertTrue(removed.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun emptySessionIsProtectedUntilItsLastReaderCloses() {
        val file = ready()
        val directory = file.parentFile.parentFile
        val first = checkNotNull(store.acquire(file))
        val second = checkNotNull(store.acquire(file))
        assertTrue(file.delete())
        store.cleanup()
        assertTrue(directory.exists())
        first.close()
        store.cleanup()
        assertTrue(directory.exists())
        second.close()
        store.cleanup()
        assertFalse(directory.exists())
        assertTrue(directory.parentFile.list()!!.isEmpty())
        assertEquals(listOf("open-cache:${directory.name}"), removed)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun zeroBytePayloadsAreRetainedEvenWhenTheirNamesMatchMetadata() {
        val files = listOf("empty.txt", "last-used", "last-used.tmp").map { name ->
            ready(name = name).apply { writeText("") }
        }
        store.cleanup()
        for (file in files) {
            assertTrue(file.isFile)
            assertEquals(0L, file.length())
        }
        assertTrue(removed.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun hasNoCapacityLimit() {
        val file = ready()
        val size = 2L * 1024 * 1024 * 1024
        RandomAccessFile(file, "rw").use { it.setLength(size) }
        val second = ready()
        store.cleanup()
        assertEquals(size, file.length())
        assertTrue(second.exists())
    }

    @Test
    fun sameNamesHaveIndependentContentsAndLifetimes() {
        val first = ready()
        time += OpenCacheStore.RETENTION_MILLIS / 2
        val second = ready().apply { writeText("second") }
        assertNotEquals(first, second)
        assertEquals(first.name, second.name)
        assertEquals("preview", first.readText())
        time += OpenCacheStore.RETENTION_MILLIS / 2
        store.cleanup()
        assertFalse(first.exists())
        assertEquals("second", second.readText())
    }

    @Test
    fun failedAndCanceledTasksRemoveTheirPartialOutput() {
        for (failure in listOf(IOException("copy failed"), InterruptedIOException("canceled"))) {
            val session = store.create(internal)
            val file = File(session.files, "partial").apply { writeText("incomplete") }
            assertThrows(IOException::class.java) { session.use { throw failure } }
            assertFalse(file.parentFile.parentFile.exists())
            assertTrue(file.parentFile.parentFile.parentFile.list()!!.isEmpty())
            session.close() // Closing twice must be harmless.
        }
    }

    @Test
    fun failedHandoffRemovesEvenACompletedCopy() {
        val session = store.create(internal)
        val file = File(session.files, "ready").apply { writeText("complete") }
        session.prepareToOpen()
        session.close() // Activity/notification did not accept it.
        assertFalse(file.exists())
        assertEquals(listOf(session.notificationTag), removed)
    }

    @Test
    fun interruptedCopiesAreProtectedWhileRunningAndRecoveredAfterRestart() {
        val session = store.create(internal)
        val file = File(session.files, "partial").apply { writeText("incomplete") }
        time += OpenCacheStore.RETENTION_MILLIS * 2
        store.cleanup()
        assertTrue(file.exists())
        // A new store represents a new process: the old writer no longer exists.
        newStore().cleanup()
        assertFalse(file.exists())
    }

    @Test
    fun interruptedCopiesDoNotNeedToWait24HoursAfterRestart() {
        val session = store.create(internal)
        val file = File(session.files, "partial").apply { writeText("incomplete") }
        newStore().cleanup()
        assertFalse(file.exists())
    }

    @Test
    fun everyReaderMustCloseAndTheLastCloseStartsANewGracePeriod() {
        val file = ready()
        val first = checkNotNull(store.acquire(file))
        val second = checkNotNull(store.acquire(file))
        time += OpenCacheStore.RETENTION_MILLIS * 2
        first.close()
        first.close()
        time += OpenCacheStore.RETENTION_MILLIS * 2
        store.cleanup()
        assertTrue(file.exists())
        second.close()
        time += OpenCacheStore.RETENTION_MILLIS - 1
        store.cleanup()
        assertTrue(file.exists())
        time++
        store.cleanup()
        assertFalse(file.exists())
    }

    @Test
    fun actualAccessPersistsAcrossRestartAndIgnoresPayloadModificationTime() {
        val file = ready()
        assertTrue(file.setLastModified(0))
        time += OpenCacheStore.RETENTION_MILLIS - 1
        store.acquire(file)!!.close()
        time += OpenCacheStore.RETENTION_MILLIS - 1
        store = newStore()
        store.cleanup()
        assertTrue(file.exists())
        // A future archive timestamp must not keep a managed preview forever either.
        assertTrue(file.setLastModified(time + OpenCacheStore.RETENTION_MILLIS * 10))
        time++
        store.cleanup()
        assertFalse(file.exists())
    }

    @Test
    fun handlesBothCacheRootsAndLegacyFilesWithoutTouchingOtherCaches() {
        assertTrue(internal.mkdir())
        assertTrue(external.mkdir())
        val oldFiles = listOf(internal, external).map {
            File(it, "old.txt").apply {
                writeText("old")
                assertTrue(setLastModified(time - OpenCacheStore.RETENTION_MILLIS))
            }
        }
        val fresh = File(external, "recent.txt").apply {
            writeText("recent")
            assertTrue(setLastModified(time))
        }
        val unrelated = File(internal.parentFile, "thumbnail").apply { writeText("unrelated") }
        store.cleanup()
        assertTrue(oldFiles.none { it.exists() })
        assertTrue(fresh.exists())
        assertEquals("unrelated", unrelated.readText())
    }

    @Test
    fun legacyDirectoriesUseTheNewestChildTimestamp() {
        val directory = File(internal, "old-directory").apply { mkdirs() }
        val file = File(directory, "recent.txt").apply {
            writeText("recent")
            assertTrue(setLastModified(time))
        }
        assertTrue(directory.setLastModified(time - OpenCacheStore.RETENTION_MILLIS * 2))
        store.cleanup()
        assertTrue(file.exists())
        time += OpenCacheStore.RETENTION_MILLIS
        store.cleanup()
        assertFalse(directory.exists())
    }

    @Test
    fun legacyReadersAlsoRefreshTheirRetention() {
        assertTrue(internal.mkdir())
        val file = File(internal, "old.txt").apply {
            writeText("old")
            assertTrue(setLastModified(time - OpenCacheStore.RETENTION_MILLIS))
        }
        store.acquire(file)!!.use {
            store.cleanup()
            assertTrue(file.exists())
        }
        newStore().cleanup()
        assertTrue(file.exists())
    }

    @Test
    fun cleanupDoesNotFollowSymlinksAndReadersCannotUseThem() {
        val outside = temporary.newFolder("outside")
        val original = File(outside, "original").apply { writeText("keep me") }
        val session = store.create(internal)
        val link = File(session.files, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        session.prepareToOpen()
        session.retain()
        session.close()
        assertThrows(FileNotFoundException::class.java) {
            store.acquire(File(link, "original"))
        }
        time += OpenCacheStore.RETENTION_MILLIS
        store.cleanup()
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep me", original.readText())
        assertEquals(listOf("original"), outside.list()!!.toList())
    }

    @Test
    fun refusesACacheRootThatIsASymlink() {
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(internal.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { store.create(internal) }
        assertTrue(outside.list()!!.isEmpty())
    }

    @Test
    fun similarlyNamedDirectoriesAreNotCacheEntries() {
        val other = File(internal.parentFile, "open_cache_other/file.txt")
        assertFalse(store.contains(other))
        assertNull(store.acquire(other))
    }

    @Test
    fun badMetadataDoesNotDeleteTheEntryOrBlockOtherCleanup() {
        val file = ready()
        File(file.parentFile.parentFile, "last-used").writeText("broken")
        val other = ready()
        time += OpenCacheStore.RETENTION_MILLIS
        store.cleanup()
        assertTrue(file.exists())
        assertFalse(other.exists())
        assertEquals(1, errors.size)
        // Actual use can repair the metadata.
        store.acquire(file)!!.close()
        time += OpenCacheStore.RETENTION_MILLIS
        store.cleanup()
        assertFalse(file.exists())
    }

    @Test
    fun movingTheClockBackDoesNotPrematurelyDeletePreviews() {
        val file = ready()
        time -= OpenCacheStore.RETENTION_MILLIS * 2
        store.cleanup()
        assertTrue(file.exists())
    }

    @Test
    fun aReaderRacingRetirementNeverAcquiresADeletedEntry() {
        val file = ready()
        time += OpenCacheStore.RETENTION_MILLIS
        val retiring = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val attemptingRead = CountDownLatch(1)
        store = newStore {
            retiring.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val cleanup = executor.submit { store.cleanup() }
            assertTrue(retiring.await(5, TimeUnit.SECONDS))
            val read = executor.submit<Boolean> {
                attemptingRead.countDown()
                try {
                    store.acquire(file)?.close()
                    false
                } catch (e: FileNotFoundException) {
                    true
                }
            }
            assertTrue(attemptingRead.await(5, TimeUnit.SECONDS))
            proceed.countDown()
            cleanup.get(5, TimeUnit.SECONDS)
            assertTrue(read.get(5, TimeUnit.SECONDS))
            assertFalse(file.exists())
        } finally {
            proceed.countDown()
            executor.shutdownNow()
        }
    }
}
