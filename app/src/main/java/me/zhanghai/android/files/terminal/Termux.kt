/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.UUID

/** The public API is also implemented by MDTerm, which uses the same package name. */
internal object Termux {
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    const val HELP_URL = "https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent"
    const val EXTRA_RESULT = "result"

    private const val PACKAGE_NAME = "com.termux"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private val serviceComponent = ComponentName(PACKAGE_NAME, "com.termux.app.RunCommandService")

    fun isInstalled(context: Context): Boolean {
        val service = context.packageManager.resolveService(
            Intent(ACTION_RUN_COMMAND).setComponent(serviceComponent), 0
        )?.serviceInfo ?: return false
        return service.exported && service.enabled && service.applicationInfo.enabled
    }

    // https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
    fun open(path: String, context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
            ?: throw ActivityNotFoundException()
        // Termux needs a mutable PendingIntent to fill in its result. An explicit, non-exported
        // receiver and a unique, one-shot token keep the callback scoped to this request.
        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .setData(Uri.Builder().scheme("termux-result").path(UUID.randomUUID().toString()).build())
        val resultFlags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val resultPendingIntent = PendingIntent.getBroadcast(context, 0, resultIntent, resultFlags)
        val commandIntent = createCommandIntent(path)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPendingIntent)
        try {
            if (context.startService(commandIntent) == null) {
                throw ActivityNotFoundException()
            }
        } catch (e: RuntimeException) {
            resultPendingIntent.cancel()
            throw e
        }
        // Open from the user's foreground action. Asking TermuxService to open the activity would
        // require Termux's "draw over other apps" permission on Android 10 and newer.
        context.startActivity(launchIntent)
    }

    internal fun createCommandIntent(path: String): Intent =
        Intent(ACTION_RUN_COMMAND)
            .setComponent(serviceComponent)
            // login selects the user's shell and preserves the supplied working directory.
            // The API expands $PREFIX itself, including for secondary Android users.
            .putExtra("com.termux.RUN_COMMAND_PATH", "\$PREFIX/bin/login")
            // Keep paths as data: spaces, quotes, '$', '#' and '%' must never become shell code.
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", path)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            // Switch to the new session; this app opens the activity itself.
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "2")
}
