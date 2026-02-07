package com.example.are_we_there_yet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives geofence transition events from the system.
 * Registered in AndroidManifest with exported=false (same-app only).
 */
class GeofenceReceiver : BroadcastReceiver() {

    private val TAG = "GeofenceReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)

        if (event == null) {
            Log.e(TAG, "GeofencingEvent is null")
            return
        }

        if (event.hasError()) {
            Log.e(TAG, "Geofence error code: ${event.errorCode}")
            return
        }

        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            val ids = event.triggeringGeofences?.map { it.requestId } ?: emptyList()
            val message = "Entered geofence: ${ids.joinToString()}"
            Log.d(TAG, message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
