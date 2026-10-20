package com.example.are_we_there_yet

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/** Foreground location service that tracks one trip and rings on arrival. */
class TripService : Service() {
    private lateinit var store: TripStore
    private lateinit var source: LocationSource
    private var plan: LocationPlan? = null
    private var lastDistance: Double? = null
    private var lastFix: Fix? = null
    private var speed: Double? = null
    /** Set while the direction screen is open. */
    private var fast = false
    /** Set once low-accuracy updates failed; GPS is used from then on. */
    private var lowPowerFailed = false
    private val batteryWatch = BatteryWatch()
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            BatteryLevel.fromIntent(intent)?.let(::onBattery)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = TripStore(this)
        source = sourceFactory(this)
        isRunning = true
        // Sticky: delivers the current level right away, then every change.
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
                ACTION_LOW_POWER_TIMEOUT -> fallBackToGps("No low-accuracy fix in time")
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
        stopTracking()
        unregisterReceiver(batteryReceiver)
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
        checkBattery()
        track(planFor(trip)) // near rate until the first fix
        publish(TripStatus.TRACKING, trip)
    }

    private fun recover(trip: Trip) {
        clearRestartNotice()
        goForeground(trip)
        checkBattery()
        if (trip.paused) {
            publish(TripStatus.PAUSED, trip)
        } else {
            track(planFor(trip))
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
        if (!trip.paused) track(planFor(trip))
    }

    private fun isTracking() = plan != null

    private fun pause() {
        val trip = store.active() ?: return stop()
        stopTracking()
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
        track(planFor(resumed))
        publish(TripStatus.TRACKING, resumed)
    }

    private fun stop() {
        stopTracking()
        notificationManager().cancel(TripNotifications.BATTERY_ID)
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
        // Below walking pace a reported speed is unreliable (providers report 0 or
        // near 0 after jumps), so fall back to the speed between fixes.
        val sample = fix.speedMps?.takeIf { it >= TripMath.MIN_ETA_SPEED } ?: previous?.let {
            val seconds = (fix.timeMillis - it.timeMillis) / 1000.0
            if (seconds > 0) TripMath.distanceMeters(it.lat, it.lng, fix.lat, fix.lng) / seconds else null
        }
        if (sample != null) speed = TripMath.smoothSpeed(speed, sample)
        lastFix = fix
        lastDistance = distance
        // A low-accuracy fix can be kilometres off; it switches to GPS (the near
        // plan, since its error reaches the radius) and the next fix decides.
        if (TripMath.isArrived(distance, trip.radius) && plan?.highAccuracy != false) return arrive(trip)
        val current = if (trip.startDistance == null) trip.copy(startDistance = distance).also(store::saveActive) else trip
        notifyProgress(current)
        publish(TripStatus.TRACKING, current)
        val next = planFor(current)
        if (next != plan) track(next) else watchLowPower(next)
    }

    private fun arrive(trip: Trip) {
        stopTracking()
        notificationManager().cancel(TripNotifications.BATTERY_ID)
        store.clearActive()
        val fullScreen = store.fullScreenEnabled && TripNotifications.canUseFullScreen(this)
        // Remove the progress notification first so the alarm is the only one up.
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager().notify(TripNotifications.ARRIVAL_ID, TripNotifications.arrival(this, trip, fullScreen))
        stopSelf()
        TripEvents.publish(TripSnapshot(TripStatus.ARRIVED, trip, lastDistance, 1.0, lastFix, speed, null, 0.0))
    }

    private fun track(next: LocationPlan) {
        plan = next
        watchLowPower(next)
        source.start(next, ::onLocation) { fallBackToGps("Low-accuracy location unavailable") }
    }

    private fun stopTracking() {
        source.stop()
        plan = null
        watchLowPower(null)
    }

    /**
     * Low-accuracy updates need network location, which may be off or have no
     * signal; then no fixes arrive. Every fix restarts this alarm, so a quiet
     * spell of one interval plus a grace period falls back to GPS. An alarm,
     * not a timer: timers stop while the phone sleeps, and with no fixes
     * nothing would wake it.
     */
    private fun watchLowPower(current: LocationPlan?) {
        val alarms = getSystemService(AlarmManager::class.java)
        val timeout = PendingIntent.getService(
            this, ACTION_LOW_POWER_TIMEOUT.hashCode(), actionIntent(this, ACTION_LOW_POWER_TIMEOUT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.cancel(timeout)
        if (current?.highAccuracy == false) {
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + current.intervalMillis + LOW_POWER_GRACE_MILLIS,
                timeout,
            )
        }
    }

    private fun fallBackToGps(reason: String) {
        val current = plan
        if (current == null) {
            // A late alarm that started a fresh service with no trip.
            if (store.active() == null) stop()
            return
        }
        if (current.highAccuracy) return
        Log.w(TAG, "$reason; switching to GPS")
        lowPowerFailed = true
        store.active()?.takeUnless { it.paused }?.let { track(planFor(it)) }
    }

    /** The receiver may report before the trip is stored, so check once it is. */
    private fun checkBattery() {
        val battery = getSystemService(BatteryManager::class.java) ?: return
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // Unsupported devices report Integer.MIN_VALUE, or 0 before Android 9.
        if (percent in 1..100) onBattery(BatteryLevel(percent, battery.isCharging))
    }

    private fun onBattery(level: BatteryLevel) {
        if (level.charging) notificationManager().cancel(TripNotifications.BATTERY_ID)
        val trip = store.active() ?: return
        val threshold = batteryWatch.update(level) ?: return
        Log.i(TAG, "Battery at ${level.percent}% (below $threshold%) during a trip")
        notificationManager().notify(TripNotifications.BATTERY_ID, TripNotifications.lowBattery(this, trip, level.percent))
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

    private fun planFor(trip: Trip) = TripMath.locationPlan(
        edgeMeters = lastDistance?.let { TripMath.edgeMeters(it, trip.radius, lastFix?.accuracyMeters) },
        fast = fast,
        lowPowerAllowed = !lowPowerFailed,
    )

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
        const val ACTION_LOW_POWER_TIMEOUT = "com.example.are_we_there_yet.trip.LOW_POWER_TIMEOUT"
        private const val EXTRA_ENABLED = "enabled"
        internal const val LOW_POWER_GRACE_MILLIS = 60_000L

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
