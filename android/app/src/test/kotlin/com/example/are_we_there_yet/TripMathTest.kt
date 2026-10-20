package com.example.are_we_there_yet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `updates slow down and drop GPS with distance`() {
        // Speed unknown: half the distance left at 180 km/h, at least the tier's.
        fun plan(edgeKm: Double) = TripMath.locationPlan(edgeKm * 1_000)
        assertEquals(LocationPlan(5_000L, highAccuracy = true), plan(-0.1))
        assertEquals(LocationPlan(10_000L, highAccuracy = true), plan(1.0))
        assertEquals(LocationPlan(30_000L, highAccuracy = true), plan(2.5))
        assertEquals(LocationPlan(100_000L, highAccuracy = true), plan(10.0))
        assertEquals(LocationPlan(105_000L, highAccuracy = false), plan(10.5))
        assertEquals(LocationPlan(600_000L, highAccuracy = false), plan(60.0))
        assertEquals(LocationPlan(TripMath.MAX_INTERVAL_MILLIS, highAccuracy = false), plan(400.0))
    }

    @Test
    fun `a slow trip checks less often than a fast one`() {
        val stopped = TripMath.locationPlan(10_500.0, speedMps = 0.0, maxSpeedMps = 0.0)
        // Accelerating at 2 m/s² to 90 km/h: 12.5 s, then 25 m/s for the rest of 5.25 km.
        assertEquals(216_250L, stopped.intervalMillis)
        val fast = TripMath.locationPlan(10_500.0, speedMps = 40.0, maxSpeedMps = 40.0)
        assertTrue(fast.intervalMillis < stopped.intervalMillis)
        assertEquals(61_838L, TripMath.locationPlan(10_500.0, speedMps = 80.0, maxSpeedMps = 80.0).intervalMillis)
    }

    @Test
    fun `twice the assumed top speed still cannot reach the radius before the next fix`() {
        for (edge in generateSequence(50.0) { it * 1.3 }.takeWhile { it < 1_000_000 }) {
            for (max in listOf(null, 0.0, 10.0, 25.0, 40.0)) {
                for (speed in listOf(null, 0.0, 5.0, 20.0, 40.0)) {
                    val plan = TripMath.locationPlan(edge, speedMps = speed, maxSpeedMps = max)
                    val top = TripMath.topSpeed(max)
                    val reach = TripMath.secondsToCover(edge, speed ?: top, 2 * top)
                    val tierMinimum = TripMath.locationPlan(edge, speedMps = 1_000.0, maxSpeedMps = 1_000.0)
                    if (plan.intervalMillis > tierMinimum.intervalMillis) {
                        assertTrue("edge $edge max $max speed $speed", reach * 1_000 >= plan.intervalMillis - 1)
                    }
                }
            }
        }
    }

    @Test
    fun `covering a distance accelerates up to the top speed`() {
        assertEquals(10.0, TripMath.secondsToCover(100.0, 0.0, 25.0), 1e-9) // a t² / 2
        assertEquals(12.5 + (1_000 - 156.25) / 25, TripMath.secondsToCover(1_000.0, 0.0, 25.0), 1e-9)
        assertEquals(40.0, TripMath.secondsToCover(1_000.0, 25.0, 25.0), 1e-9)
        assertEquals(0.0, TripMath.secondsToCover(-5.0, 0.0, 25.0), 1e-9)
        assertEquals(50.0, TripMath.topSpeed(null), 1e-9)
        assertEquals(25.0, TripMath.topSpeed(3.0), 1e-9)
        assertEquals(45.0, TripMath.topSpeed(30.0), 1e-9)
        assertEquals(85.0, TripMath.topSpeed(90.0), 1e-9)
    }

    @Test
    fun `with speed unknown no fix can be skipped at 180 km per h`() {
        val speed = 50.0 // m/s
        for (edge in generateSequence(2_000.5) { it * 1.1 }.takeWhile { it < 1_000_000 }) {
            val plan = TripMath.locationPlan(edge)
            assertTrue("edge $edge", edge - speed * plan.intervalMillis / 1_000 > 0)
        }
    }

    @Test
    fun `first fix, direction screen and failed low power use GPS`() {
        assertEquals(TripMath.NEAR_PLAN, TripMath.locationPlan(null))
        assertEquals(TripMath.FAST_PLAN, TripMath.locationPlan(400_000.0, fast = true))
        assertEquals(
            LocationPlan(TripMath.MAX_INTERVAL_MILLIS, highAccuracy = true),
            TripMath.locationPlan(400_000.0, lowPowerAllowed = false),
        )
    }

    @Test
    fun `edge distance counts the radius and the fix error`() {
        assertEquals(9_500.0, TripMath.edgeMeters(10_000.0, 200.0, 300.0), 1e-9)
        assertEquals(9_800.0, TripMath.edgeMeters(10_000.0, 200.0, null), 1e-9)
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

    @Test
    fun `bearing is measured clockwise from north`() {
        assertEquals(0.0, TripMath.bearingDegrees(12.0, 77.0, 13.0, 77.0), 0.5)
        assertEquals(90.0, TripMath.bearingDegrees(0.0, 77.0, 0.0, 78.0), 0.5)
        assertEquals(180.0, TripMath.bearingDegrees(13.0, 77.0, 12.0, 77.0), 0.5)
        assertEquals(270.0, TripMath.bearingDegrees(0.0, 78.0, 0.0, 77.0), 0.5)
    }

    @Test
    fun `speed is smoothed toward new samples`() {
        assertEquals(10.0, TripMath.smoothSpeed(null, 10.0), 1e-9)
        assertEquals(13.0, TripMath.smoothSpeed(10.0, 20.0), 1e-9)
    }

    @Test
    fun `eta needs at least walking pace`() {
        assertEquals(100.0, TripMath.etaSeconds(1_000.0, 10.0)!!, 1e-9)
        assertNull(TripMath.etaSeconds(1_000.0, 0.5))
        assertNull(TripMath.etaSeconds(1_000.0, null))
    }
}
