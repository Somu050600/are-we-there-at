package com.example.are_we_there_yet

import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.text.RegexOption

/**
 * Parses incoming share/view intents into coordinates.
 * Handles geo: URIs, Google Maps URLs (including short links), and plain-text lat/lng.
 */
object IntentParser {

    private val TAG = "IntentParser"

    data class ParsedLocation(val lat: Double, val lng: Double, val name: String?)

    /**
     * Attempts to extract a location from an Intent.
     * Suspend because short URLs may need network resolution.
     */
    suspend fun parse(intent: Intent): ParsedLocation? {
        return when (intent.action) {
            Intent.ACTION_VIEW -> parseViewIntent(intent)
            Intent.ACTION_SEND -> parseSendIntent(intent)
            else -> null
        }
    }

    // ── ACTION_VIEW  (geo: URIs) ───────────────────────────────

    private suspend fun parseViewIntent(intent: Intent): ParsedLocation? {
        val uri = intent.data ?: return null
        if (uri.scheme == "geo") return parseGeoUri(uri)
        return parseMapUrlDirect(uri.toString())
    }

    // ── ACTION_SEND  (shared text) ─────────────────────────────

    private suspend fun parseSendIntent(intent: Intent): ParsedLocation? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        Log.d(TAG, "Shared text: $text")

        // Try direct URL parsing first
        val directResult = parseMapUrlDirect(text)
        if (directResult != null) return directResult

        // Check if it contains a URL that needs network resolution
        val url = extractUrl(text)
        if (url != null) {
            val networkResult = resolveViaNetwork(url)
            if (networkResult != null) return networkResult
        }

