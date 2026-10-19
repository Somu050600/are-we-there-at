package com.example.are_we_there_yet

import java.util.Locale
import kotlin.math.asin
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

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

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
