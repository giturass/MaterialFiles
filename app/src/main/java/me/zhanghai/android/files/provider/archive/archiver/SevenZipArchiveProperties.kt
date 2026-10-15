/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.IOException
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IOutCreateArchive
import net.sf.sevenzipjbinding.IOutCreateArchiveBZip2
import net.sf.sevenzipjbinding.IOutUpdateArchive
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.util.ByteArrayStream

/** Adapts creation features missing from the fixed Android JBinding Java API. */
internal object SevenZipArchiveProperties {
    private var loaded = false

    /**
     * Upstream added openOutArchiveXz() in commit 7b4c4d3d59f3a6800ab4f7524c929893552c15d6.
     * Its XZ writer uses the same level setting and pathless single-stream items as BZip2.
     * The pinned Android library already has that native writer, but no XZ Java enum/factory.
     * Obtain its IOutArchive through a small in-memory XZ reader and transfer the reference into
     * a new BZip2 callback session. Only its Java metadata contract remains BZip2; the native
     * handler does all compression as XZ. Neither the global enum nor other writers are changed.
     */
    @Throws(IOException::class)
    fun openXzArchive(): IOutCreateArchiveBZip2 {
        ensureLoaded()
        val archive = SevenZip.openOutArchiveBZip2()
        try {
            SevenZip.openInArchive(null, ByteArrayStream(EMPTY_XZ, true)).use { inputArchive ->
                try {
                    nativeUseXzHandler(archive, inputArchive)
                } catch (e: LinkageError) {
                    throw IOException("Unable to initialize the 7-Zip XZ writer", e)
                }
            }
            // Closing the reader clears its input stream and session; the writer owns its own
            // native reference and JBinding session until the normal create/close operation.
            return archive
        } catch (e: Throwable) {
            try {
                archive.close()
            } catch (closeFailure: Throwable) {
                e.addSuppressed(closeFailure)
            }
            throw e
        }
    }

    /**
     * Use only on a newly opened writer, before createArchive(), without JBinding feature setters.
     * In Release-16.02-2.03 those setters each reset the other native properties, and the Java ZIP
     * interface has no encryption-method setter. The bridge configures the existing upstream engine.
     */
    @Throws(IOException::class)
    fun configure(
        archive: IOutCreateArchive<*>,
        level: Int,
        encryptHeaders: Boolean = false,
        zipAes: Boolean = false
    ) {
        if (level !in 0..9) throw IOException("Invalid archive compression level: $level")
        when (archive.archiveFormat) {
            ArchiveFormat.SEVEN_ZIP -> if (zipAes) {
                throw IOException("ZIP encryption was requested for a 7z writer")
            }
            ArchiveFormat.ZIP -> if (encryptHeaders) {
                throw IOException("ZIP does not support encrypted file names")
            }
            else -> throw IOException("Unsupported archive properties: ${archive.archiveFormat}")
        }
        if ((archive as? IOutUpdateArchive<*>)?.connectedInArchive != null) {
            throw IOException("Archive creation properties require a new writer")
        }
        ensureLoaded()
        try {
            nativeConfigure(archive, level, encryptHeaders, zipAes)
        } catch (e: LinkageError) {
            throw IOException("Unable to configure 7-Zip-JBinding archive properties", e)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun ensureLoaded() {
        if (loaded) return
        SevenZipRuntime.ensureInitialized()
        // The native interface layout and Java handle field are pinned to this upstream release:
        // https://github.com/omicronapps/7-Zip-JBinding-4Android/tree/
        // 875f38aac441f41e6eb693177e020e97971dca97
        val version = SevenZip.getSevenZipVersion()
        if (SevenZip.getSevenZipJBindingVersion() != "16.02-2.03"
            || version.major != 16 || version.minor != 2) {
            throw IOException("Unsupported 7-Zip-JBinding version for archive creation features")
        }
        try {
            System.loadLibrary("archive_jbinding")
        } catch (e: LinkageError) {
            throw IOException("Unable to load 7-Zip-JBinding archive creation features", e)
        }
        loaded = true
    }

    @Throws(IOException::class)
    private external fun nativeConfigure(
        archive: IOutCreateArchive<*>, level: Int, encryptHeaders: Boolean, zipAes: Boolean
    )

    @Throws(IOException::class)
    private external fun nativeUseXzHandler(
        archive: IOutCreateArchive<*>, inputArchive: IInArchive
    )

    // Valid empty XZ stream with CRC64; used only to obtain the bundled native XZ handler.
    private val EMPTY_XZ = byteArrayOf(
        0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00, 0x00, 0x04,
        0xE6.toByte(), 0xD6.toByte(), 0xB4.toByte(), 0x46, 0x00, 0x00, 0x00, 0x00,
        0x1C, 0xDF.toByte(), 0x44, 0x21, 0x1F, 0xB6.toByte(), 0xF3.toByte(), 0x7D,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x04, 0x59, 0x5A
    )
}