        return parseLatLngFromText(text)
    }

    // Extract first URL from text
    private val URL_REGEX = Regex("""https?://\S+""")
    private fun extractUrl(text: String): String? = URL_REGEX.find(text)?.value

    /**
     * Resolves a URL via network: follows all redirects, then if the final
     * URL still has no coords, fetches the page and scrapes them from HTML.
     */
    private suspend fun resolveViaNetwork(url: String): ParsedLocation? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Resolving via network: $url")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true  // follow full chain
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connect()

                val finalUrl = conn.url.toString()
                Log.d(TAG, "Final URL: $finalUrl")

                val nameFromUrl = extractPlaceNameFromUrl(finalUrl)

                // 1. Try parsing the final URL for coords
                val fromUrl = parseMapUrlDirect(finalUrl)
                if (fromUrl != null) {
                    conn.disconnect()
                    val name = fromUrl.name ?: nameFromUrl
                    return@withContext ParsedLocation(fromUrl.lat, fromUrl.lng, name)
                }

                // 2. Read page body and search for coords in HTML/JS
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val fromBody = extractCoordsFromHtml(body)
                if (fromBody != null) {
                    val nameFromHtml = extractNameFromHtml(body)
                    val name = nameFromHtml ?: nameFromUrl
                    Log.d(TAG, "Extracted from page body: $fromBody, name: $name")
                    return@withContext ParsedLocation(fromBody.lat, fromBody.lng, name)
                }

                null
            } catch (e: Exception) {
                Log.e(TAG, "Network resolution failed: $url", e)
                null
            }
        }

    /** Extracts a short place name from a Google Maps URL path, e.g. /place/ABC+Restaurant/ */
    private fun extractPlaceNameFromUrl(url: String): String? {
        val placeMatch = Regex("""/place/([^/]+?)(?:/|@|$)""").find(url) ?: return null
        val encoded = placeMatch.groupValues[1]
        if (encoded.isBlank()) return null
        return try {
            var name = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                .replace('+', ' ')
                .trim()
            // Skip if it looks like coordinates or too short/long
            if (name.length < 2 || name.length > 120) return null
            if (Regex("""^-?\d+\.?\d*,\s*-?\d+\.?\d*$""").matches(name)) return null
            name
        } catch (_: Exception) {
            null
        }
    }

    /** Tries to get place name from page HTML (e.g. og:title). */
    private fun extractNameFromHtml(html: String): String? {
        val ogTitle = Regex("""<meta[^>]*property=["']og:title["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""content=["']([^"']+)["'][^>]*property=["']og:title["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        if (!ogTitle.isNullOrBlank()) {
            val cleaned = ogTitle.replace(Regex("""\s*[-–—]\s*Google\s+Maps\s*$""", RegexOption.IGNORE_CASE), "").trim()
            if (cleaned.length in 2..120) return cleaned
        }
        return null
    }

    // Patterns found in Google Maps page HTML/JS that contain coordinates
    private val HTML_COORD_PATTERNS = listOf(
        // window.APP_INITIALIZATION_STATE contains [null,null,lat,lng]
        Regex("""\[null,null,(-?\d+\.\d{4,8}),(-?\d+\.\d{4,8})\]"""),
        // meta og:image or other tags: center=lat%2Clng
        Regex("""center=(-?\d+\.\d{4,8})%2C(-?\d+\.\d{4,8})"""),
        // /@lat,lng in canonical or other embedded URLs
        Regex("""/@(-?\d+\.\d{4,8}),(-?\d+\.\d{4,8})"""),
        // [lat, lng] in JSON-like structures (common in Maps page data)
        Regex("""\[(-?\d+\.\d{5,8}),(-?\d+\.\d{5,8})\]"""),
    )

    private fun extractCoordsFromHtml(html: String): ParsedLocation? {
        for (pattern in HTML_COORD_PATTERNS) {
            val match = pattern.find(html)
            if (match != null) {
                val result = locOrNull(match.groupValues[1], match.groupValues[2], null)
                if (result != null) return result
            }
        }
        return null
    }

    // ── geo: URI parser ────────────────────────────────────────
    // Formats:
    //   geo:37.7749,-122.4194
    //   geo:0,0?q=37.7749,-122.4194(Label)
    //   geo:37.7749,-122.4194?z=15

    private fun parseGeoUri(uri: Uri): ParsedLocation? {
        val ssp = uri.schemeSpecificPart ?: return null  // everything after "geo:"

        // Check ?q= parameter first (may contain coords + label)
        val qParam = uri.getQueryParameter("q")
        if (qParam != null) {
            val qResult = parseQParam(qParam)
            if (qResult != null) return qResult
        }

        // Fall back to the lat,lng before the '?'
        val coordsPart = ssp.substringBefore('?')
        return parsePair(coordsPart)
    }

    // q=37.7749,-122.4194  or  q=37.7749,-122.4194(Place Name)
    private fun parseQParam(q: String): ParsedLocation? {
        val labelMatch = Regex("""^(-?\d+\.?\d*),\s*(-?\d+\.?\d*)\((.+)\)$""").find(q)
        if (labelMatch != null) {
            val (lat, lng, name) = labelMatch.destructured
            return locOrNull(lat, lng, name)
        }
        return parsePair(q)
    }

    // ── Google Maps / generic map URL parser ───────────────────

    private val MAP_PATTERNS = listOf(
        // @lat,lng,zoom
        Regex("""@(-?\d+\.?\d*),(-?\d+\.?\d*)"""),
        // ?q=lat,lng  or &q=lat,lng
        Regex("""[?&]q=(-?\d+\.?\d*),(-?\d+\.?\d*)"""),
        // /place/lat,lng
        Regex("""/place/[^/]*/@(-?\d+\.?\d*),(-?\d+\.?\d*)"""),
        // ll=lat,lng
        Regex("""ll=(-?\d+\.?\d*),(-?\d+\.?\d*)"""),
        // query=lat,lng
        Regex("""query=(-?\d+\.?\d*),(-?\d+\.?\d*)"""),
    )

    private fun parseMapUrlDirect(text: String): ParsedLocation? {
        for (pattern in MAP_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val result = locOrNull(match.groupValues[1], match.groupValues[2], null)
                if (result != null) {
                    Log.d(TAG, "Matched pattern: $pattern -> $result")
                    return result
                }
            }
        }
        return null
    }

    // ── Fallback: plain-text lat/lng extraction ────────────────

    // Matches patterns like "37.7749, -122.4194" anywhere in text
    private val LATLNG_REGEX = Regex("""(-?\d{1,3}\.\d{3,8}),\s*(-?\d{1,3}\.\d{3,8})""")

    private fun parseLatLngFromText(text: String): ParsedLocation? {
        val match = LATLNG_REGEX.find(text) ?: return null
        return locOrNull(match.groupValues[1], match.groupValues[2], null)
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun parsePair(s: String): ParsedLocation? {
        val parts = s.split(",")
        if (parts.size < 2) return null
        return locOrNull(parts[0].trim(), parts[1].trim(), null)
    }

    private fun locOrNull(latStr: String, lngStr: String, name: String?): ParsedLocation? {
        val lat = latStr.toDoubleOrNull() ?: return null
        val lng = lngStr.toDoubleOrNull() ?: return null
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null
        return ParsedLocation(lat, lng, name?.takeIf { it.isNotBlank() })
    }
}
