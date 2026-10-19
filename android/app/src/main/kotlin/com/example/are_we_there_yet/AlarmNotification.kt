package com.example.are_we_there_yet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

object AlarmNotification {
    const val CHANNEL_ID = "location_arrivals_v1"
    const val EXTRA_ALARM_ID = "alarm_id"

    fun show(context: Context, alarm: AlarmRecord) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Location arrivals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Audible alerts when you enter a saved destination"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                    setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                },
            )
        }
        val dismiss = PendingIntent.getBroadcast(
            context, alarm.id.hashCode(),
            Intent(context, NotificationDismissReceiver::class.java).putExtra(EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Arrived at ${alarm.name?.takeIf { it.isNotBlank() } ?: "destination"}"
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
        val notification = builder.setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title).setContentText("Tap to dismiss this arrival alert")
            .setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(dismiss).setDeleteIntent(dismiss)
            .addAction(Notification.Action.Builder(null, "Dismiss", dismiss).build())
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0, 500, 250, 500)).build()
        runCatching { manager.notify(alarm.id, notificationId(alarm.id), notification) }
    }

    fun cancel(context: Context, id: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(id, notificationId(id))
    }

    private fun notificationId(id: String) = id.hashCode() and Int.MAX_VALUE
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(AlarmNotification.EXTRA_ALARM_ID)?.let { AlarmNotification.cancel(context, it) }
    }
}

class GeofenceEntryHandler(private val context: Context, private val store: AlarmStore = AlarmStore(context)) {
    fun onEnter(ids: List<String>) {
        ids.distinct().mapNotNull(store::activeAlarm).forEach { AlarmNotification.show(context, it) }
    }
}
