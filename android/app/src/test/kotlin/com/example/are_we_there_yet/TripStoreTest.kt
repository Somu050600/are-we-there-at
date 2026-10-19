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
