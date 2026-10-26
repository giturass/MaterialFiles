package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.un4seen.bass.BASS
import java.io.IOException
import java.nio.ByteBuffer

/** BASS only decodes. ExoPlayer owns AudioTrack, focus, speed, repeat, session and lifecycle. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BassPcmDataSource(private val context: Context) : BaseDataSource(false) {
    private var descriptor: AssetFileDescriptor? = null
    private var stream = 0
    private var acquired = false
    private var opened = false
    private var sourceUri: Uri? = null
    private var header = byteArrayOf()
    private var position = 0L
    private var remaining = 0L
    private var frameSize = 4
    private var buffer = ByteBuffer.allocateDirect(65536)

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val original = Uri.parse(dataSpec.uri.getQueryParameter("uri")
                ?: throw IOException("缺少音频地址"))
            if (original.scheme !in setOf("content", "file")) {
                throw IOException("BASS 需要可定位的文件来源")
            }
            BassRuntime.acquire(context)
            acquired = true
            descriptor = context.contentResolver.openAssetFileDescriptor(original, "r")
                ?: throw IOException("无法打开音频文件")
            val file = checkNotNull(descriptor)
            stream = BASS.BASS_StreamCreateFile(
                file.parcelFileDescriptor, file.startOffset, file.length.coerceAtLeast(0),
                // RF64 requires the exact decoded length and byte-accurate seeks.
                BASS.BASS_STREAM_DECODE or BASS.BASS_SAMPLE_FLOAT or BASS.BASS_STREAM_PRESCAN
            )
            if (stream == 0) throw bassError("无法解码音频")
            val info = BASS.BASS_CHANNELINFO()
            if (!BASS.BASS_ChannelGetInfo(stream, info)) throw bassError("无法读取音频信息")
            frameSize = info.chans * 4
            val length = BASS.BASS_ChannelGetLength(stream, BASS.BASS_POS_BYTE)
            if (length < 0) throw bassError("无法读取音频长度")
            header = PcmWaveHeader.create(info.freq, info.chans, length)
            val total = length + header.size
            position = dataSpec.position
            if (position > total) throw DataSourceException(
                "音频定位超出文件末尾", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            )
            remaining = total - position
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
            val pcmPosition = (position - header.size).coerceAtLeast(0)
            if (pcmPosition > 0 && pcmPosition < length) {
                val aligned = pcmPosition / frameSize * frameSize
                if (!BASS.BASS_ChannelSetPosition(stream, aligned, BASS.BASS_POS_BYTE)) {
                    throw bassError("无法定位音频")
                }
                val discard = (pcmPosition - aligned).toInt()
                if (discard != 0) {
                    // Preserve the unaligned first frame for arbitrary DataSpec byte ranges.
                    buffer.clear()
                    val read = BASS.BASS_ChannelGetData(stream, buffer, frameSize)
                    if (read < frameSize) throw bassError("无法读取音频帧")
                    buffer.position(discard); buffer.limit(read)
                } else buffer.limit(0)
            } else buffer.limit(0)
            sourceUri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else remaining
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException("BASS 解码失败", e)
        } catch (e: LinkageError) {
            close()
            throw IOException("BASS 音频库不可用", e)
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), remaining).toInt()
        val count: Int
        if (position < header.size) {
            count = minOf(requested, header.size - position.toInt())
            header.copyInto(target, offset, position.toInt(), position.toInt() + count)
        } else {
            if (!buffer.hasRemaining()) {
                buffer.clear()
                val decoded = BASS.BASS_ChannelGetData(
                    stream, buffer, buffer.capacity() / frameSize * frameSize
                )
                if (decoded <= 0) {
                    // A truncated or failed decode must not appear as successful completion.
                    throw bassError("音频数据提前结束")
                }
                buffer.position(0); buffer.limit(decoded)
            }
            count = minOf(requested, buffer.remaining())
            buffer.get(target, offset, count)
        }
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = sourceUri

    override fun close() {
        try {
            if (stream != 0) BASS.BASS_StreamFree(stream)
            stream = 0
            descriptor?.close()
        } finally {
            descriptor = null
            if (acquired) BassRuntime.release()
            acquired = false
            sourceUri = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    private fun bassError(message: String) = IOException("$message（BASS ${BASS.BASS_ErrorGetCode()}）")

    companion object {
        const val SCHEME = "basspcm"
        fun wrap(uri: Uri): Uri = Uri.Builder().scheme(SCHEME).authority("decode")
            .appendQueryParameter("uri", uri.toString()).build()
    }
}

/** BASS's no-sound device is process-wide; overlapping Exo loaders must not free each other. */
private object BassRuntime {
    private var references = 0
    private val plugins = mutableListOf<Int>()

    @Synchronized fun acquire(context: Context) {
        if (references == 0) {
            if (!BASS.BASS_Init(0, 48000, 0)) throw IOException("BASS 初始化失败")
            try {
                for (library in listOf("bassalac", "bassflac", "basswv")) {
                    val plugin = BASS.BASS_PluginLoad("${context.applicationInfo.nativeLibraryDir}/lib$library.so", 0)
                    if (plugin == 0) throw IOException("无法加载 $library")
                    plugins += plugin
                }
            } catch (e: Exception) {
                plugins.forEach { BASS.BASS_PluginFree(it) }; plugins.clear()
                BASS.BASS_Free()
                throw e
            }
        }
        BASS.BASS_SetDevice(0)
        ++references
    }

    @Synchronized fun release() {
        check(references > 0)
        if (--references == 0) {
            plugins.forEach { BASS.BASS_PluginFree(it) }; plugins.clear()
            BASS.BASS_SetDevice(0)
            BASS.BASS_Free()
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackDataSource(private val context: Context) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) {
        listeners += listener
        delegate?.addTransferListener(listener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == BassPcmDataSource.SCHEME) {
            BassPcmDataSource(context)
        } else DefaultDataSource.Factory(context).createDataSource()
        delegate = source
        listeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate).read(buffer, offset, length)
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { try { delegate?.close() } finally { delegate = null } }
}
