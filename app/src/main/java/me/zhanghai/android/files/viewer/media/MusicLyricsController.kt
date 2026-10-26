package me.zhanghai.android.files.viewer.media

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java8.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.fileProviderPath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.getColorByAttr
import com.google.android.material.R as MaterialR

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicLyricsController(root: View, private val scope: CoroutineScope) {
    private val context = root.context
    private val preferences = context.getSharedPreferences("media_lyrics", android.content.Context.MODE_PRIVATE)
    private val list = root.findViewById<RecyclerView>(R.id.media_lyrics_list)
    private val status = root.findViewById<TextView>(R.id.media_lyrics_status)
    private val layout = LinearLayoutManager(context)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaController? = null
    private var mediaId: String? = null
    private var tracks: Tracks? = null
    private var job: Job? = null
    private var lyrics: Lyrics? = null
    private var active = -1
    private var centered = -1
    private var followAfter = 0L
    private val adapter = object : RecyclerView.Adapter<LineHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = LineHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.media_lyric_line, parent, false) as TextView)
        override fun getItemCount() = lyrics?.lines?.size ?: 0
        override fun onBindViewHolder(holder: LineHolder, position: Int) {
            holder.text.text = lyrics!!.lines[position].text
            val selected = position == active
            holder.text.isSelected = selected
            holder.text.setTextColor(context.getColorByAttr(if (selected) MaterialR.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant))
            holder.text.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            if (visible && player != null) handler.postDelayed(this, 250)
        }
    }

    var visible = false
        set(value) {
            if (field == value) return
            field = value
            centered = -1
            scheduleTick()
        }

    init {
        list.layoutManager = layout
        list.adapter = adapter
        list.itemAnimator = null
        list.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                list.setPadding(0, list.height / 3, 0, list.height / 3)
                centered = -1
            }
        }
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // The fixed-height lyrics pane scrolls independently of the music page.
                    rv.parent.requestDisallowInterceptTouchEvent(true)
                    followAfter = SystemClock.uptimeMillis() + 4000
                    centered = -1
                }
                return false
            }
        })
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                followAfter = SystemClock.uptimeMillis() + 4000
            }
        })
    }

    fun update(value: MediaController?) {
        val id = value?.currentMediaItem?.mediaId
        val changed = player !== value || mediaId != id || tracks != value?.currentTracks
        player = value
        if (!changed) return
        val newItem = mediaId != id
        mediaId = id
        tracks = value?.currentTracks
        job?.cancel()
        if (value == null || id == null) display(null)
        else {
            if (newItem) {
                display(null)
                status.setText(R.string.media_lyrics_loading)
            }
            reload()
        }
        scheduleTick()
    }

    fun loadFile(uri: Uri, targetMediaId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { requireNotNull(readUri(uri)) }
                preferences.edit().putString(targetMediaId, uri.toString()).apply()
                if (mediaId == targetMediaId) reload()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, R.string.media_lyrics_error, Toast.LENGTH_LONG).show() }
        }
    }

    private fun reload() {
        val player = player ?: return
        val id = mediaId ?: return
        val sourcePath = player.currentMediaItem?.mediaMetadata?.extras?.getString(SOURCE_PATH)
        job?.cancel()
        job = scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val selected = preferences.getString(id, null)
                    val imported = selected?.let { runCatching { readUri(Uri.parse(it)) }.getOrNull() }
                    imported ?: readSidecar(id, sourcePath)
                } ?: requestEmbedded(player, id)?.let { text -> withContext(Dispatchers.IO) { Lyrics.parse(text) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            if (this@MusicLyricsController.player === player && mediaId == id) display(loaded)
        }
    }

    private suspend fun requestEmbedded(player: MediaController, id: String): String? {
        val future = player.sendCustomCommand(SessionCommand(MediaPlaybackService.GET_LYRICS, Bundle.EMPTY),
            Bundle().apply { putString("media_id", id) })
        val result = suspendCancellableCoroutine<SessionResult?> { continuation ->
            future.addListener({
                if (continuation.isActive) continuation.resume(runCatching { future.get() }.getOrNull())
            }, ContextCompat.getMainExecutor(context))
            continuation.invokeOnCancellation { future.cancel(false) }
        }
        return result?.takeIf { it.resultCode == SessionResult.RESULT_SUCCESS && it.extras.getString("media_id") == id }
            ?.extras?.getString("lyrics")
    }

    private fun readUri(uri: Uri): Lyrics? = context.contentResolver.openInputStream(uri)?.use { read(it) }

    private fun readSidecar(id: String, sourcePath: String?): Lyrics? {
        return try {
            val uri = Uri.parse(id)
            val path = when {
                sourcePath != null -> Paths.get(URI(sourcePath))
                uri.scheme == "content" && uri.authority == BuildConfig.FILE_PROVIDIER_AUTHORITY -> uri.fileProviderPath
                uri.scheme == "file" -> Paths.get(URI(id))
                else -> return null
            }
            val name = path.fileName.toString().substringBeforeLast('.', path.fileName.toString())
            for (extension in listOf("lrc", "LRC")) {
                val result = runCatching { path.resolveSibling("$name.$extension").newInputStream().use { read(it) } }.getOrNull()
                if (result != null) return result
            }
            null
        } catch (_: Exception) { null }
    }

    private fun read(input: InputStream): Lyrics? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > Lyrics.MAX_TEXT_LENGTH * 4) throw IOException("Lyrics are too large")
            output.write(buffer, 0, count)
        }
        return Lyrics.parse(Lyrics.decode(output.toByteArray()))
    }

    private fun display(value: Lyrics?) {
        lyrics = value
        active = -1
        centered = -1
        followAfter = 0
        list.stopScroll()
        adapter.notifyDataSetChanged()
        layout.scrollToPositionWithOffset(0, 0)
        status.setText(R.string.media_lyrics_empty)
        status.isVisible = value == null
        list.isVisible = value != null
        updatePosition()
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        if (visible && player != null) handler.post(tick)
    }

    private fun updatePosition() {
        val index = lyrics?.activeLine(player?.currentPosition ?: 0) ?: -1
        if (active != index) {
            val previous = active
            active = index
            if (previous >= 0) adapter.notifyItemChanged(previous)
            if (index >= 0) adapter.notifyItemChanged(index)
        }
        val target = if (lyrics?.timed == true) index.coerceAtLeast(0) else -1
        if (!visible || target < 0 || centered == target || SystemClock.uptimeMillis() < followAfter
            || list.scrollState != RecyclerView.SCROLL_STATE_IDLE
            || context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) return
        val lineHeight = layout.findViewByPosition(target)?.height ?: (48 * context.resources.displayMetrics.density).toInt()
        layout.scrollToPositionWithOffset(target, (list.height - lineHeight) / 2 - list.paddingTop)
        centered = target
    }

    private class LineHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    companion object {
        const val SOURCE_PATH = "media.lyrics.source_path"
    }
}
