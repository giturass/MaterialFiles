/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.compat.use
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isMedia
import me.zhanghai.android.files.file.isPdf
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.filelist.isRemotePath
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.content.resolver.ResolverException
import me.zhanghai.android.files.provider.document.documentSupportsThumbnail
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.document.resolver.DocumentResolver
import me.zhanghai.android.files.provider.ftp.isFtpPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.isGetPackageArchiveInfoCompatible
import me.zhanghai.android.files.util.isMediaMetadataRetrieverCompatible
import me.zhanghai.android.files.util.runWithCancellationSignal
import me.zhanghai.android.files.util.setDataSource
import me.zhanghai.android.files.util.valueCompat
import okio.buffer
import okio.source
import me.zhanghai.android.files.util.setDataSource as appSetDataSource

class PathAttributesKeyer : Keyer<Pair<Path, BasicFileAttributes>> {
    override fun key(data: Pair<Path, BasicFileAttributes>, options: Options): String {
        val (path, attributes) = data
        return pathAttributesImageCacheKey(path, attributes)
    }
}

class PathAttributesFetcher(
    private val data: Pair<Path, BasicFileAttributes>,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val apkIconFetcherFactory: ApkIconFetcher.Factory,
    private val videoFrameFetcherFactory: VideoFrameFetcher.Factory<Path>,
    private val pdfPageFetcherFactory: PdfPageFetcher.Factory<Path>
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val (path, attributes) = data
        val mimeType = AndroidFileTypeDetector.getMimeType(path, attributes).asMimeType()
        val (width, height) = options.size
        // @see android.provider.MediaStore.ThumbnailConstants.MINI_SIZE
        val isThumbnail = width is Dimension.Pixels && width.px <= 512
            && height is Dimension.Pixels && height.px <= 384
        if (isThumbnail) {
            width as Dimension.Pixels
            height as Dimension.Pixels
            // APKs use their original icon layers; a provider thumbnail may already contain
            // the device's rounded-rectangle mask and cannot be reshaped correctly afterwards.
            if (!mimeType.isApk && path.isDocumentPath && attributes.documentSupportsThumbnail) {
                val thumbnail = runWithCancellationSignal { signal ->
                    try {
                        DocumentResolver.getThumbnail(
                            path as DocumentResolver.Path, width.px, height.px, signal
                        )
                    } catch (e: ResolverException) {
                        e.printStackTrace()
                        null
                    }
                }
                if (thumbnail != null) {
                    return DrawableResult(
                        thumbnail.toDrawable(options.context.resources), true, path.dataSource
                    )
                }
            }
            if (path.isRemotePath) {
                // FTP doesn't support random access and requires one connection per parallel read.
                val shouldReadRemotePath = !path.isFtpPath
                    && Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.valueCompat
                if (!shouldReadRemotePath) {
                    error("Cannot read $path for thumbnail")
                }
            }
        }
        when {
            mimeType.isApk && path.isGetPackageArchiveInfoCompatible -> {
                try {
                    return apkIconFetcherFactory.create(path, options, imageLoader).fetch()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            mimeType.isImage || mimeType == MimeType.GENERIC -> {
                val inputStream = path.newInputStream()
                return SourceResult(
                    ImageSource(inputStream.source().buffer(), options.context),
                    if (mimeType != MimeType.GENERIC) mimeType.value else null, path.dataSource
                )
            }
            mimeType.isMedia && path.isMediaMetadataRetrieverCompatible -> {
                val embeddedPicture = try {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(path)
                        retriever.embeddedPicture
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
                if (embeddedPicture != null) {
                    return SourceResult(
                        ImageSource(
                            embeddedPicture.inputStream().source().buffer(), options.context
                        ), null, path.dataSource
                    )
                }
                if (mimeType.isVideo) {
                    try {
                        return videoFrameFetcherFactory.create(path, options, imageLoader).fetch()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            mimeType.isPdf && (path.isLinuxPath || path.isDocumentPath) -> {
                try {
                    return pdfPageFetcherFactory.create(path, options, imageLoader).fetch()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return null
    }

    class Factory(context: Context) : Fetcher.Factory<Pair<Path, BasicFileAttributes>> {
        private val apkIconFetcherFactory = ApkIconFetcher.Factory(context)

        private val videoFrameFetcherFactory = object : VideoFrameFetcher.Factory<Path>() {
            override fun MediaMetadataRetriever.setDataSource(data: Path) {
                appSetDataSource(data)
            }
        }

        private val pdfPageFetcherFactory = object : PdfPageFetcher.Factory<Path>() {
            override fun openParcelFileDescriptor(data: Path): ParcelFileDescriptor =
                when {
                    data.isLinuxPath ->
                        ParcelFileDescriptor.open(data.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
                    data.isDocumentPath ->
                        DocumentResolver.openParcelFileDescriptor(data as DocumentResolver.Path, "r")
                    else -> throw IllegalArgumentException(data.toString())
                }
        }

        override fun create(
            data: Pair<Path, BasicFileAttributes>,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher =
            PathAttributesFetcher(
                data, options, imageLoader, apkIconFetcherFactory, videoFrameFetcherFactory,
                pdfPageFetcherFactory
            )
    }
}
