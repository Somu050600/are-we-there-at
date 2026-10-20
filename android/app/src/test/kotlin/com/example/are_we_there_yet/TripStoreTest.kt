package com.example.are_we_there_yet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.getSharedPreferences(TripStore.PREFERENCES, 0).edit().clear().commit()
    }

    @Test
    fun `active trip survives a new store instance`() {
        val trip = Trip("a", "Station", 12.97, 77.57, 200.0, startDistance = null, paused = false)
        TripStore(context).saveActive(trip)

        assertEquals(trip, TripStore(context).active())

        val moved = trip.copy(startDistance = 1_250.5, paused = true)
        TripStore(context).saveActive(moved)
        assertEquals(moved, TripStore(context).active())
    }

    @Test
    fun `time alert settings persist and default when missing`() {
        val trip = Trip("a", "Station", 1.0, 2.0, 100.0, null, false, alertMinutes = 10, alerted = true)
        TripStore(context).saveActive(trip)
        assertEquals(trip, TripStore(context).active())

        // Saved by a version without the time alert.
        context.getSharedPreferences(TripStore.PREFERENCES, 0).edit().putString(
            "active",
            """{"id":"a","name":"Station","lat":1,"lng":2,"radius":100,"startDistance":null,"paused":false}""",
        ).commit()
        val old = TripStore(context).active()!!
        assertNull(old.alertMinutes)
        assertFalse(old.alerted)
    }

    @Test
    fun `every instance sees the latest trip despite caching`() {
        val service = TripStore(context)
        val activity = TripStore(context)
        val trip = Trip("a", "Station", 1.0, 2.0, 100.0, null, false)
        service.saveActive(trip)
        assertEquals(trip, activity.active())
        service.saveActive(trip.copy(paused = true))
        assertTrue(activity.active()!!.paused)
        service.clearActive()
        assertNull(activity.active())
    }

    @Test
    fun `vibrate only defaults off and persists`() {
        assertFalse(TripStore(context).vibrateOnly)
        TripStore(context).vibrateOnly = true
        assertTrue(TripStore(context).vibrateOnly)
    }

    @Test
    fun `clearing removes the active trip`() {
        val store = TripStore(context)
        store.saveActive(Trip("a", "Station", 1.0, 2.0, 100.0, null, false))
        store.clearActive()
        assertNull(TripStore(context).active())
    }

    @Test
    fun `full-screen alarm defaults on and persists`() {
        assertTrue(TripStore(context).fullScreenEnabled)
        TripStore(context).fullScreenEnabled = false
        assertFalse(TripStore(context).fullScreenEnabled)
    }

    @Test
    fun `legacy cleanup flag persists`() {
        assertFalse(TripStore(context).legacyCleanupDone)
        TripStore(context).legacyCleanupDone = true
        assertTrue(TripStore(context).legacyCleanupDone)
    }

    @Test
    fun `corrupt stored trip reads as none`() {
        context.getSharedPreferences(TripStore.PREFERENCES, 0).edit().putString("active", "{bad").commit()
        assertNull(TripStore(context).active())
    }
}
