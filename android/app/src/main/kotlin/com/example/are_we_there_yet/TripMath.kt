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

/** Straight-line trip calculations; road distance is out of scope. */
object TripMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    const val NEAR_METERS = 2_000.0
    private const val FAR_INTERVAL_MILLIS = 30_000L
    private const val NEAR_INTERVAL_MILLIS = 5_000L
    const val FAST_INTERVAL_MILLIS = 1_000L
    private const val MIN_ETA_SPEED = 0.7 // m/s, below walking pace
    private const val SPEED_SMOOTHING = 0.3

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

    fun updateIntervalMillis(distance: Double) =
        if (distance > NEAR_METERS) FAR_INTERVAL_MILLIS else NEAR_INTERVAL_MILLIS

    fun formatDistance(meters: Double): String =
        if (meters >= 1_000) String.format(Locale.US, "%.1f km", meters / 1_000) else "${meters.roundToInt()} m"

    /** Rounded down so the bar only reaches 100% on arrival. */
    fun percent(progress: Double?): Int = floor((progress ?: 0.0) * 100).toInt().coerceIn(0, 100)
}
