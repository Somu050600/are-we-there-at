package com.example.are_we_there_yet

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Finds the place a shared Maps link names; null when it can't ask (no key, offline, error). */
fun interface PlaceFinder {
    /** [feature] is the place's Maps feature id from the link ("0x…:0x…"), when it had one. */
    suspend fun find(query: String, feature: String?): IntentParser.ParsedLocation?

    companion object {
        val None = PlaceFinder { _, _ -> null }
    }
}

/** Google says the shared place doesn't exist, and the user turned off searching for it. */
class PlaceNotFoundException : Exception("Couldn't find this place on Google Maps")

/** A place Autocomplete suggests: shown by name and address, located once picked. */
data class PlaceSuggestion(val placeId: String, val name: String, val address: String?)

/**
 * The Google Maps app shares a named place as a link with its name, address and Maps feature id
 * but no coordinates. The feature id is what a place id encodes, so one Place Details request for
 * the location alone (Essentials, the cheapest tier) finds exactly the shared place. When Google
 * doesn't know that id, Text Search by name and address finds candidates and the one with the
 * same Maps id (CID) is used; without a match the top result is used, marked approximate.
 */
object GooglePlaces {
    private const val TAG = "GooglePlaces"
    private const val API = "https://places.googleapis.com/v1"
    private const val TIMEOUT_MILLIS = 8_000
    private const val SUGGESTION_BIAS_METERS = 50_000.0

    /** "0x3a4cf32101cd9b9b:0xf4638626f70b8787" in a link's data or ftid parameter. */
    private val featureId = Regex("""0x([0-9a-fA-F]{1,16}):0x([0-9a-fA-F]{1,16})""")
    private val cidParameter = Regex("""[?&]cid=(\d+)""")

    /** The last Maps feature id in [text], in lower case. */
    fun feature(text: String): String? = featureId.findAll(text).lastOrNull()?.value?.lowercase()

    /** The decimal Maps id (CID) from a feature id in [text]: its second half, unsigned. */
    fun cid(text: String): String? = featureId.findAll(text).lastOrNull()?.let { match ->
        match.groupValues[2].toULongOrNull(16)?.toString()
    }

    /**
     * The place id for a feature id. A "ChIJ…" place id is URL-safe base64 of a small protobuf
     * holding the feature id's two halves as fixed64 fields 1 and 2.
     */
    fun placeId(feature: String): String? {
        val match = featureId.find(feature) ?: return null
        val high = match.groupValues[1].toULongOrNull(16) ?: return null
        val low = match.groupValues[2].toULongOrNull(16) ?: return null
        val bytes = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x0a.toByte()).put(0x12.toByte())
            .put(0x09.toByte()).putLong(high.toLong())
            .put(0x11.toByte()).putLong(low.toLong())
            .array()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** The location in a Place Details response. */
    fun location(response: String): IntentParser.ParsedLocation? {
        val location = runCatching { JSONObject(response).optJSONObject("location") }.getOrNull() ?: return null
        if (!location.has("latitude") || !location.has("longitude")) return null
        return IntentParser.ParsedLocation(location.getDouble("latitude"), location.getDouble("longitude"))
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

    /** The place suggestions in an Autocomplete response; query suggestions are skipped. */
    fun suggestions(response: String): List<PlaceSuggestion> {
        val items = runCatching { JSONObject(response).optJSONArray("suggestions") }.getOrNull() ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val prediction = items.optJSONObject(index)?.optJSONObject("placePrediction") ?: return@mapNotNull null
            val placeId = prediction.optString("placeId").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val format = prediction.optJSONObject("structuredFormat")
            val name = format?.optJSONObject("mainText")?.optString("text")?.takeIf(String::isNotEmpty)
                ?: prediction.optJSONObject("text")?.optString("text")?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val address = format?.optJSONObject("secondaryText")?.optString("text")?.takeIf(String::isNotEmpty)
            PlaceSuggestion(placeId, name, address)
        }
    }

    /** Whether this build has a Places key. */
    val available: Boolean get() = BuildConfig.PLACES_API_KEY.isNotBlank()

    /**
     * A finder for this build's key, or [PlaceFinder.None] when the build has none. With
     * [searchFallback] off, a place Google doesn't know by its id is reported, not searched for.
     */
    fun finder(context: Context, cache: PlaceCache, searchFallback: Boolean): PlaceFinder =
        client(context)?.let { finder(it, cache, searchFallback) } ?: PlaceFinder.None

