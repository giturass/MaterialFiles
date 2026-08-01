/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import androidx.core.app.ServiceCompat
import me.zhanghai.android.files.app.notificationManager
import me.zhanghai.android.files.compat.stopForegroundCompat

class ForegroundNotificationManager(private val service: Service) {
    private val notifications = mutableMapOf<Int, Notification>()

    private var foregroundId = 0

    @SuppressLint("MissingPermission")
    fun notify(id: Int, notification: Notification) {
        synchronized(notifications) {
            if (notifications.isEmpty()) {
                startForegroundSafe(id, notification)
                notifications[id] = notification
                foregroundId = id
            } else {
                if (id == foregroundId) {
                    startForegroundSafe(id, notification)
                } else {
                    notificationManager.notify(id, notification)
                }
                notifications[id] = notification
            }
        }
    }

    /**
     * Android 12 and above may refuse to let a service go foreground while its app is in the
     * background. The work is still running and its progress is still worth showing, so show it as
     * an ordinary notification rather than letting the exception take the app down.
     */
    @SuppressLint("MissingPermission")
    private fun startForegroundSafe(id: Int, notification: Notification) {
        try {
            service.startForeground(id, notification)
        } catch (e: Exception) {
            e.printStackTrace()
            notificationManager.notify(id, notification)
        }
    }

    fun cancel(id: Int) {
        synchronized(notifications) {
            if (id !in notifications) {
                return
            }
            if (id == foregroundId) {
                if (notifications.size == 1) {
                    service.stopForegroundCompat(ServiceCompat.STOP_FOREGROUND_REMOVE)
                    // No-op unless startForegroundSafe() had to fall back to a plain notification.
                    notificationManager.cancel(id)
                    notifications -= id
                    foregroundId = 0
                } else {
                    notifications.entries.find { it.key != id }!!.let {
                        startForegroundSafe(it.key, it.value)
                        foregroundId = it.key
                    }
                    notificationManager.cancel(id)
                    notifications -= id
                }
            } else {
                notificationManager.cancel(id)
                notifications -= id
            }
        }
    }
}
