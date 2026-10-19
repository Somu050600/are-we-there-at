package com.example.are_we_there_yet

import android.content.Context
import org.json.JSONObject

data class Trip(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Double,
    /** Distance at the first location fix; null until then. */
    val startDistance: Double?,
    val paused: Boolean,
)

enum class TripStatus { IDLE, TRACKING, PAUSED, ARRIVED }

data class TripSnapshot(
    val status: TripStatus,
    val trip: Trip? = null,
    val distance: Double? = null,
    val progress: Double? = null,
    val fix: Fix? = null,
    val speed: Double? = null,
    /** From the last fix to the destination, clockwise from north. */
    val bearing: Double? = null,
    val eta: Double? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "state" to status.name.lowercase(),
        "destinationId" to trip?.id,
        "name" to trip?.name,
        "distanceMeters" to distance,
        "progress" to progress,
        "lat" to fix?.lat,
        "lng" to fix?.lng,
        "speedMps" to speed,
        "bearingDegrees" to bearing,
        "etaSeconds" to eta,
    )

    companion object {
        val IDLE = TripSnapshot(TripStatus.IDLE)
    }
}

/** Durable trip state, so a restarted service or app can recover the active trip. */
class TripStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized fun active(): Trip? = preferences.getString(KEY_ACTIVE, null)?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            Trip(
                id = json.getString("id"),
                name = json.getString("name"),
                lat = json.getDouble("lat"),
                lng = json.getDouble("lng"),
                radius = json.getDouble("radius"),
                startDistance = if (json.isNull("startDistance")) null else json.getDouble("startDistance"),
                paused = json.getBoolean("paused"),
            )
        }.getOrNull()
    }

    @Synchronized fun saveActive(trip: Trip) {
        val json = JSONObject()
            .put("id", trip.id).put("name", trip.name)
            .put("lat", trip.lat).put("lng", trip.lng).put("radius", trip.radius)
            .put("startDistance", trip.startDistance ?: JSONObject.NULL)
            .put("paused", trip.paused)
        commit { putString(KEY_ACTIVE, json.toString()) }
    }

    @Synchronized fun clearActive() = commit { remove(KEY_ACTIVE) }

    var fullScreenEnabled: Boolean
        @Synchronized get() = preferences.getBoolean(KEY_FULL_SCREEN, true)
        @Synchronized set(value) = commit { putBoolean(KEY_FULL_SCREEN, value) }

    var legacyCleanupDone: Boolean
        @Synchronized get() = preferences.getBoolean(KEY_LEGACY_CLEANUP, false)
        @Synchronized set(value) = commit { putBoolean(KEY_LEGACY_CLEANUP, value) }

    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        if (!preferences.edit().apply(change).commit()) {
            throw IllegalStateException("Could not save trip state")
        }
    }

    companion object {
        internal const val PREFERENCES = "trip_state_v1"
        private const val KEY_ACTIVE = "active"
        private const val KEY_FULL_SCREEN = "full_screen_alarm"
        private const val KEY_LEGACY_CLEANUP = "legacy_geofences_removed"
    }
}