    internal fun finder(client: Lookup, cache: PlaceCache, searchFallback: Boolean): PlaceFinder =
        PlaceFinder { query, feature ->
            withContext(Dispatchers.IO) {
                val cid = feature?.let(::cid)
                cid?.let(cache::get)?.let { return@withContext it }
                val details = feature?.let(::placeId)?.let { id -> client.details(id, null) }
                if (details is Details.Found) {
                    cid?.let { cache.put(it, details.location) }
                    return@withContext details.location
                }
                if (!searchFallback) {
                    if (details is Details.Missing) throw PlaceNotFoundException()
                    return@withContext null
                }
                client.search(query)?.let { pick(it, cid) }?.also { found ->
                    if (cid != null && !found.approximate) cache.put(cid, found)
                }
            }
        }

    /** Places for the Add place screen, or null when this build has no key. */
    fun client(context: Context): Client? {
        val key = BuildConfig.PLACES_API_KEY.takeIf(String::isNotBlank) ?: return null
        val app = context.applicationContext
        return Client(key, app.packageName, signingCertificateSha1(app))
    }

    sealed interface Details {
        data class Found(val location: IntentParser.ParsedLocation) : Details
        /** Google doesn't know the place id. */
        data object Missing : Details
        /** Any other refusal: the key, quota or Google itself. */
        data object Failed : Details
    }

    /** The two requests a share can need. */
    interface Lookup {
        fun details(placeId: String, session: String?): Details
        fun search(query: String): String?
    }

    class Client internal constructor(
        private val key: String,
        private val packageName: String,
        private val certificate: String?,
    ) : Lookup {
        /**
         * The location of [placeId], the only field asked for (Place Details Essentials).
         * [session] ends an Autocomplete session, which makes its suggestions free.
         * Throws [ShareUnreachableException] when Google can't be reached.
         */
        override fun details(placeId: String, session: String?): Details {
            val url = buildString {
                append("$API/places/").append(Uri.encode(placeId))
                session?.let { append("?sessionToken=").append(Uri.encode(it)) }
            }
            return try {
                WebConnections.request(URL(url)) { connection ->
                    prepare(connection, "location")
                    connection.requestMethod = "GET"
                    when (val code = connection.responseCode) {
                        in 200..299 -> location(connection.inputStream.bufferedReader().use { it.readText() })
                            ?.let(Details::Found) ?: Details.Failed
                        400, 404 -> Details.Missing
                        else -> {
                            logError(connection, "Place Details", code)
                            Details.Failed
                        }
                    }
                }
            } catch (offline: IOException) {
                throw ShareUnreachableException(offline)
            }
        }

        /** Suggestions for [input], biased to [near] when known. Throws when Google can't be reached. */
        fun autocomplete(input: String, session: String, near: Pair<Double, Double>?): List<PlaceSuggestion> {
            val body = JSONObject().put("input", input).put("sessionToken", session)
            near?.let { (lat, lng) ->
                body.put(
                    "locationBias",
                    JSONObject().put(
                        "circle",
                        JSONObject()
                            .put("center", JSONObject().put("latitude", lat).put("longitude", lng))
                            .put("radius", SUGGESTION_BIAS_METERS),
                    ),
                )
            }
            val response = try {
                post("$API/places:autocomplete", body, FIELDS_SUGGESTIONS)
            } catch (offline: IOException) {
                throw ShareUnreachableException(offline)
            }
            return response?.let(::suggestions) ?: throw IllegalStateException("Google search failed")
        }

        /** Text Search (Pro, for the location and Maps link) by name and address; null on any error. */
        override fun search(query: String): String? = try {
            post(
                "$API/places:searchText",
                JSONObject().put("textQuery", query).put("pageSize", 20),
                "places.location,places.googleMapsUri",
            )
        } catch (failure: Exception) {
            Log.w(TAG, "Text Search failed", failure)
            null
        }

        private fun post(url: String, body: JSONObject, fields: String): String? =
            WebConnections.request(URL(url)) { connection ->
                prepare(connection, fields)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    logError(connection, url.substringAfterLast('/'), code)
                    null
                }
            }

        private fun prepare(connection: HttpURLConnection, fields: String) {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("X-Goog-Api-Key", key)
            // Only the fields used: the field mask decides the price.
            connection.setRequestProperty("X-Goog-FieldMask", fields)
            // The key only works from this app; Google checks these against its restriction.
            connection.setRequestProperty("X-Android-Package", packageName)
            certificate?.let { connection.setRequestProperty("X-Android-Cert", it) }
        }

        private fun logError(connection: HttpURLConnection, request: String, code: Int) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
            Log.w(TAG, "$request failed ($code): ${error?.take(300)}")
        }
    }

    private const val FIELDS_SUGGESTIONS =
        "suggestions.placePrediction.placeId,suggestions.placePrediction.text,suggestions.placePrediction.structuredFormat"

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
