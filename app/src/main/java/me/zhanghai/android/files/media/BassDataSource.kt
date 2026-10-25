package me.zhanghai.android.files.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.un4seen.bass.BASS
import java.io.IOException
import java.nio.ByteBuffer

/** Presents BASS decoded float PCM as a seekable WAV to Media3's standard WavExtractor. */
@OptIn(UnstableApi::class)
class BassDataSource(private val context: Context) : BaseDataSource(false) {
    private var descriptor: ParcelFileDescriptor? = null
    private var stream = 0
    private var uri: Uri? = null
    private var reader: BassPcmReader? = null
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            BassRuntime.ensureInitialized(context)
            BASS.BASS_SetDevice(0) // No output device: ExoPlayer owns audio output and focus.
            uri = dataSpec.uri
            val flags = BASS.BASS_STREAM_DECODE or BASS.BASS_SAMPLE_FLOAT
            stream = when (dataSpec.uri.scheme) {
                "file" -> BASS.BASS_StreamCreateFile(dataSpec.uri.path, 0, 0, flags)
                "content" -> {
                    descriptor = context.contentResolver.openFileDescriptor(dataSpec.uri, "r")
                        ?: throw IOException("无法打开音频文件")
                    BASS.BASS_StreamCreateFile(descriptor, 0, 0, flags)
                }
                else -> throw IOException("BASS 需要可随机读取的文件")
            }
            if (stream == 0) throw bassError("打开音频")
            val info = BASS.BASS_CHANNELINFO()
            if (!BASS.BASS_ChannelGetInfo(stream, info)) throw bassError("读取音频信息")
            val pcmBytes = BASS.BASS_ChannelGetLength(stream, BASS.BASS_POS_BYTE)
            if (pcmBytes < 0) throw bassError("读取音频时长")
            reader = BassPcmReader(object : BassPcmReader.Decoder {
                override fun sampleRate() = info.freq
                override fun channels() = info.chans
                override fun length() = pcmBytes
                override fun seek(alignedPcmBytePosition: Long) {
                    if (!BASS.BASS_ChannelSetPosition(stream, alignedPcmBytePosition, BASS.BASS_POS_BYTE)) {
                        throw bassError("音频定位")
                    }
                }
                override fun read(buffer: ByteBuffer): Int {
                    val count = BASS.BASS_ChannelGetData(stream, buffer, buffer.capacity())
                    if (count < 0 && BASS.BASS_ErrorGetCode() != BASS.BASS_ERROR_ENDED) {
                        throw bassError("音频解码")
                    }
                    return count
                }
                override fun close() {}
            })
            val remaining = reader!!.open(dataSpec.position, dataSpec.length)
            started = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException("BASS 音频初始化失败", e)
        } catch (e: LinkageError) {
            close()
            throw IOException("BASS 音频库加载失败", e)
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val count = (reader ?: throw IOException("音频数据源未打开")).read(target, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            if (stream != 0) {
                BASS.BASS_StreamFree(stream)
                stream = 0
            }
        } finally {
            try {
                descriptor?.close()
            } finally {
                descriptor = null
                reader = null
                uri = null
                if (started) {
                    started = false
                    transferEnded()
                }
            }
        }
    }

    private fun bassError(operation: String) =
        IOException("$operation 失败（BASS ${BASS.BASS_ErrorGetCode()}）")

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = BassDataSource(context)
    }
}

private object BassRuntime {
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        if (!BASS.BASS_Init(0, 44100, 0) && BASS.BASS_ErrorGetCode() != BASS.BASS_ERROR_ALREADY) {
            throw IOException("BASS 初始化失败（${BASS.BASS_ErrorGetCode()}）")
        }
        for (name in listOf("bassalac", "bassape", "basswv", "bassflac", "bassopus", "bassdsd")) {
            val path = "${context.applicationInfo.nativeLibraryDir}/lib$name.so"
            if (BASS.BASS_PluginLoad(path, 0) == 0) {
                throw IOException("无法加载 $name（BASS ${BASS.BASS_ErrorGetCode()}）")
            }
        }
        initialized = true
    }
}
