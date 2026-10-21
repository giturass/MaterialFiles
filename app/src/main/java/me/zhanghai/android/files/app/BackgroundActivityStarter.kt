/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.showToast

val backgroundActivityStartNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "background_activity_start",
            R.string.notification_channel_background_activity_start_name,
            NotificationManagerCompat.IMPORTANCE_HIGH,
            descriptionRes = R.string.notification_channel_background_activity_start_description,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        ongoing = true,
        autoCancel = true,
        category = NotificationCompat.CATEGORY_ERROR,
        priority = NotificationCompat.PRIORITY_HIGH
    )

object BackgroundActivityStarter {
    fun startActivity(
        intent: Intent,
        title: CharSequence,
        text: CharSequence?,
        context: Context,
        notificationTag: String? = null,
        notificationTimeoutMillis: Long? = null
    ): Boolean {
        // TODO: Only use new task when in background?
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (isInForeground) {
            context.startActivitySafe(intent)
        } else {
            if (notificationTag != null) {
                val channel = notificationManager.getNotificationChannel(
                    backgroundActivityStartNotificationTemplate.channelTemplate.id
                )
                if (!notificationManager.areNotificationsEnabled()
                    || channel == null || channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
                    context.showToast(R.string.file_open_from_background_unavailable)
                    return false
                }
            }
            notifyStartActivity(
                intent, title, text, context, notificationTag, notificationTimeoutMillis
            )
            true
        }
    }

    private val isInForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
            Lifecycle.State.STARTED
        )

    // We'll just throw exception for the file job if we don't have the permission to post
    // notifications.
    @SuppressLint("MissingPermission")
    private fun notifyStartActivity(
        intent: Intent,
        title: CharSequence,
        text: CharSequence?,
        context: Context,
        tag: String?,
        timeoutMillis: Long?
    ) {
        val pendingIntent = PendingIntent.getActivity(
            context, intent.hashCode(), intent, PendingIntent.FLAG_ONE_SHOT or
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = backgroundActivityStartNotificationTemplate.createBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .apply { timeoutMillis?.let { setTimeoutAfter(it) } }
            .build()
        notificationManager.notify(tag, if (tag != null) 0 else intent.hashCode(), notification)
    }
}
