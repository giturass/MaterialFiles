/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.intentType
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

class MediaPlayerActivity : AppActivity() {

    private var pendingMediaIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = MediaPlayerFragment().putArgs(MediaPlayerFragment.Args(intent))
            supportFragmentManager.commit {
                add(android.R.id.content, fragment, FRAGMENT_TAG)
            }
        } else {
            val fragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)
                as? MediaPlayerFragment
            if (MediaPlayerFragment.canHandleIntent(this, intent)
                && (fragment == null || !fragment.matchesIntent(intent))) {
                pendingMediaIntent = intent
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (!MediaPlayerFragment.canHandleIntent(this, intent)) {
            return
        }
        setIntent(intent)
        pendingMediaIntent = intent
        replacePendingFragment()
    }

    override fun onPostResume() {
        super.onPostResume()

        replacePendingFragment()
    }

    private fun replacePendingFragment() {
        if (supportFragmentManager.isStateSaved) {
            return
        }
        val intent = pendingMediaIntent ?: return
        pendingMediaIntent = null
        (supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? MediaPlayerFragment)
            ?.prepareForReplacement()
        val fragment = MediaPlayerFragment().putArgs(MediaPlayerFragment.Args(intent))
        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            replace(android.R.id.content, fragment, FRAGMENT_TAG)
        }.commitNow()
    }

    companion object {
        private const val FRAGMENT_TAG = "media_player"

        private const val EXTRA_ATTACH_TO_SESSION =
            "me.zhanghai.android.files.viewer.media.extra.ATTACH_TO_SESSION"

        fun createIntent(path: Path, mimeType: MimeType): Intent =
            Intent(application, MediaPlayerActivity::class.java)
                .setDataAndType(path.fileProviderUri, mimeType.intentType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { extraPath = path }

        internal fun createSessionIntent(uri: Uri, mimeType: String?): Intent =
            Intent(application, MediaPlayerActivity::class.java)
                .setDataAndType(uri, mimeType)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(EXTRA_ATTACH_TO_SESSION, true)

        internal fun isAttachToSessionIntent(intent: Intent): Boolean =
            intent.getBooleanExtra(EXTRA_ATTACH_TO_SESSION, false)
    }
}
