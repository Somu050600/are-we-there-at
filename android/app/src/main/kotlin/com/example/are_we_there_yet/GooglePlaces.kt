package com.example.are_we_there_yet

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Finds the place a shared Maps link names; null when it can't ask (no key, offline, error). */
fun interface PlaceFinder {
    /** [cid] is the place's Maps id from the link, when it had one. */
    suspend fun find(query: String, cid: String?): IntentParser.ParsedLocation?

    companion object {
        val None = PlaceFinder { _, _ -> null }
    }
}

/**
 * The Google Maps app shares a named place as a link with its name, address and Maps id but no
 * coordinates. Places Text Search finds candidates by name and address; the one whose Maps link
 * carries the same id is exactly the shared place. Without a match the top result is used and
 * marked approximate.
 */
object GooglePlaces {
    private const val TAG = "GooglePlaces"
    private const val ENDPOINT = "https://places.googleapis.com/v1/places:searchText"
    private const val TIMEOUT_MILLIS = 6_000

    /** "0x3a4cf32101cd9b9b:0xf4638626f70b8787" in a link's data or ftid parameter. */
    private val featureId = Regex("""0x[0-9a-fA-F]{1,16}:0x([0-9a-fA-F]{1,16})""")
    private val cidParameter = Regex("""[?&]cid=(\d+)""")

    /** The decimal Maps id (CID) from a link's feature id: its second half, unsigned. */
    fun cid(url: String): String? = featureId.findAll(url).lastOrNull()?.let { match ->
        match.groupValues[1].toULongOrNull(16)?.toString()
    }

    /** The exact place from a Text Search response, else its top result marked approximate. */
    fun pick(response: String, cid: String?): IntentParser.ParsedLocation? {
        val places = runCatching { JSONObject(response).optJSONArray("places") }.getOrNull() ?: return null
        val candidates = (0 until places.length()).mapNotNull { index ->
            val place = places.optJSONObject(index) ?: return@mapNotNull null
            val location = place.optJSONObject("location") ?: return@mapNotNull null
            if (!location.has("latitude") || !location.has("longitude")) return@mapNotNull null
            val placeCid = cidParameter.find(place.optString("googleMapsUri"))?.groupValues?.get(1)
            placeCid to IntentParser.ParsedLocation(location.getDouble("latitude"), location.getDouble("longitude"))
        }
        val exact = cid?.let { wanted -> candidates.firstOrNull { it.first == wanted } }
        return exact?.second ?: candidates.firstOrNull()?.second?.copy(approximate = true)
    }

    /** A finder for this build's key, or [PlaceFinder.None] when the build has none. */
    fun finder(context: Context): PlaceFinder {
        val key = BuildConfig.PLACES_API_KEY.takeIf(String::isNotBlank) ?: return PlaceFinder.None
        val app = context.applicationContext
        val certificate = signingCertificateSha1(app)
        return PlaceFinder { query, cid ->
            withContext(Dispatchers.IO) {
                search(key, app.packageName, certificate, query)?.let { pick(it, cid) }
            }
        }
    }

    private fun search(key: String, packageName: String, certificate: String?, query: String): String? {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Goog-Api-Key", key)
            // Location and Maps link only: the cheapest fields that answer the question.
            connection.setRequestProperty("X-Goog-FieldMask", "places.location,places.googleMapsUri")
            // The key only works from this app; Google checks these against its restriction.
            connection.setRequestProperty("X-Android-Package", packageName)
            certificate?.let { connection.setRequestProperty("X-Android-Cert", it) }
            val body = JSONObject().put("textQuery", query).put("pageSize", 20).toString()
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Text Search failed ($code): ${error?.take(300)}")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (failure: Exception) {
            Log.w(TAG, "Text Search failed", failure)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** SHA-1 of the app's signing certificate, as the Android key restriction expects it. */
    @Suppress("DEPRECATION")
    private fun signingCertificateSha1(context: Context): String? = runCatching {
        val manager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.toByteArray()?.let { certificate ->
            MessageDigest.getInstance("SHA-1").digest(certificate).joinToString("") { "%02X".format(it) }
        }
    }.getOrNull()
}
