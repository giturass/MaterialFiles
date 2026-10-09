/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe

object Terminal {
    fun open(path: String, context: Context) {
        val sendIntent = Intent(Intent.ACTION_SEND).setType("*/*")
        val activities = context.packageManager.queryIntentActivities(
            sendIntent, PackageManager.MATCH_DEFAULT_ONLY
        )
        for (activity in activities.map { it.activityInfo }) {
            if (!activity.name.endsWith(".TermHere") || !activity.exported || !activity.enabled) {
                continue
            }
            // TermHere reads Uri.getPath(). Encode URI metacharacters without using a file:// URI,
            // which would cause FileUriExposedException on Android 7 and newer.
            val intent = Intent(sendIntent)
                .setComponent(ComponentName(activity.packageName, activity.name))
                .putExtra(Intent.EXTRA_STREAM, Uri.Builder().path(path).build())
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // The terminal may have been disabled or uninstalled since the query.
            } catch (_: SecurityException) {
                // Try another terminal if this one requires a permission we do not have.
            }
        }
        if (Termux.isInstalled(context)) {
            context.startActivitySafe(
                Intent(context, OpenTermuxActivity::class.java)
                    .putArgs(OpenTermuxActivity.Args(path))
            )
        } else {
            context.showToast(R.string.terminal_not_found)
        }
    }
}
