package me.zhanghai.android.files.media

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.Locale
import java8.nio.file.Path
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isAudio
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.util.extraPathList

object MediaFiles {
    const val EXTRA_POSITION = "me.zhanghai.android.files.media.POSITION"
    const val EXTRA_VIDEO = "me.zhanghai.android.files.media.VIDEO"
    private val audioExtensions = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "wav", "wave", "ogg", "oga", "opus", "aiff",
        "aif", "alac", "ape", "wv", "dsf", "dff", "mp2", "mp1", "wma", "amr", "ac3", "ec3"
    )
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "ts", "m2ts", "mts",
        "mpeg", "mpg", "vob", "flv", "wmv", "ogv", "m3u8", "mpd"
    )

    fun isAudio(name: String, mimeType: String?) =
        mimeType?.startsWith("audio/") == true || extension(name) in audioExtensions

    fun isVideo(name: String, mimeType: String?) =
        mimeType?.startsWith("video/") == true || extension(name) in videoExtensions

    fun isMedia(name: String, mimeType: String?) = isAudio(name, mimeType) || isVideo(name, mimeType)

    fun putPlaylist(intent: Intent, paths: List<Path>, position: Int, video: Boolean) {
        intent.extraPathList = paths
        intent.putExtra(EXTRA_POSITION, position)
        intent.putExtra(EXTRA_VIDEO, video)
    }

    fun item(uri: Uri, title: String, video: Boolean, mimeType: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            // Wildcard/unknown MIME types must be sniffed, not treated as containers.
            .setMimeType(mimeType?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
                ?.takeUnless { '*' in it })
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title)
                .setMediaType(if (video) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsPlayable(true).build())
            .build()

    fun item(path: Path, video: Boolean): MediaItem =
        item(path.fileProviderUri, path.fileName?.toString().orEmpty(), video)

    fun isVideo(item: MediaItem): Boolean =
        item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_MOVIE

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
