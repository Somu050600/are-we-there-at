package com.example.are_we_there_yet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlarmSnoozeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val trip = Trip("a", "Station", 12.97, 77.57, 200.0, startDistance = 5_000.0, paused = false)
    private val content = TripNotifications.arrivalContent(trip)

    @Before
    fun setUp() {
        context.getSharedPreferences(TripStore.PREFERENCES, 0).edit().clear().commit()
    }

    private fun alarmTitle() = shadowOf(manager).getNotification(TripNotifications.ARRIVAL_ID)
        ?.extras?.getString(Notification.EXTRA_TITLE)

    @Test
    fun `content travels in an intent`() {
        assertEquals(content, AlarmContent.from(content.putInto(Intent())))
        assertNull(AlarmContent.from(Intent()))
    }

    @Test
    fun `snooze silences the alarm and schedules it again`() {
        manager.notify(TripNotifications.ARRIVAL_ID, TripNotifications.arrival(context, trip, fullScreen = false))
        AlarmSnooze.snooze(context, content)
        assertNull(alarmTitle())
        val scheduled = shadowOf(alarms).scheduledAlarms
        assertEquals(1, scheduled.size)

        AlarmSnooze.ring(context, content)
        assertEquals("Arrived at Station", alarmTitle())
    }

    @Test
    fun `the snooze action goes through the receiver`() {
        val action = TripNotifications.arrival(context, trip, fullScreen = false).actions[1]
        action.actionIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test
    fun `a ringing snoozed alarm follows the vibrate setting`() {
        TripStore(context).vibrateOnly = true
        AlarmSnooze.ring(context, content)
        assertEquals(
            TripNotifications.ARRIVAL_VIBRATE_CHANNEL,
            shadowOf(manager).getNotification(TripNotifications.ARRIVAL_ID).channelId,
        )
    }

    @Test
    fun `cancel drops a pending snooze`() {
        AlarmSnooze.snooze(context, content)
        AlarmSnooze.cancel(context)
        assertEquals(0, shadowOf(alarms).scheduledAlarms.size)
    }
}
