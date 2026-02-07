package com.example.are_we_there_yet

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity() {

    private val CHANNEL = "are_we_there/native"
    private val TAG = "MainActivity"

    private lateinit var geofenceManager: GeofenceManager
    private var pendingResult: MethodChannel.Result? = null
    private var pendingParams: GeofenceParams? = null

    // Stores location parsed from a share intent (one-shot, cleared after Flutter reads it)
    private var sharedLocation: IntentParser.ParsedLocation? = null
    private var sharedRawText: String? = null

    private data class GeofenceParams(
        val id: String, val lat: Double, val lng: Double, val radius: Double
    )

    companion object {
        private const val RC_FINE_LOCATION = 1001
        private const val RC_BACKGROUND_LOCATION = 1002
        private const val RC_LOCATION_SETTINGS = 1003
    }

    // ── MethodChannel wiring ───────────────────────────────────

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        geofenceManager = GeofenceManager(this)

        // Parse the initial launch intent (async — may resolve short links)
        CoroutineScope(Dispatchers.Main).launch {
            sharedLocation = IntentParser.parse(intent)
            if (sharedLocation != null) {
                sharedRawText = rawTextFromIntent(intent)
                Log.d(TAG, "Initial intent parsed: $sharedLocation")
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "sayHello" -> {
                        Toast.makeText(this, "Hello from Kotlin", Toast.LENGTH_SHORT).show()
                        result.success("Hello back from Android")
                    }
                    "getSharedLocation" -> handleGetSharedLocation(result)
                    "addGeofence" -> handleAddGeofence(call, result)
                    else -> result.notImplemented()
                }
            }
    }

    // Handle re-launch while app is already running (singleTop)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        CoroutineScope(Dispatchers.Main).launch {
            val parsed = IntentParser.parse(intent)
            if (parsed != null) {
                Log.d(TAG, "onNewIntent parsed: $parsed")
                sharedLocation = parsed
                sharedRawText = rawTextFromIntent(intent)
            }
        }
    }

    private fun rawTextFromIntent(i: Intent): String? = when (i.action) {
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> i.data?.toString()
        else -> null
    }

    // ── getSharedLocation handler (Flutter pulls on startup) ───

    private fun handleGetSharedLocation(result: MethodChannel.Result) {
        // Parse may still be in-flight for short links — run async and return result
        CoroutineScope(Dispatchers.Main).launch {
            // If sharedLocation is already set (fast path), return it
            val loc = sharedLocation
            if (loc != null) {
                val raw = sharedRawText
                result.success(mapOf(
                    "lat" to loc.lat,
                    "lng" to loc.lng,
                    "name" to loc.name,
                    "sharedLink" to raw
                ))
                sharedLocation = null
                sharedRawText = null
                return@launch
            }

            // If intent looks like a share but hasn't resolved yet, try parsing now
            val currentIntent = intent
            if (currentIntent.action == Intent.ACTION_SEND || currentIntent.action == Intent.ACTION_VIEW) {
                val parsed = IntentParser.parse(currentIntent)
                if (parsed != null) {
                    result.success(mapOf(
                        "lat" to parsed.lat,
                        "lng" to parsed.lng,
                        "name" to parsed.name,
                        "sharedLink" to rawTextFromIntent(currentIntent)
                    ))
                    return@launch
                }
            }

            result.success(null)
        }
    }

    // ── addGeofence handler ────────────────────────────────────

    private fun handleAddGeofence(call: MethodCall, result: MethodChannel.Result) {
        val id     = call.argument<String>("id")
        val lat    = call.argument<Double>("lat")
        val lng    = call.argument<Double>("lng")
        val radius = call.argument<Double>("radius")

        if (id == null || lat == null || lng == null || radius == null) {
            result.error("INVALID_ARGUMENT", "Missing id/lat/lng/radius", null)
            return
        }

        val params = GeofenceParams(id, lat, lng, radius)

        // 1. Fine + Coarse location (Android 12+ requires both in same request)
        if (!geofenceManager.hasLocationPermission()) {
            save(result, params)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                RC_FINE_LOCATION
            )
            return
        }

        // 2. Background location (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !geofenceManager.hasBackgroundLocationPermission()
        ) {
            save(result, params)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                RC_BACKGROUND_LOCATION
            )
            return
        }

        // 3. Location settings check, then register
        checkSettingsAndRegister(params, result)
    }

    // ── Permission results ─────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val result = pendingResult ?: return
        val params = pendingParams ?: return

        val granted = grantResults.isNotEmpty() &&
                      grantResults[0] == PackageManager.PERMISSION_GRANTED

        when (requestCode) {
            RC_FINE_LOCATION -> {
                if (!granted) {
                    finishWithPermissionError(Manifest.permission.ACCESS_FINE_LOCATION, result)
                    return
                }
                // Fine granted — now check background (Android 10+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !geofenceManager.hasBackgroundLocationPermission()
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        RC_BACKGROUND_LOCATION
                    )
                    return
                }
                checkSettingsAndRegister(params, result)
            }
            RC_BACKGROUND_LOCATION -> {
                if (!granted) {
                    finishWithPermissionError(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION, result
                    )
                    return
                }
                checkSettingsAndRegister(params, result)
            }
        }
    }

    private fun finishWithPermissionError(permission: String, result: MethodChannel.Result) {
        val code = if (shouldShowRequestPermissionRationale(permission))
            "PERMISSION_DENIED" else "PERMISSION_PERMANENTLY_DENIED"
        result.error(code, "Permission not granted: $permission", null)
        clear()
    }

    // ── Location Settings resolution ───────────────────────────

    private fun checkSettingsAndRegister(params: GeofenceParams, result: MethodChannel.Result) {
        val locRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build()

        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locRequest)
            .setAlwaysShow(true)
            .build()

        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener { registerGeofence(params, result) }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    save(result, params)
                    try {
                        e.startResolutionForResult(this, RC_LOCATION_SETTINGS)
                    } catch (sendEx: Exception) {
                        Log.e(TAG, "Settings resolution failed", sendEx)
                        result.error("LOCATION_SETTINGS_ERROR",
                            "Could not open location settings", null)
                        clear()
                    }
                } else {
                    result.error("LOCATION_DISABLED",
                        "Location services are disabled", null)
                    clear()
                }
            }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_LOCATION_SETTINGS) return

        val result = pendingResult ?: return
        val params = pendingParams ?: return

        if (resultCode == RESULT_OK) {
            registerGeofence(params, result)
        } else {
            result.error("LOCATION_DISABLED",
                "Location services were not enabled", null)
        }
        clear()
    }

    // ── Geofence registration (delegates to GeofenceManager) ──

    private fun registerGeofence(params: GeofenceParams, result: MethodChannel.Result) {
        val intent = Intent(this, GeofenceReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this,
            params.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        geofenceManager.addGeofence(
            params.id, params.lat, params.lng, params.radius.toFloat(), pi
        ).addOnSuccessListener {
            result.success("Geofence registered: ${params.id}")
            clear()
        }.addOnFailureListener { e ->
            val msg = if (e is ApiException) "Error ${e.statusCode}: ${e.message}" else e.message
            result.error("REGISTRATION_FAILED", msg, null)
            clear()
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun save(result: MethodChannel.Result, params: GeofenceParams) {
        pendingResult = result
        pendingParams = params
    }

    private fun clear() {
        pendingResult = null
        pendingParams = null
    }
}
