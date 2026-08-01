/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.media

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.compat.stopForegroundCompat
import me.zhanghai.android.files.util.NotificationChannelTemplate

val mediaPlaybackNotificationChannelTemplate =
    NotificationChannelTemplate(
        "media_playback",
        R.string.notification_channel_media_playback_name,
        NotificationManagerCompat.IMPORTANCE_LOW,
        descriptionRes = R.string.notification_channel_media_playback_description,
        showBadge = false
    )

class MediaPlaybackNotification(
    private val service: MediaPlaybackService,
    private val sessionToken: MediaSessionCompat.Token
) {
    private var isForeground = false

    fun createContentPendingIntent(): PendingIntent {
        val contentIntent = service.currentUri?.let {
            MediaPlayerActivity.createSessionIntent(it, service.currentMimeType)
        } ?: Intent(service, MediaPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            service,
            REQUEST_CONTENT,
            contentIntent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    fun startOrUpdate() {
        val notification = buildNotification()
        if (!isForeground && service.isPlayingOrPreparing) {
            enterForeground(notification)
        } else {
            runCatching {
                NotificationManagerCompat.from(service)
                    .notify(NotificationIds.MEDIA_PLAYBACK, notification)
            }
        }
    }

    /**
     * Enters the foreground whatever the playback state is.
     *
     * A start command has at most a few seconds to call `startForeground()`, and the notification's
     * own actions are delivered as foreground service starts so that they still work once we have
     * left the foreground. Pausing or stopping right afterwards is fine; never starting is not.
     */
    fun startForegroundNow() {
        if (isForeground) {
            return
        }
        enterForeground(buildNotification())
    }

    private fun enterForeground(notification: android.app.Notification) {
        val started = runCatching {
            ServiceCompat.startForeground(
                service,
                NotificationIds.MEDIA_PLAYBACK,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }.isSuccess
        if (started) {
            isForeground = true
        } else {
            // Android 12 and above can refuse a foreground start outright. The notification is
            // still worth posting, and playback itself is unaffected.
            runCatching {
                NotificationManagerCompat.from(service)
                    .notify(NotificationIds.MEDIA_PLAYBACK, notification)
            }
        }
    }

    fun leaveForeground() {
        if (!isForeground) {
            return
        }
        service.stopForegroundCompat(ServiceCompat.STOP_FOREGROUND_DETACH)
        isForeground = false
    }

    fun stopForeground() {
        if (isForeground) {
            service.stopForegroundCompat(ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        runCatching {
            NotificationManagerCompat.from(service).cancel(NotificationIds.MEDIA_PLAYBACK)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val rewindIntent = createServicePendingIntent(
            REQUEST_REWIND, MediaPlaybackService.createRewindIntent(service)
        )
        val playPauseIntent = if (service.isPlayingOrPreparing) {
            createServicePendingIntent(
                REQUEST_PAUSE, MediaPlaybackService.createPauseIntent(service)
            )
        } else {
            createServicePendingIntent(
                REQUEST_PLAY, MediaPlaybackService.createPlayIntent(service)
            )
        }
        val fastForwardIntent = createServicePendingIntent(
            REQUEST_FAST_FORWARD, MediaPlaybackService.createFastForwardIntent(service)
        )
        val stopIntent = createServicePendingIntent(
            REQUEST_STOP, MediaPlaybackService.createStopIntent(service)
        )

        val statusText = service.displaySubtitle ?: service.getString(
            when (service.status) {
                MediaPlaybackService.Status.OPENING,
                MediaPlaybackService.Status.BUFFERING -> R.string.media_player_buffering
                MediaPlaybackService.Status.PLAYING -> R.string.media_player_playing
                MediaPlaybackService.Status.PAUSED -> R.string.media_player_paused
                MediaPlaybackService.Status.ENDED -> R.string.media_player_finished
                MediaPlaybackService.Status.ERROR -> R.string.media_player_error
                else -> R.string.media_player_ready
            }
        )
        val builder = NotificationCompat.Builder(
            service, mediaPlaybackNotificationChannelTemplate.id
        )
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(service.displayTitle.ifEmpty {
                service.getString(R.string.media_player_title)
            })
            .setContentText(statusText)
            .setContentIntent(createContentPendingIntent())
            .setDeleteIntent(stopIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(service.isPlayingOrPreparing)
            .setShowWhen(false)
            .setSilent(true)
        val compactActionIndices: IntArray
        val playPauseAction = {
            builder.addAction(
                if (service.isPlayingOrPreparing) {
                    R.drawable.media_pause_icon_white_24dp
                } else {
                    R.drawable.media_play_icon_white_24dp
                },
                service.getString(
                    if (service.isPlayingOrPreparing) R.string.media_player_pause
                    else R.string.media_player_play
                ),
                playPauseIntent
            )
        }
        val stopAction = {
            builder.addAction(
                R.drawable.stop_icon_white_24dp,
                service.getString(R.string.stop),
                stopIntent
            )
        }
        if (service.isAudio) {
            // Music moves between tracks rather than within one, so the folder replaces the seek
            // buttons here just like it does in the player.
            if (service.hasPrevious || service.hasNext) {
                builder.addAction(
                    R.drawable.media_skip_previous_icon_white_24dp,
                    service.getString(R.string.media_player_previous),
                    createServicePendingIntent(
                        REQUEST_SKIP_TO_PREVIOUS,
                        MediaPlaybackService.createSkipToPreviousIntent(service)
                    )
                )
                playPauseAction()
                builder.addAction(
                    R.drawable.media_skip_next_icon_white_24dp,
                    service.getString(R.string.media_player_next),
                    createServicePendingIntent(
                        REQUEST_SKIP_TO_NEXT,
                        MediaPlaybackService.createSkipToNextIntent(service)
                    )
                )
                stopAction()
                compactActionIndices = intArrayOf(0, 1, 2)
            } else {
                playPauseAction()
                stopAction()
                compactActionIndices = intArrayOf(0)
            }
        } else if (service.isSeekable) {
            builder.addAction(
                R.drawable.media_replay_10_icon_white_24dp,
                service.getString(R.string.media_player_rewind_10),
                rewindIntent
            )
            playPauseAction()
            builder.addAction(
                R.drawable.media_forward_10_icon_white_24dp,
                service.getString(R.string.media_player_forward_10),
                fastForwardIntent
            )
            stopAction()
            compactActionIndices = intArrayOf(0, 1, 2)
        } else {
            playPauseAction()
            stopAction()
            compactActionIndices = intArrayOf(0)
        }
        builder.setStyle(
            MediaStyle()
                .setMediaSession(sessionToken)
                .setShowActionsInCompactView(*compactActionIndices)
                .setShowCancelButton(false)
        )
        service.artwork?.let(builder::setLargeIcon)
        return builder.build()
    }

    private fun createServicePendingIntent(requestCode: Int, intent: Intent): PendingIntent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The service may have left the foreground while paused, and a plain service start from
            // the background is not allowed. onStartCommand() re-enters the foreground for this.
            PendingIntent.getForegroundService(
                service, requestCode, intent, pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            PendingIntent.getService(
                service, requestCode, intent, pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }

    private fun pendingIntentFlags(flags: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags or PendingIntent.FLAG_IMMUTABLE
        } else {
            flags
        }

    companion object {
        private const val REQUEST_CONTENT = 1
        private const val REQUEST_REWIND = 2
        private const val REQUEST_PLAY = 3
        private const val REQUEST_PAUSE = 4
        private const val REQUEST_FAST_FORWARD = 5
        private const val REQUEST_STOP = 6
        private const val REQUEST_SKIP_TO_PREVIOUS = 7
        private const val REQUEST_SKIP_TO_NEXT = 8
    }
}
