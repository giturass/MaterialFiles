/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.provider.archive.archiver

import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.util.Collections
import java.util.IdentityHashMap

/** Serializes the binding's first initialization, including calls from concurrent file jobs. */
internal object SevenZipRuntime {
    private var initialized = false

    @Synchronized
    @Throws(IOException::class)
    fun ensureInitialized() {
        if (initialized) return
        try {
            SevenZip.getSevenZipVersion()
            initialized = true
        } catch (e: Exception) {
            throw IOException("Unable to initialize 7-Zip-JBinding", e)
        } catch (e: LinkageError) {
            throw IOException("Unable to load 7-Zip-JBinding", e)
        }
    }
}

internal class ArchivePassphraseException(
    val incorrect: Boolean,
    cause: Throwable? = null
) : IOException(
    if (incorrect) "Incorrect passphrase" else "Passphrase required for this entry", cause
)

/** JNI can retain callback errors on any of four cause branches, including other threads. */
internal fun SevenZipException.toIOException(): IOException {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = java.util.ArrayDeque<Throwable>()
    val causes = mutableListOf<Throwable>()
    pending.add(this)
    while (!pending.isEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        causes += current
        current.cause?.let { pending.add(it) }
        if (current is SevenZipException) {
            current.causeLastThrown?.let { pending.add(it) }
            current.causeFirstPotentialThrown?.let { pending.add(it) }
            current.causeLastPotentialThrown?.let { pending.add(it) }
        }
    }
    causes.filterIsInstance<InterruptedIOException>().firstOrNull()?.let { return it }
    causes.filterIsInstance<ClosedByInterruptException>().firstOrNull()?.let {
        return InterruptedIOException().apply { initCause(it) }
    }
    causes.filterIsInstance<ArchivePassphraseException>().firstOrNull()?.let { return it }
    causes.filterIsInstance<IOException>().firstOrNull { it !is SevenZipException }?.let {
        return it
    }
    return IOException(message ?: "7-Zip archive operation failed", this)
}
