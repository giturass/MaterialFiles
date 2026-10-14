/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.IOException
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.IOutCreateArchive
import net.sf.sevenzipjbinding.IOutUpdateArchive
import net.sf.sevenzipjbinding.SevenZip

/** Applies the fixed JBinding version's writer properties in one ISetProperties call. */
internal object SevenZipArchiveProperties {
    private var loaded = false

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
            throw IOException("Unsupported 7-Zip-JBinding version for archive properties")
        }
        try {
            System.loadLibrary("archive_jbinding")
        } catch (e: LinkageError) {
            throw IOException("Unable to load 7-Zip-JBinding archive properties", e)
        }
        loaded = true
    }

    @Throws(IOException::class)
    private external fun nativeConfigure(
        archive: IOutCreateArchive<*>, level: Int, encryptHeaders: Boolean, zipAes: Boolean
    )
}
