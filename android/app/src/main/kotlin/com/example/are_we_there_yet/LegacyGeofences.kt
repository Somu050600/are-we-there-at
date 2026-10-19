package com.example.are_we_there_yet

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationServices

/** Removes always-on geofences registered by versions before the trip model. */
object LegacyGeofences {
    private const val LEGACY_PREFERENCES = "native_alarm_state_v1"

    fun remove(context: Context, callback: (Result<Unit>) -> Unit = {}) {
        val store = TripStore(context)
        if (store.legacyCleanupDone) return callback(Result.success(Unit))
        // Same identity as the old GeofenceManager PendingIntent; the receiver class is gone.
        val intent = Intent().setComponent(ComponentName(context.packageName, "${context.packageName}.GeofenceReceiver"))
        val pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        LocationServices.getGeofencingClient(context).removeGeofences(pending).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                context.deleteSharedPreferences(LEGACY_PREFERENCES)
                store.legacyCleanupDone = true
                callback(Result.success(Unit))
            } else {
                Log.w("LegacyGeofences", "Could not remove legacy geofences", task.exception)
                callback(Result.failure(task.exception ?: IllegalStateException("Geofence removal failed")))
            }
        }
    }
}
