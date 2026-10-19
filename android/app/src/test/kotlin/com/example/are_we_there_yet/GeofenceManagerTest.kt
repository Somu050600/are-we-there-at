package com.example.are_we_there_yet

import com.google.android.gms.location.GeofencingRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class GeofenceManagerTest {
    private val alarm = AlarmRecord("home", 1.0, 2.0, 100f, "Home")

    @Test
    fun `sync and boot request has no initial entry trigger`() {
        val request = GeofenceManager.buildRequest(listOf(alarm), initialTrigger = false)

        assertEquals(0, request.initialTrigger)
    }

    @Test
    fun `interactive add requests an initial entry transition`() {
        val request = GeofenceManager.buildRequest(listOf(alarm), initialTrigger = true)

        assertEquals(GeofencingRequest.INITIAL_TRIGGER_ENTER, request.initialTrigger)
    }
}
