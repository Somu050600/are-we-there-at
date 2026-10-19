package com.example.are_we_there_yet

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlarmNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: AlarmStore

    @Before
    fun setUp() {
        context.getSharedPreferences(AlarmStore.PREFERENCES, 0).edit().clear().commit()
        store = AlarmStore(context)
    }

    @Test
    fun `entry posts a high importance named notification for an active alarm`() {
        store.saveActive(AlarmRecord("home", 1.0, 2.0, 100f, "Home"))

        GeofenceEntryHandler(context, store).onEnter(listOf("home"))

        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("Arrived at Home", notification.extras.getString("android.title"))
        assertNotNull(manager.getNotificationChannel(AlarmNotification.CHANNEL_ID))
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(AlarmNotification.CHANNEL_ID).importance,
        )
    }

    @Test
    fun `pending removal suppresses stale geofence entry`() {
        store.saveActive(AlarmRecord("gone", 1.0, 2.0, 100f, "Gone"))
        store.beginRemoval("gone")

        GeofenceEntryHandler(context, store).onEnter(listOf("gone"))

        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNull(shadowOf(manager).allNotifications.singleOrNull())
    }

    @Test
    fun `alarms with colliding hash codes keep separate dismiss and open actions`() {
        // "Aa" and "BB" share String.hashCode(), the old PendingIntent request code.
        store.saveActive(AlarmRecord("Aa", 1.0, 2.0, 100f, "First"))
        store.saveActive(AlarmRecord("BB", 3.0, 4.0, 100f, "Second"))

        GeofenceEntryHandler(context, store).onEnter(listOf("Aa", "BB"))

        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.size)
        val dismissed = notifications.map {
            shadowOf(it.deleteIntent).savedIntent.getStringExtra(AlarmNotification.EXTRA_ALARM_ID)
        }
        assertEquals(setOf("Aa", "BB"), dismissed.toSet())
        assertNotEquals(notifications[0].contentIntent, notifications[1].contentIntent)
        assertTrue(shadowOf(notifications[0].contentIntent).isActivityIntent)
    }

    @Test
    fun `dismiss action cancels only its own alarm notification`() {
        store.saveActive(AlarmRecord("Aa", 1.0, 2.0, 100f, "First"))
        store.saveActive(AlarmRecord("BB", 3.0, 4.0, 100f, "Second"))
        GeofenceEntryHandler(context, store).onEnter(listOf("Aa", "BB"))
        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        val first = shadowOf(manager).allNotifications.first {
            it.extras.getString("android.title") == "Arrived at First"
        }

        NotificationDismissReceiver().onReceive(context, shadowOf(first.deleteIntent).savedIntent)

        val remaining = shadowOf(manager).allNotifications.map { it.extras.getString("android.title") }
        assertEquals(listOf("Arrived at Second"), remaining)
    }
}
