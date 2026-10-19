package com.example.are_we_there_yet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity() {
    private lateinit var channel: MethodChannel
    private lateinit var tripEvents: EventChannel
    private lateinit var headingEvents: EventChannel
    private var heading: HeadingSensor? = null
    private lateinit var store: TripStore
    private val inbox = SharedLocationInbox()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val openResults = mutableSetOf<ChannelResult>()
    private var activeOperation: ActiveOperation? = null
    private var destroyed = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        store = TripStore(applicationContext)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(::onMethodCall)
        headingEvents = EventChannel(flutterEngine.dartExecutor.binaryMessenger, HEADING_CHANNEL)
        headingEvents.setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    val sensor = HeadingSensor(this@MainActivity) { displayRotation() }
                    if (!sensor.start { events.success(it) }) {
                        events.error("NO_COMPASS", "This device has no compass", null)
                        return
                    }
                    heading = sensor
                }

                override fun onCancel(arguments: Any?) {
                    heading?.stop()
                    heading = null
                }
            },
        )
        tripEvents = EventChannel(flutterEngine.dartExecutor.binaryMessenger, TRIP_CHANNEL)
        tripEvents.setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    TripEvents.listener = { snapshot -> runOnUiThread { events.success(snapshot.toMap()) } }
                    events.success(currentTrip().toMap())
                }

                override fun onCancel(arguments: Any?) {
                    TripEvents.listener = null
                }
            },
        )
        SharedLocationInbox.fromIntent(intent)?.let(inbox::add)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val share = SharedLocationInbox.fromIntent(intent) ?: return
        inbox.add(share)
        if (::channel.isInitialized) channel.invokeMethod("sharedLocationAvailable", null)
    }

    override fun onDestroy() {
        destroyed = true
        activeOperation?.let { operation ->
            operation.result.error("ACTIVITY_DESTROYED", "The Android activity was closed", null)
            // A mutation already handed to Play services keeps the queue until its
            // callback runs, so a later operation cannot overlap its store writes.
            if (!operation.executing) operation.done()
        }
        activeOperation = null
        synchronized(openResults) {
            openResults.toList().forEach {
                it.error("ACTIVITY_DESTROYED", "The Android activity was closed", null)
            }
        }
        scope.cancel()
        TripEvents.listener = null
        heading?.stop()
        heading = null
        if (::headingEvents.isInitialized) headingEvents.setStreamHandler(null)
        if (::tripEvents.isInitialized) tripEvents.setStreamHandler(null)
        if (::channel.isInitialized) channel.setMethodCallHandler(null)
        super.onDestroy()
    }

    private fun onMethodCall(call: MethodCall, rawResult: MethodChannel.Result) {
        when (call.method) {
            "getSharedLocation" -> getSharedLocation(rawResult)
            "startTrip" -> parseOrError(rawResult) { result ->
                val trip = NativeArguments.trip(call.arguments)
                enqueueMutation(result, requiresLocation = true) { done ->
                    ContextCompat.startForegroundService(this, TripService.startIntent(this, trip))
                    done(Result.success(Unit))
                }
            }
            "pauseTrip" -> tripAction(rawResult, TripService.ACTION_PAUSE)
            "resumeTrip" -> parseOrError(rawResult) { result ->
                enqueueMutation(result, requiresLocation = true) { done ->
                    ContextCompat.startForegroundService(this, TripService.actionIntent(this, TripService.ACTION_RESUME))
                    done(Result.success(Unit))
                }
            }
            "stopTrip" -> tripAction(rawResult, TripService.ACTION_STOP)
            "getTripState" -> track(rawResult).success(currentTrip().toMap())
            "removeLegacyGeofences" -> {
                val result = track(rawResult)
                LegacyGeofences.remove(applicationContext) { outcome ->
                    runOnUiThread {
                        outcome.exceptionOrNull()?.let {
                            result.error("CLEANUP_FAILED", it.message ?: "Could not remove old alarms", null)
                        } ?: result.success(null)
                    }
                }
            }
            "getAlarmSettings" -> track(rawResult).success(alarmSettings())
            "setFullScreenAlarm" -> parseOrError(rawResult) { result ->
                store.fullScreenEnabled = NativeArguments.enabled(call.arguments)
                result.success(alarmSettings())
            }
            "openFullScreenSettings" -> openFullScreenSettings(rawResult)
            "setFastUpdates" -> parseOrError(rawResult) { result ->
                val enabled = NativeArguments.enabled(call.arguments)
                // Only a running trip changes cadence; never start the service for this.
                if (TripService.isRunning) startService(TripService.fastIntent(this, enabled))
                result.success(null)
            }
            "openInMaps" -> parseOrError(rawResult) { result ->
                openInMaps(NativeArguments.place(call.arguments), result)
            }
            "openSettings" -> openAppSettings(rawResult)
            else -> rawResult.notImplemented()
        }
    }

    /** Pause and stop only touch an already running trip, so they need no permission checks. */
    private fun tripAction(rawResult: MethodChannel.Result, action: String) = parseOrError(rawResult) { result ->
        if (store.active() == null) {
            TripEvents.publish(TripSnapshot.IDLE)
        } else {
            startService(TripService.actionIntent(this, action))
        }
        result.success(null)
    }

    /**
     * The service's last report. A stored trip whose service is gone (force-stop,
     * update, reboot) is resumed: the app is in the foreground, so Android allows it.
     */
    private fun currentTrip(): TripSnapshot {
        val stored = store.active()
        if (stored != null && !TripService.isRunning) {
            if (!hasFineLocationPermission()) {
                store.clearActive()
                return TripSnapshot.IDLE
            }
            ContextCompat.startForegroundService(this, TripService.actionIntent(this, TripService.ACTION_RECOVER))
            return TripSnapshot(if (stored.paused) TripStatus.PAUSED else TripStatus.TRACKING, stored)
        }
        val last = TripEvents.last
        if (last.status != TripStatus.IDLE || stored == null) return last
        return TripSnapshot(if (stored.paused) TripStatus.PAUSED else TripStatus.TRACKING, stored)
    }

    private fun alarmSettings() = mapOf(
        "fullScreen" to store.fullScreenEnabled,
        "fullScreenAllowed" to TripNotifications.canUseFullScreen(this),
    )

    private fun openFullScreenSettings(rawResult: MethodChannel.Result) {
        val result = track(rawResult)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", packageName, null))
        } else {
            appDetailsIntent()
        }
        try {
            startActivity(intent)
            result.success(null)
        } catch (failure: Throwable) {
            result.error("SETTINGS_UNAVAILABLE", failure.message ?: "Could not open Android settings", null)
        }
    }

    private fun openInMaps(place: Place, result: ChannelResult) {
        val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${place.lat},${place.lng}"))
        val label = Uri.encode(place.name)
        val anyMap = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${place.lat},${place.lng}?q=${place.lat},${place.lng}($label)"))
        for (intent in listOf(navigation, anyMap)) {
            try {
                startActivity(intent)
                result.success(null)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Try the next option.
            }
        }
        result.error("NO_MAPS_APP", "No maps app is installed", null)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else windowManager.defaultDisplay.rotation

    private fun hasFineLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled() =
        LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))

    private fun parseOrError(rawResult: MethodChannel.Result, block: (ChannelResult) -> Unit) {
        val result = track(rawResult)
        try {
            block(result)
        } catch (failure: InvalidArgumentsException) {
            result.error("INVALID_ARGUMENT", failure.message, null)
        } catch (failure: Throwable) {
            result.error("NATIVE_ERROR", failure.message ?: "Android operation failed", null)
        }
    }

    private fun getSharedLocation(rawResult: MethodChannel.Result) {
        val result = track(rawResult)
        val share = inbox.take()
        if (share == null) {
            result.success(null)
            return
        }
        scope.launch {
            try {
                val location = IntentParser.parseText(
                    share.text,
                    SafeGoogleMapsRedirectResolver,
                    AndroidPlaceGeocoder(this@MainActivity),
                )
                if (location == null) {
                    result.error(
                        "INVALID_SHARE",
                        "Couldn't find where this place is. Check your connection, or share a dropped pin instead.",
                        null,
                    )
                } else {
                    result.success(
                        mapOf(
                            "lat" to location.lat,
                            "lng" to location.lng,
                            "name" to location.name,
                            "sharedLink" to share.sharedLink,
                            "approximate" to location.approximate,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                if (!destroyed) result.error("CANCELLED", "Share parsing was cancelled", null)
            } catch (failure: Throwable) {
                result.error("INVALID_SHARE", failure.message ?: "The shared location could not be read", null)
            }
        }
    }

    private fun enqueueMutation(
        result: ChannelResult,
        requiresLocation: Boolean,
        successValue: Any? = null,
        mutation: ((Result<Unit>) -> Unit) -> Unit,
    ) {
        NativeOperationQueue.shared.submit(
            onCancelled = { result.error("CANCELLED", "Android operation was cancelled", null) },
            onFailure = { failure ->
                activeOperation = null
                result.error("NATIVE_OPERATION_FAILED", failure.message ?: "Android operation failed", null)
            },
        ) { done ->
            if (destroyed) {
                result.error("ACTIVITY_DESTROYED", "The Android activity was closed", null)
                done()
                return@submit
            }
            val operation = ActiveOperation(result, done, successValue, mutation)
            activeOperation = operation
            if (requiresLocation) continuePrerequisites(operation) else execute(operation)
        }
    }

    private fun continuePrerequisites(operation: ActiveOperation) {
        if (destroyed || activeOperation !== operation) return
        if (!hasFineLocationPermission()) {
            requestPermissions(
                operation,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                RC_FINE_LOCATION,
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(operation, arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFICATIONS)
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            if (!operation.notificationSettingsOpened) {
                operation.notificationSettingsOpened = true
                launchSettings(
                    operation,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    RC_NOTIFICATION_SETTINGS,
                )
            } else {
                fail(operation, "NOTIFICATIONS_DISABLED", "Enable notifications to receive arrival alarms")
            }
            return
        }
        if (!isLocationEnabled()) {
            if (!operation.locationSettingsOpened) {
                operation.locationSettingsOpened = true
                launchSettings(operation, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), RC_LOCATION_SETTINGS)
            } else {
                fail(operation, "LOCATION_DISABLED", "Enable location services to use arrival alarms")
            }
            return
        }
        execute(operation)
    }

    private fun requestPermissions(operation: ActiveOperation, permissions: Array<String>, requestCode: Int) {
        try {
            ActivityCompat.requestPermissions(this, permissions, requestCode)
        } catch (failure: Throwable) {
            fail(operation, "PERMISSION_REQUEST_FAILED", failure.message ?: "Could not request permission")
        }
    }

    @Suppress("DEPRECATION")
    private fun launchSettings(operation: ActiveOperation, intent: Intent, requestCode: Int) {
        try {
            startActivityForResult(intent, requestCode)
        } catch (failure: Throwable) {
            fail(operation, "SETTINGS_UNAVAILABLE", failure.message ?: "Could not open Android settings")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val operation = activeOperation ?: return
        when (requestCode) {
            RC_FINE_LOCATION -> {
                if (!hasFineLocationPermission()) {
                    permissionError(operation, Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    continuePrerequisites(operation)
                }
            }
            RC_NOTIFICATIONS -> {
                val granted = Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) permissionError(operation, Manifest.permission.POST_NOTIFICATIONS)
                else continuePrerequisites(operation)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in SETTINGS_REQUEST_CODES) return
        val operation = activeOperation ?: return
        if (resultCode == Activity.RESULT_CANCELED || resultCode == Activity.RESULT_OK) {
            continuePrerequisites(operation)
        } else {
            continuePrerequisites(operation)
        }
    }

    private fun execute(operation: ActiveOperation) {
        if (activeOperation !== operation || destroyed) return
        operation.executing = true
        try {
            operation.mutation { outcome ->
                val failure = outcome.exceptionOrNull()
                if (failure == null) {
                    operation.result.success(operation.successValue)
                } else {
                    operation.result.error(
                        "NATIVE_OPERATION_FAILED",
                        failure.message ?: "Android operation failed",
                        failure.javaClass.name,
                    )
                }
                finish(operation)
            }
        } catch (failure: Throwable) {
            operation.result.error("NATIVE_OPERATION_FAILED", failure.message ?: "Android operation failed", null)
            finish(operation)
        }
    }

    private fun permissionError(operation: ActiveOperation, permission: String) {
        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        val code = if (canAskAgain) "PERMISSION_DENIED" else "PERMISSION_PERMANENTLY_DENIED"
        // Shown to the user as-is by the configuration screen.
        val needed = when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "Allow precise location to track your trip."
            Manifest.permission.POST_NOTIFICATIONS -> "Allow notifications so the arrival alarm can alert you."
            else -> "A required permission was not granted."
        }
        fail(operation, code, if (canAskAgain) needed else "$needed You can change this in Android Settings.")
    }

    private fun fail(operation: ActiveOperation, code: String, message: String) {
        operation.result.error(code, message, null)
        finish(operation)
    }

    private fun finish(operation: ActiveOperation) {
        if (activeOperation === operation) activeOperation = null
        operation.done()
    }

    private fun openAppSettings(rawResult: MethodChannel.Result) {
        val result = track(rawResult)
        try {
            startActivity(appDetailsIntent())
            result.success(null)
        } catch (failure: Throwable) {
            result.error("SETTINGS_UNAVAILABLE", failure.message ?: "Could not open Android settings", null)
        }
    }

    private fun appDetailsIntent() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )

    private fun track(rawResult: MethodChannel.Result): ChannelResult =
        ChannelResult(rawResult).also { synchronized(openResults) { openResults += it } }

    private inner class ChannelResult(private val delegate: MethodChannel.Result) {
        private var complete = false

        @Synchronized fun success(value: Any?) = settle { delegate.success(value) }

        @Synchronized fun error(code: String, message: String?, details: Any?) =
            settle { delegate.error(code, message, details) }

        private fun settle(action: () -> Unit) {
            if (complete) return
            complete = true
            synchronized(openResults) { openResults -= this }
            action()
        }
    }

    private data class ActiveOperation(
        val result: ChannelResult,
        val done: () -> Unit,
        val successValue: Any?,
        val mutation: ((Result<Unit>) -> Unit) -> Unit,
        var notificationSettingsOpened: Boolean = false,
        var locationSettingsOpened: Boolean = false,
        var executing: Boolean = false,
    )

    companion object {
        private const val CHANNEL = "are_we_there/native"
        private const val TRIP_CHANNEL = "are_we_there/trip"
        private const val HEADING_CHANNEL = "are_we_there/heading"
        private const val RC_FINE_LOCATION = 1001
        private const val RC_NOTIFICATIONS = 1003
        private const val RC_NOTIFICATION_SETTINGS = 1005
        private const val RC_LOCATION_SETTINGS = 1006
        private val SETTINGS_REQUEST_CODES = setOf(
            RC_NOTIFICATION_SETTINGS,
            RC_LOCATION_SETTINGS,
        )
    }
}
