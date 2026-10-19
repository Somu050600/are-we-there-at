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
    private var lastFix: Fix? = null
    private var speed: Double? = null
    /** Set while the direction screen is open. */
    private var fast = false

    override fun onCreate() {
        super.onCreate()
        store = TripStore(this)
        source = sourceFactory(this)
        isRunning = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> start(NativeArguments.trip(intent.tripArguments()))
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                ACTION_STOP -> stop()
                ACTION_FAST -> setFast(intent.getBooleanExtra(EXTRA_ENABLED, false))
                // ACTION_RECOVER from the app, or a null intent when the system
                // restarts the service after the process died.
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
        isRunning = false
        super.onDestroy()
    }

    private fun start(trip: Trip) {
        clearRestartNotice()
        store.saveActive(trip)
        lastDistance = null
        lastFix = null
        speed = null
        goForeground(trip)
        track(intervalFor(0.0)) // near-rate until the first fix
        publish(TripStatus.TRACKING, trip)
    }

    private fun recover(trip: Trip) {
        clearRestartNotice()
        goForeground(trip)
        if (trip.paused) {
            publish(TripStatus.PAUSED, trip)
        } else {
            track(intervalFor(0.0))
            publish(TripStatus.TRACKING, trip)
        }
    }

    private fun setFast(enabled: Boolean) {
        fast = enabled
        val trip = store.active()
        if (trip == null) {
            if (!isTracking()) stopSelf()
            return
        }
        if (!trip.paused) track(intervalFor(lastDistance ?: 0.0))
    }

    private fun isTracking() = interval != null

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
        track(intervalFor(lastDistance ?: 0.0))
        publish(TripStatus.TRACKING, resumed)
    }

    private fun stop() {
        source.stop()
        interval = null
        lastDistance = null
        lastFix = null
        speed = null
        store.clearActive()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        TripEvents.publish(TripSnapshot.IDLE)
    }

    private fun onLocation(fix: Fix) {
        val trip = store.active()?.takeUnless { it.paused } ?: return
        val distance = TripMath.distanceMeters(fix.lat, fix.lng, trip.lat, trip.lng)
        val previous = lastFix
        val sample = fix.speedMps ?: previous?.let {
            val seconds = (fix.timeMillis - it.timeMillis) / 1000.0
            if (seconds > 0) TripMath.distanceMeters(it.lat, it.lng, fix.lat, fix.lng) / seconds else null
        }
        if (sample != null) speed = TripMath.smoothSpeed(speed, sample)
        lastFix = fix
        lastDistance = distance
        if (TripMath.isArrived(distance, trip.radius)) return arrive(trip)
        val current = if (trip.startDistance == null) trip.copy(startDistance = distance).also(store::saveActive) else trip
        notifyProgress(current)
        publish(TripStatus.TRACKING, current)
        val next = intervalFor(distance)
        if (next != interval) track(next)
    }

    private fun arrive(trip: Trip) {
        source.stop()
        interval = null
        store.clearActive()
        val fullScreen = store.fullScreenEnabled && TripNotifications.canUseFullScreen(this)
        // Remove the progress notification first so the alarm is the only one up.
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager().notify(TripNotifications.ARRIVAL_ID, TripNotifications.arrival(this, trip, fullScreen))
        stopSelf()
        TripEvents.publish(TripSnapshot(TripStatus.ARRIVED, trip, lastDistance, 1.0, lastFix, speed, null, 0.0))
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

    private fun intervalFor(distance: Double) =
        if (fast) TripMath.FAST_INTERVAL_MILLIS else TripMath.updateIntervalMillis(distance)

    private fun publish(status: TripStatus, trip: Trip) {
        val fix = lastFix
        val distance = lastDistance
        TripEvents.publish(
            TripSnapshot(
                status, trip, distance, progressOf(trip), fix, speed,
                bearing = fix?.let { TripMath.bearingDegrees(it.lat, it.lng, trip.lat, trip.lng) },
                eta = if (status == TripStatus.TRACKING && distance != null) TripMath.etaSeconds(distance, speed) else null,
            ),
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun clearRestartNotice() = notificationManager().cancel(TripNotifications.RESTART_ID)

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
        const val ACTION_RECOVER = "com.example.are_we_there_yet.trip.RECOVER"
        const val ACTION_FAST = "com.example.are_we_there_yet.trip.FAST"
        private const val EXTRA_ENABLED = "enabled"

        /** False after a force-stop, update or reboot killed the service. */
        @Volatile var isRunning = false
            private set
        val DEFAULT_SOURCE: (Context) -> LocationSource = { FusedLocationSource(it) }
        /** Replaced in tests. */
        @Volatile var sourceFactory: (Context) -> LocationSource = DEFAULT_SOURCE

        fun startIntent(context: Context, trip: Trip): Intent = actionIntent(context, ACTION_START)
            .putExtra("id", trip.id).putExtra("name", trip.name)
            .putExtra("lat", trip.lat).putExtra("lng", trip.lng).putExtra("radius", trip.radius)

        fun fastIntent(context: Context, enabled: Boolean): Intent =
            actionIntent(context, ACTION_FAST).putExtra(EXTRA_ENABLED, enabled)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, TripService::class.java).setAction(action)
    }
}
