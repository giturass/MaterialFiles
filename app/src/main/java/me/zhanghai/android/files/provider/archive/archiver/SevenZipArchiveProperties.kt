/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.IOException
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.IOutCreateArchive
import net.sf.sevenzipjbinding.IOutUpdateArchive

/** Applies creation properties together using the same official headers as the bundled engine. */
internal object SevenZipArchiveProperties {
    private var loaded = false

    /**
     * Use only on a newly opened writer, before createArchive(), without JBinding feature setters.
     * The 7-Zip handlers reset properties on every SetProperties call. This sets the level,
     * mobile dictionary limit and encryption together, including ZIP's explicit AES-256 method.
     */
    @Throws(IOException::class)
    fun configure(
        archive: IOutCreateArchive<*>,
        level: Int,
        encryptHeaders: Boolean = false,
        zipAes: Boolean = false
    ) {
        if (level !in 0..9) throw IOException("Invalid archive compression level: $level")
        val format = when (archive.archiveFormat) {
            ArchiveFormat.SEVEN_ZIP -> {
                if (zipAes) throw IOException("ZIP encryption was requested for a 7z writer")
                0
            }
            ArchiveFormat.ZIP -> {
                if (encryptHeaders) throw IOException("ZIP does not support encrypted file names")
                1
            }
            ArchiveFormat.XZ -> {
                if (encryptHeaders || zipAes) throw IOException("XZ does not support encryption")
                2
            }
            else -> throw IOException("Unsupported archive properties: ${archive.archiveFormat}")
        }
        if ((archive as? IOutUpdateArchive<*>)?.connectedInArchive != null) {
            throw IOException("Archive creation properties require a new writer")
        }
        ensureLoaded()
        try {
            nativeConfigure(archive, format, level, encryptHeaders, zipAes)
        } catch (e: LinkageError) {
            throw IOException("Unable to configure 7-Zip-JBinding archive properties", e)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun ensureLoaded() {
        if (loaded) return
        SevenZipRuntime.ensureInitialized()
        try {
            System.loadLibrary("archive_jbinding")
            // The old adapter used a different COM layout and nativeConfigure signature.
            if (nativeGetInterfaceVersion() != 1) {
                throw IOException("Unsupported 7-Zip archive property adapter version")
            }
        } catch (e: LinkageError) {
            throw IOException("Unable to load 7-Zip-JBinding archive creation features", e)
        }
        loaded = true
    }

    @Throws(IOException::class)
    private external fun nativeGetInterfaceVersion(): Int

    @Throws(IOException::class)
    private external fun nativeConfigure(
        archive: IOutCreateArchive<*>, format: Int, level: Int,
        encryptHeaders: Boolean, zipAes: Boolean
    )
}
