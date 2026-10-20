package com.example.are_we_there_yet

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** What an alarm says; travels in intents so a snoozed alarm can ring again. */
data class AlarmContent(val name: String, val title: String, val text: String, val headline: String) {
    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_NAME, name).putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_TEXT, text).putExtra(EXTRA_HEADLINE, headline)

    companion object {
        private const val EXTRA_NAME = "alarm_name"
        private const val EXTRA_TITLE = "alarm_title"
        private const val EXTRA_TEXT = "alarm_text"
        private const val EXTRA_HEADLINE = "alarm_headline"

        fun from(intent: Intent): AlarmContent? = AlarmContent(
            name = intent.getStringExtra(EXTRA_NAME) ?: return null,
            title = intent.getStringExtra(EXTRA_TITLE) ?: return null,
            text = intent.getStringExtra(EXTRA_TEXT) ?: return null,
            headline = intent.getStringExtra(EXTRA_HEADLINE) ?: return null,
        )
    }
}

/** Snooze silences an alarm and rings the same one again a few minutes later. */
object AlarmSnooze {
    const val MINUTES = 5
    private const val ACTION_SNOOZE = "com.example.are_we_there_yet.alarm.SNOOZE"
    private const val ACTION_RING = "com.example.are_we_there_yet.alarm.RING"

    fun snoozeIntent(context: Context, content: AlarmContent): PendingIntent = PendingIntent.getBroadcast(
        context, 1, content.putInto(Intent(context, AlarmSnoozeReceiver::class.java).setAction(ACTION_SNOOZE)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Extras do not count when matching, so this also finds the pending one to cancel. */
    private fun ringIntent(context: Context, content: AlarmContent?): PendingIntent {
        val intent = Intent(context, AlarmSnoozeReceiver::class.java).setAction(ACTION_RING)
        content?.putInto(intent)
        return PendingIntent.getBroadcast(
            context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun snooze(context: Context, content: AlarmContent) {
        ArrivalActivity.stopAlarm(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + MINUTES * 60_000L
        val ring = ringIntent(context, content)
        // Exact when allowed (USE_EXACT_ALARM); otherwise Doze may add a few minutes.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, ring)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, ring)
        }
    }

    /** A new alarm or trip replaces a snoozed one. */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(ringIntent(context, null))
    }

    fun ring(context: Context, content: AlarmContent) {
        val store = TripStore(context)
        val fullScreen = store.fullScreenEnabled && TripNotifications.canUseFullScreen(context)
        context.getSystemService(NotificationManager::class.java).notify(
            TripNotifications.ARRIVAL_ID,
            TripNotifications.alarm(context, content, fullScreen, store.vibrateOnly),
        )
    }

    internal fun handle(context: Context, intent: Intent) {
        val content = AlarmContent.from(intent) ?: return
        when (intent.action) {
            ACTION_SNOOZE -> snooze(context, content)
            ACTION_RING -> ring(context, content)
        }
    }
}

class AlarmSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AlarmSnooze.handle(context, intent)
}
