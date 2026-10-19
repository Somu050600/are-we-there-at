package com.example.are_we_there_yet

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

interface GeofenceGateway {
    fun add(alarms: List<AlarmRecord>, initialTrigger: Boolean, callback: (Result<Unit>) -> Unit)
    fun remove(ids: Set<String>, callback: (Result<Unit>) -> Unit)
}

class GeofenceManager(private val context: Context) : GeofenceGateway {
    private val client = LocationServices.getGeofencingClient(context)

    fun hasFineLocationPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission() = android.os.Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    override fun add(alarms: List<AlarmRecord>, initialTrigger: Boolean, callback: (Result<Unit>) -> Unit) {
        if (alarms.isEmpty()) return callback(Result.success(Unit))
        val request = buildRequest(alarms, initialTrigger)
        client.addGeofences(request, pendingIntent())
            .addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    override fun remove(ids: Set<String>, callback: (Result<Unit>) -> Unit) {
        if (ids.isEmpty()) return callback(Result.success(Unit))
        client.removeGeofences(ids.toList())
            .addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    private fun pendingIntent() = PendingIntent.getBroadcast(
        context, 0, Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    companion object {
        internal fun buildRequest(alarms: List<AlarmRecord>, initialTrigger: Boolean): GeofencingRequest {
            val geofences = alarms.map { alarm ->
                Geofence.Builder().setRequestId(alarm.id)
                    .setCircularRegion(alarm.latitude, alarm.longitude, alarm.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER).build()
            }
            return GeofencingRequest.Builder()
                .addGeofences(geofences)
                .setInitialTrigger(if (initialTrigger) GeofencingRequest.INITIAL_TRIGGER_ENTER else 0)
                .build()
        }
    }
}
