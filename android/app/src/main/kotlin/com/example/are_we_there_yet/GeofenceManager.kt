package com.example.are_we_there_yet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task

/**
 * Handles geofence registration and permission checks (read-only).
 * Does NOT request permissions — that is MainActivity's job.
 */
class GeofenceManager(private val context: Context) {

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)
    private val TAG = "GeofenceManager"

    // ── Permission checks (read-only) ──────────────────────────

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // ── Geofence registration ──────────────────────────────────

    /**
     * Builds a circular ENTER-only geofence and registers it.
     * Returns a Task so the caller can attach success/failure listeners.
     */
    fun addGeofence(
        id: String,
        latitude: Double,
        longitude: Double,
        radius: Float,
        pendingIntent: android.app.PendingIntent
    ): Task<Void> {

        val geofence = Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(latitude, longitude, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        return geofencingClient.addGeofences(request, pendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "Geofence registered: $id")
            }
            .addOnFailureListener { e ->
                val msg = if (e is ApiException) "code=${e.statusCode}" else e.message
                Log.e(TAG, "Geofence registration failed ($id): $msg", e)
            }
    }
}
