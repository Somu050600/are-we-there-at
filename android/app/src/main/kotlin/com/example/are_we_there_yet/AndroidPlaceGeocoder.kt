package com.example.are_we_there_yet

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Looks places up with the platform geocoder, which needs no API key. */
class AndroidPlaceGeocoder(context: Context) : PlaceGeocoder {
    private val context = context.applicationContext

    override suspend fun locate(query: String): IntentParser.ParsedLocation? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context)
        val address = withTimeoutOrNull(TIMEOUT_MILLIS) { firstMatch(geocoder, query) } ?: return null
        if (!address.hasLatitude() || !address.hasLongitude()) return null
        return IntentParser.ParsedLocation(address.latitude, address.longitude)
    }

    private suspend fun firstMatch(geocoder: Geocoder, query: String): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        continuation.resume(null)
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 8_000L
    }
}
