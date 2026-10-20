package com.example.are_we_there_yet

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * One location reading; [speedMps] and [accuracyMeters] (68% confidence radius)
 * are null when the provider does not report them.
 */
data class Fix(
    val lat: Double,
    val lng: Double,
    val speedMps: Double?,
    val timeMillis: Long,
    val accuracyMeters: Double? = null,
)

interface LocationSource {
    /**
     * Replaces any earlier request. [onUnavailable] runs when the provider says
     * no fixes can be delivered for now.
     */
    fun start(plan: LocationPlan, onFix: (Fix) -> Unit, onUnavailable: () -> Unit)
    fun stop()
}

class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // TripService only starts after the permission check.
    override fun start(plan: LocationPlan, onFix: (Fix) -> Unit, onUnavailable: () -> Unit) {
        stop()
        // Balanced priority depends on network location, which users can turn
        // off; then no fixes arrive at all. TripService falls back to GPS then.
        val priority = if (plan.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, plan.intervalMillis)
            .setMinUpdateIntervalMillis(plan.intervalMillis / 2)
            .build()
        val next = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    onFix(
                        Fix(
                            it.latitude, it.longitude,
                            if (it.hasSpeed()) it.speed.toDouble() else null,
                            it.time,
                            if (it.hasAccuracy()) it.accuracy.toDouble() else null,
                        ),
                    )
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) onUnavailable()
            }
        }
        callback = next
        client.requestLocationUpdates(request, next, Looper.getMainLooper())
    }

    override fun stop() {
        callback?.let(client::removeLocationUpdates)
        callback = null
    }
}
