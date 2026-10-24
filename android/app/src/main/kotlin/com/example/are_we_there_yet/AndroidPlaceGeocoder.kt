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
        val address = search(query, 1).firstOrNull() ?: return null
        return IntentParser.ParsedLocation(address.lat, address.lng)
    }

    /** Up to [max] places for [query]; empty when there are none or the geocoder fails. */
    suspend fun search(query: String, max: Int): List<Found> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context)
        val addresses = withTimeoutOrNull(TIMEOUT_MILLIS) { matches(geocoder, query, max) } ?: return emptyList()
        return addresses.filter { it.hasLatitude() && it.hasLongitude() }.map { address ->
            val line = address.getAddressLine(0)
            // featureName is often just a house number; then the address's first part names it.
            val name = address.featureName?.takeIf { it.any(Char::isLetter) }
                ?: line?.substringBefore(',')?.trim()?.takeIf(String::isNotEmpty)
                ?: query
            Found(name, line?.takeIf { it != name }, address.latitude, address.longitude)
        }
    }

    data class Found(val name: String, val address: String?, val lat: Double, val lng: Double)

    private suspend fun matches(geocoder: Geocoder, query: String, max: Int): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(query, max, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        continuation.resume(emptyList())
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(query, max) }.getOrNull().orEmpty()
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 8_000L
    }
}
