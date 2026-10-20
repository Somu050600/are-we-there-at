package com.example.are_we_there_yet

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How often and how precisely to ask for location. Low accuracy uses Wi-Fi and
 * cell towers instead of GPS: far cheaper, but off by up to a few kilometres.
 */
data class LocationPlan(val intervalMillis: Long, val highAccuracy: Boolean)

/** Straight-line trip calculations; road distance is out of scope. */
object TripMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    const val MIN_ETA_SPEED = 0.7 // m/s, walking pace
    private const val SPEED_SMOOTHING = 0.3

    val FAST_PLAN = LocationPlan(1_000L, highAccuracy = true)
    val NEAR_PLAN = LocationPlan(5_000L, highAccuracy = true)

    /**
     * Tiers by distance to the edge of the arrival radius: the closest tier whose
     * limit is at or beyond the distance applies. Each interval is short enough
     * that at 180 km/h the next fix still lands outside the radius, and low
     * accuracy is only used where its error is far smaller than the distance left.
     */
    private val TIERS = listOf(
        2_000.0 to NEAR_PLAN,
        10_000.0 to LocationPlan(30_000L, highAccuracy = true),
        50_000.0 to LocationPlan(60_000L, highAccuracy = false),
        100_000.0 to LocationPlan(3 * 60_000L, highAccuracy = false),
    )
    private val FARTHEST_PLAN = LocationPlan(5 * 60_000L, highAccuracy = false)

    /**
     * [edgeMeters] is the distance left to the arrival radius, or null before the
     * first fix (near rate, for a quick first fix). [fast] is the direction
     * screen. Without [lowPowerAllowed] every tier uses GPS.
     */
    fun locationPlan(edgeMeters: Double?, fast: Boolean = false, lowPowerAllowed: Boolean = true): LocationPlan {
        if (fast) return FAST_PLAN
        if (edgeMeters == null) return NEAR_PLAN
        val plan = TIERS.firstOrNull { (limit, _) -> edgeMeters <= limit }?.second ?: FARTHEST_PLAN
        return if (lowPowerAllowed) plan else plan.copy(highAccuracy = true)
    }

    /** Distance to the radius edge, counting the fix's error against us. */
    fun edgeMeters(distance: Double, radius: Double, accuracy: Double?): Double =
        distance - radius - (accuracy ?: 0.0)

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from the first point to the second, clockwise from north. */
    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun smoothSpeed(previous: Double?, sample: Double): Double =
        if (previous == null) sample else previous + SPEED_SMOOTHING * (sample - previous)

    fun etaSeconds(distance: Double, speed: Double?): Double? =
        if (speed == null || speed < MIN_ETA_SPEED) null else distance / speed

    fun progress(start: Double, current: Double): Double =
        if (start <= 0) 1.0 else (1 - current / start).coerceIn(0.0, 1.0)

    fun isArrived(distance: Double, radius: Double) = distance <= radius

    fun formatDistance(meters: Double): String =
        if (meters >= 1_000) String.format(Locale.US, "%.1f km", meters / 1_000) else "${meters.roundToInt()} m"

    /** Rounded down so the bar only reaches 100% on arrival. */
    fun percent(progress: Double?): Int = floor((progress ?: 0.0) * 100).toInt().coerceIn(0, 100)
}
