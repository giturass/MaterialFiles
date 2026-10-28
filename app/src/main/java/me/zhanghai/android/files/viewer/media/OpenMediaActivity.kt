package me.zhanghai.android.files.viewer.media

import android.content.Intent
import android.os.Bundle
import me.zhanghai.android.files.app.AppActivity

/** External intents cannot inject internal playlists or provider paths. */
class OpenMediaActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri != null && uri.scheme in setOf("content", "file", "https", "http")) {
            startActivity(Intent(this, MediaPlayerActivity::class.java)
                .setDataAndType(uri, intent.type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        finish()
    }
}
