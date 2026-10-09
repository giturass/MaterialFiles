/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.BackgroundActivityStarter
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast

/** Receives only the explicit PendingIntent given to Termux for this request. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = intent.getBundleExtra(Termux.EXTRA_RESULT) ?: return
        if (result.getInt("err", Activity.RESULT_OK) == Activity.RESULT_OK ||
            result.containsKey("exitCode")) {
            return
        }
        // Do not inspect or retain stdout/stderr: a completed terminal session can contain private
        // commands. An exit code means the session ran (including one the user later stopped).
        // Only startup errors, including allow-external-apps=false, need feedback here.
        val message = result.getString("errmsg") ?: context.getString(R.string.terminal_open_failed)
        val errorIntent = Intent(context, OpenTermuxActivity::class.java)
            .putArgs(OpenTermuxActivity.Args(errorMessage = message))
        val title = context.getString(R.string.terminal_open_failed)
        context.showToast(R.string.terminal_termux_failed_hint, Toast.LENGTH_LONG)
        try {
            BackgroundActivityStarter.startActivity(errorIntent, title, message, context)
        } catch (_: SecurityException) {
            // The toast still reports the failure when notifications are not allowed.
        }
    }
}
