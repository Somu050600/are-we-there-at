package com.example.are_we_there_yet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

object TripNotifications {
    const val PROGRESS_CHANNEL = "trip_progress_v1"
    const val ARRIVAL_CHANNEL = "arrival_alarm_v2"
    private const val NOTICE_CHANNEL = "trip_notices_v1"
    const val PROGRESS_ID = 1001
    const val ARRIVAL_ID = 1002
    const val RESTART_ID = 1003
    const val EXTRA_NAME = "destination_name"
    private const val ALARM_TIMEOUT_MILLIS = 5 * 60 * 1000L
    private val VIBRATION = longArrayOf(0, 600, 400, 600)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, "Trip progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live distance to your destination"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ARRIVAL_CHANNEL, "Arrival alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when you reach your destination"
                enableVibration(true)
                vibrationPattern = VIBRATION
                setSound(alarmSound(), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(NOTICE_CHANNEL, "Trip notices", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun progress(context: Context, trip: Trip, distance: Double?, progress: Double?): Notification {
        ensureChannels(context)
        val percent = TripMath.percent(progress)
        val text = when {
            trip.paused && distance != null -> "Paused · ${TripMath.formatDistance(distance)} remaining"
            trip.paused -> "Paused"
            distance == null -> "Calculating… remaining (0%)"
            else -> "${TripMath.formatDistance(distance)} remaining ($percent%)"
        }
        val toggle = if (trip.paused) {
            action(context, "Resume", TripService.ACTION_RESUME)
        } else {
            action(context, "Pause", TripService.ACTION_PAUSE)
        }
        val builder = builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_arrow)
            .setContentTitle("Travelling to ${trip.name}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openApp(context))
            .addAction(toggle)
            .addAction(action(context, "Stop Tracking", TripService.ACTION_STOP))
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgress(percent)
                    .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_trip_arrow)),
            )
            // Ask Android 16 to show it as a live update chip where allowed.
            builder.addExtras(android.os.Bundle().apply { putBoolean(EXTRA_PROMOTED, true) })
        } else {
            builder.setProgress(100, percent, distance == null && !trip.paused)
        }
        return builder.build()
    }

    fun arrival(context: Context, trip: Trip, fullScreen: Boolean): Notification {
        ensureChannels(context)
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, ArrivalStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = builder(context, ARRIVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_arrow)
            .setContentTitle("Arrived at ${trip.name}")
            .setContentText("You have reached your destination")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(ALARM_TIMEOUT_MILLIS)
            .setContentIntent(openApp(context))
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setSound(alarmSound())
            .setVibrate(VIBRATION)
        if (fullScreen) {
            val screen = PendingIntent.getActivity(
                context, 0,
                Intent(context, ArrivalActivity::class.java)
                    .putExtra(EXTRA_NAME, trip.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(screen, true)
        }
        // Insistent: the alarm sound repeats until the user stops or dismisses it.
        return builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    fun restartNotice(context: Context, trip: Trip): Notification {
        ensureChannels(context)
        return builder(context, NOTICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_arrow)
            .setContentTitle("Trip paused")
            .setContentText("Trip to ${trip.name} stopped by restart. Tap to resume.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
    }

    fun canUseFullScreen(context: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private const val EXTRA_PROMOTED = "android.requestPromotedOngoing"

    private fun alarmSound() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    @Suppress("DEPRECATION")
    private fun builder(context: Context, channel: String) =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context)

    private fun action(context: Context, title: String, serviceAction: String) = Notification.Action.Builder(
        null, title,
        PendingIntent.getService(
            context, serviceAction.hashCode(), TripService.actionIntent(context, serviceAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
