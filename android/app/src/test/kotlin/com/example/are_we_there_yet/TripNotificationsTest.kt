package com.example.are_we_there_yet

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TripNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val trip = Trip("a", "Aurva Bangalore", 12.91, 77.65, 200.0, startDistance = 5_000.0, paused = false)
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun Notification.title() = extras.getString(Notification.EXTRA_TITLE)
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.actionTitles() = actions.orEmpty().map { it.title.toString() }

    @Test
    fun `progress before the first fix says calculating`() {
        val notification = TripNotifications.progress(context, trip.copy(startDistance = null), null, null)
        assertEquals("Travelling to Aurva Bangalore", notification.title())
        assertEquals("Calculating… remaining (0%)", notification.text())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("Pause", "Stop Tracking"), notification.actionTitles())
    }

    @Test
    fun `progress shows remaining distance and rounded percent`() {
        val notification = TripNotifications.progress(context, trip, 4_230.0, 0.634)
        assertEquals("4.2 km remaining (63%)", notification.text())
        assertEquals(63, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    @Test
    fun `paused trip offers resume`() {
        val notification = TripNotifications.progress(context, trip.copy(paused = true), 950.0, 0.81)
        assertEquals("Paused · 950 m remaining", notification.text())
        assertEquals(listOf("Resume", "Stop Tracking"), notification.actionTitles())
    }

    @Test
    fun `arrival alarm loops until dismissed and can go full screen`() {
        val plain = TripNotifications.arrival(context, trip, fullScreen = false)
        assertEquals("Arrived at Aurva Bangalore", plain.title())
        assertEquals(Notification.CATEGORY_ALARM, plain.category)
        assertTrue(plain.flags and Notification.FLAG_INSISTENT != 0)
        assertNull(plain.fullScreenIntent)
        assertEquals(listOf("Stop"), plain.actionTitles())

        assertNotNull(TripNotifications.arrival(context, trip, fullScreen = true).fullScreenIntent)
    }

    @Test
    fun `channels separate silent progress from the alarm`() {
        TripNotifications.ensureChannels(context)
        val progress = manager.getNotificationChannel(TripNotifications.PROGRESS_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, progress.importance)
        assertNull(progress.sound)
        val alarm = manager.getNotificationChannel(TripNotifications.ARRIVAL_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alarm.importance)
        assertNotNull(alarm.sound)
    }

    @Test
    fun `each notification has its own group so Android cannot bundle and mute it`() {
        val all = listOf(
            TripNotifications.progress(context, trip, 1_000.0, 0.5),
            TripNotifications.arrival(context, trip, fullScreen = true),
            TripNotifications.restartNotice(context, trip),
        )
        assertEquals(3, all.map { it.group }.toSet().size)
        all.forEach { assertEquals(Notification.GROUP_ALERT_ALL, it.groupAlertBehavior) }
    }

    @Test
    fun `restart notice asks to resume`() {
        val notice = TripNotifications.restartNotice(context, trip)
        assertEquals("Trip to Aurva Bangalore stopped by restart. Tap to resume.", notice.text())
        assertTrue(shadowOf(notice.contentIntent).isActivityIntent)
    }
}
