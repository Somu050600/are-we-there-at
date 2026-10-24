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
    /** Also ring this many minutes before arriving; null for radius only. */
    val alertMinutes: Int? = null,
    /** The time alert has rung. */
    val alerted: Boolean = false,
) {
    /** The time alert, in seconds, while it is still to ring. */
    val pendingAlertSeconds: Double? get() = alertMinutes?.takeUnless { alerted }?.let { it * 60.0 }
}

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
    /** Location is unavailable right now (tunnel, no sky view). */
    val waitingForSignal: Boolean = false,
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
        "fixTimeMillis" to fix?.timeMillis,
        "waitingForSignal" to waitingForSignal,
    )

    companion object {
        val IDLE = TripSnapshot(TripStatus.IDLE)
    }
}

/** Durable trip state, so a restarted service or app can recover the active trip. */
class TripStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized fun active(): Trip? {
        val raw = preferences.getString(KEY_ACTIVE, null) ?: return null
        // Read on every fix and battery broadcast; parse only when it changed.
        // Keyed by the stored text, so it is shared safely by every instance.
        parsed?.let { (text, trip) -> if (text == raw) return trip }
        return parse(raw).also { parsed = raw to it }
    }

    private fun parse(raw: String): Trip? =
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
                alertMinutes = if (json.isNull("alertMinutes")) null else json.getInt("alertMinutes"),
                alerted = json.optBoolean("alerted", false),
            )
        }.getOrNull()

    @Synchronized fun saveActive(trip: Trip) {
        val json = JSONObject()
            .put("id", trip.id).put("name", trip.name)
            .put("lat", trip.lat).put("lng", trip.lng).put("radius", trip.radius)
            .put("startDistance", trip.startDistance ?: JSONObject.NULL)
            .put("paused", trip.paused)
            .put("alertMinutes", trip.alertMinutes ?: JSONObject.NULL)
            .put("alerted", trip.alerted)
        commit { putString(KEY_ACTIVE, json.toString()) }
    }

    @Synchronized fun clearActive() = commit { remove(KEY_ACTIVE) }

    var fullScreenEnabled: Boolean
        @Synchronized get() = preferences.getBoolean(KEY_FULL_SCREEN, true)
        @Synchronized set(value) = commit { putBoolean(KEY_FULL_SCREEN, value) }

    /** The largest radius the destination sheet offers, in metres. */
    var maxRadius: Double
        @Synchronized get() = preferences.getFloat(KEY_MAX_RADIUS, DEFAULT_MAX_RADIUS.toFloat()).toDouble()
        @Synchronized set(value) = commit { putFloat(KEY_MAX_RADIUS, value.toFloat()) }

    /** Alarms vibrate without sound, e.g. for a quiet carriage. */
    var vibrateOnly: Boolean
        @Synchronized get() = preferences.getBoolean(KEY_VIBRATE_ONLY, false)
        @Synchronized set(value) = commit { putBoolean(KEY_VIBRATE_ONLY, value) }

    /** Add place searches Google as you type instead of Android's free geocoder. */
    var googleSearch: Boolean
        @Synchronized get() = preferences.getBoolean(KEY_GOOGLE_SEARCH, false)
        @Synchronized set(value) = commit { putBoolean(KEY_GOOGLE_SEARCH, value) }

    /** A shared place Google doesn't know by its id is searched for by name (Text Search). */
    var placesSearchFallback: Boolean
        @Synchronized get() = preferences.getBoolean(KEY_PLACES_FALLBACK, true)
        @Synchronized set(value) = commit { putBoolean(KEY_PLACES_FALLBACK, value) }

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
        @Volatile private var parsed: Pair<String, Trip?>? = null
        private const val KEY_ACTIVE = "active"
        private const val KEY_FULL_SCREEN = "full_screen_alarm"
        private const val KEY_LEGACY_CLEANUP = "legacy_geofences_removed"
        private const val KEY_VIBRATE_ONLY = "vibrate_only"
        private const val KEY_GOOGLE_SEARCH = "google_search"
        private const val KEY_PLACES_FALLBACK = "places_search_fallback"
        private const val KEY_MAX_RADIUS = "max_radius_meters"
        private const val DEFAULT_MAX_RADIUS = 10_000.0
    }
}
