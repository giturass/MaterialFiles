/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.R

/** A snapshot of what a running [FileJob] is doing, as shown to the user. */
class FileJobProgress(
    val title: CharSequence,
    val text: CharSequence?,
    val subText: CharSequence?,
    val info: CharSequence?,
    val max: Int,
    val progress: Int,
    val indeterminate: Boolean,
    val showCancel: Boolean
)

/**
 * Keeps the latest [FileJobProgress] of every running job, so that it can be shown either in a
 * notification or in a dialog.
 *
 * The two are alternatives rather than duplicates: while a job has a dialog in front of the user
 * its notification is taken down, and it comes back as soon as the dialog goes away or the app
 * leaves the foreground.
 */
object FileJobProgressManager {
    private val lock = Any()

    private val liveDatas = mutableMapOf<Int, MutableLiveData<FileJobProgress?>>()

    private val lastProgresses = mutableMapOf<Int, FileJobProgress>()

    /** Jobs whose progress is currently on screen, and whose notification is therefore hidden. */
    private val foregroundIds = mutableSetOf<Int>()

    @MainThread
    fun start(id: Int) {
        synchronized(lock) {
            liveDatas[id] = MutableLiveData()
            lastProgresses -= id
        }
    }

    fun finish(id: Int) {
        val liveData = synchronized(lock) {
            lastProgresses -= id
            foregroundIds -= id
            liveDatas.remove(id)
        }
        // The dialog holds on to the live data it observes, so it still hears about this.
        liveData?.postValue(null)
    }

    /** Null once the job has finished, or before it was ever started. */
    fun getLiveData(id: Int): LiveData<FileJobProgress?>? = synchronized(lock) { liveDatas[id] }

    fun setProgress(id: Int, progress: FileJobProgress, service: FileJobService) {
        // Held across posting so that a dialog appearing right now cannot have its notification
        // put back up behind it.
        synchronized(lock) {
            lastProgresses[id] = progress
            liveDatas[id]?.postValue(progress)
            if (id !in foregroundIds) {
                service.notificationManager.notify(id, progress.createNotification(id, service))
            }
        }
    }

    /**
     * Called by the progress dialog as it comes and goes, so that the notification only ever shows
     * what the dialog isn't showing already.
     */
    @MainThread
    fun setShownInForeground(id: Int, shownInForeground: Boolean) {
        synchronized(lock) {
            if (shownInForeground) {
                foregroundIds += id
            } else {
                foregroundIds -= id
            }
            if (id !in liveDatas) {
                // Finished, so there is neither a notification to hide nor one to bring back.
                return
            }
            val service = FileJobService.instance ?: return
            if (shownInForeground) {
                service.notificationManager.cancel(id)
            } else {
                lastProgresses[id]?.let {
                    service.notificationManager.notify(id, it.createNotification(id, service))
                }
            }
        }
    }
}

private fun FileJobProgress.createNotification(id: Int, context: Context): Notification =
    fileJobNotificationTemplate.createBuilder(context)
        .apply {
            setContentTitle(title)
            setContentText(text)
            setSubText(subText)
            setContentInfo(info)
            setProgress(max, progress, indeterminate)
            // TODO
            //setContentIntent()
            if (showCancel) {
                val intent = FileJobReceiver.createIntent(id)
                var pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pendingIntentFlags = pendingIntentFlags or PendingIntent.FLAG_IMMUTABLE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, id + 1, intent, pendingIntentFlags
                )
                addAction(
                    R.drawable.close_icon_white_24dp, context.getString(android.R.string.cancel),
                    pendingIntent
                )
            }
        }
        .build()
