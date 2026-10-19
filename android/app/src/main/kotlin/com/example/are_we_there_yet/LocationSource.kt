package com.example.are_we_there_yet

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

interface LocationSource {
    /** Replaces any earlier request. */
    fun start(intervalMillis: Long, onLocation: (Double, Double) -> Unit)
    fun stop()
}

class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // TripService only starts after the permission check.
    override fun start(intervalMillis: Long, onLocation: (Double, Double) -> Unit) {
        stop()
        val priority = if (intervalMillis <= TripMath.updateIntervalMillis(0.0)) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .build()
        val next = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocation(it.latitude, it.longitude) }
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
