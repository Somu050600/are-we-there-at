package com.example.are_we_there_yet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMathTest {
    @Test
    fun `distance between two nearby Bengaluru points`() {
        assertEquals(191.0, TripMath.distanceMeters(12.9784, 77.5710, 12.9767, 77.5713), 2.0)
    }

    @Test
    fun `progress is the share of the starting distance covered`() {
        assertEquals(0.75, TripMath.progress(start = 1000.0, current = 250.0), 1e-9)
        assertEquals(0.0, TripMath.progress(start = 1000.0, current = 1500.0), 1e-9)
        assertEquals(1.0, TripMath.progress(start = 1000.0, current = 0.0), 1e-9)
        assertEquals(1.0, TripMath.progress(start = 0.0, current = 0.0), 1e-9)
    }

    @Test
    fun `arrival includes the radius boundary`() {
        assertTrue(TripMath.isArrived(distance = 200.0, radius = 200.0))
        assertFalse(TripMath.isArrived(distance = 200.5, radius = 200.0))
    }

    @Test
    fun `updates are frequent only near the destination`() {
        assertEquals(30_000L, TripMath.updateIntervalMillis(2_500.0))
        assertEquals(5_000L, TripMath.updateIntervalMillis(2_000.0))
        assertEquals(5_000L, TripMath.updateIntervalMillis(150.0))
    }

    @Test
    fun `distance labels use km above one kilometre`() {
        assertEquals("4.2 km", TripMath.formatDistance(4_230.0))
        assertEquals("950 m", TripMath.formatDistance(950.0))
        assertEquals("12 m", TripMath.formatDistance(12.4))
    }

    @Test
    fun `percent rounds down so 100 only shows on arrival`() {
        assertEquals(63, TripMath.percent(0.634))
        assertEquals(99, TripMath.percent(0.999))
        assertEquals(0, TripMath.percent(null))
    }
}
