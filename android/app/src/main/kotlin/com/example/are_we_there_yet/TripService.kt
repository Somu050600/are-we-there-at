package com.example.are_we_there_yet

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Foreground location service that tracks one trip and rings on arrival. */
class TripService : Service() {
    private lateinit var store: TripStore
    private lateinit var source: LocationSource
    private var interval: Long? = null
    private var lastDistance: Double? = null

    override fun onCreate() {
        super.onCreate()
        store = TripStore(this)
        source = sourceFactory(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> start(NativeArguments.trip(intent.tripArguments()))
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                ACTION_STOP -> stop()
                // Restarted by the system after the process died.
                else -> store.active()?.let(::recover) ?: stop()
            }
        } catch (failure: Exception) {
            Log.e(TAG, "Trip command failed", failure)
            stop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        source.stop()
        super.onDestroy()
    }

    private fun start(trip: Trip) {
        store.saveActive(trip)
        lastDistance = null
        goForeground(trip)
        track(TripMath.updateIntervalMillis(0.0)) // fast first fix
        publish(TripStatus.TRACKING, trip)
    }

    private fun recover(trip: Trip) {
        goForeground(trip)
        if (trip.paused) {
            publish(TripStatus.PAUSED, trip)
        } else {
            track(TripMath.updateIntervalMillis(0.0))
            publish(TripStatus.TRACKING, trip)
        }
    }

    private fun pause() {
        val trip = store.active() ?: return stop()
        source.stop()
        interval = null
        val paused = trip.copy(paused = true)
        store.saveActive(paused)
        notifyProgress(paused)
        publish(TripStatus.PAUSED, paused)
    }

    private fun resume() {
        val trip = store.active() ?: return stop()
        val resumed = trip.copy(paused = false)
        store.saveActive(resumed)
        goForeground(resumed)
        track(TripMath.updateIntervalMillis(lastDistance ?: 0.0))
        publish(TripStatus.TRACKING, resumed)
    }

    private fun stop() {
        source.stop()
        interval = null
        lastDistance = null
        store.clearActive()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        TripEvents.publish(TripSnapshot.IDLE)
    }

    private fun onLocation(lat: Double, lng: Double) {
        val trip = store.active()?.takeUnless { it.paused } ?: return
        val distance = TripMath.distanceMeters(lat, lng, trip.lat, trip.lng)
        lastDistance = distance
        if (TripMath.isArrived(distance, trip.radius)) return arrive(trip)
        val current = if (trip.startDistance == null) trip.copy(startDistance = distance).also(store::saveActive) else trip
        notifyProgress(current)
        publish(TripStatus.TRACKING, current)
        val next = TripMath.updateIntervalMillis(distance)
        if (next != interval) track(next)
    }

    private fun arrive(trip: Trip) {
        source.stop()
        interval = null
        store.clearActive()
        val fullScreen = store.fullScreenEnabled && TripNotifications.canUseFullScreen(this)
        notificationManager().notify(TripNotifications.ARRIVAL_ID, TripNotifications.arrival(this, trip, fullScreen))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        TripEvents.publish(TripSnapshot(TripStatus.ARRIVED, trip, lastDistance, 1.0))
    }

    private fun track(intervalMillis: Long) {
        interval = intervalMillis
        source.start(intervalMillis, ::onLocation)
    }

    private fun goForeground(trip: Trip) {
        val notification = TripNotifications.progress(this, trip, lastDistance, progressOf(trip))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(TripNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(TripNotifications.PROGRESS_ID, notification)
        }
    }

    private fun notifyProgress(trip: Trip) = notificationManager().notify(
        TripNotifications.PROGRESS_ID,
        TripNotifications.progress(this, trip, lastDistance, progressOf(trip)),
    )

    private fun progressOf(trip: Trip): Double? {
        val distance = lastDistance ?: return null
        return TripMath.progress(trip.startDistance ?: distance, distance)
    }

    private fun publish(status: TripStatus, trip: Trip) =
        TripEvents.publish(TripSnapshot(status, trip, lastDistance, progressOf(trip)))

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun Intent.tripArguments(): Map<String, Any?> = mapOf(
        "id" to getStringExtra("id"),
        "name" to getStringExtra("name"),
        "lat" to getDoubleExtra("lat", Double.NaN),
        "lng" to getDoubleExtra("lng", Double.NaN),
        "radius" to getDoubleExtra("radius", Double.NaN),
    )

    companion object {
        private const val TAG = "TripService"
        const val ACTION_START = "com.example.are_we_there_yet.trip.START"
        const val ACTION_PAUSE = "com.example.are_we_there_yet.trip.PAUSE"
        const val ACTION_RESUME = "com.example.are_we_there_yet.trip.RESUME"
        const val ACTION_STOP = "com.example.are_we_there_yet.trip.STOP"
        val DEFAULT_SOURCE: (Context) -> LocationSource = { FusedLocationSource(it) }
        /** Replaced in tests. */
        @Volatile var sourceFactory: (Context) -> LocationSource = DEFAULT_SOURCE

        fun startIntent(context: Context, trip: Trip): Intent = actionIntent(context, ACTION_START)
            .putExtra("id", trip.id).putExtra("name", trip.name)
            .putExtra("lat", trip.lat).putExtra("lng", trip.lng).putExtra("radius", trip.radius)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, TripService::class.java).setAction(action)
    }
}
