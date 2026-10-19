package com.example.are_we_there_yet

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
