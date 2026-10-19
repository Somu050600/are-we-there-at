package com.example.are_we_there_yet

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

fun interface RedirectResolver {
    suspend fun resolve(url: String): String?
}

/** Parses only explicit coordinates. It never scrapes arbitrary HTML for coordinate-shaped arrays. */
object IntentParser {
    data class ParsedLocation(val lat: Double, val lng: Double, val name: String? = null)

    private val pair = Regex("""(?<![\d.])([+-]?(?:\d+(?:\.\d+)?|\.\d+))\s*,\s*([+-]?(?:\d+(?:\.\d+)?|\.\d+))(?![\d.])""")
    private val destination = Regex("""!3d([+-]?(?:\d+(?:\.\d+)?|\.\d+))!4d([+-]?(?:\d+(?:\.\d+)?|\.\d+))""", RegexOption.IGNORE_CASE)
    private val destinationLngFirst = Regex("""!2d([+-]?(?:\d+(?:\.\d+)?|\.\d+))!3d([+-]?(?:\d+(?:\.\d+)?|\.\d+))""", RegexOption.IGNORE_CASE)
    private val destinationMarker = Regex("""!(?:3d[^!/?&#]+!4d|2d[^!/?&#]+!3d)[^!/?&#]+""", RegexOption.IGNORE_CASE)
    private val viewport = Regex("""@([+-]?(?:\d+(?:\.\d+)?|\.\d+)),([+-]?(?:\d+(?:\.\d+)?|\.\d+))""")
    private val webUrl = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
    private val geoLine = Regex("""(?im)\bgeo:[^\r\n]+""")

    suspend fun parse(
        intent: Intent,
        resolver: RedirectResolver = SafeGoogleMapsRedirectResolver,
    ): ParsedLocation? {
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.toString()
            else -> null
        } ?: return null
        return parseText(text, resolver)
    }

    internal suspend fun parseText(text: String, resolver: RedirectResolver): ParsedLocation? {
        geoLine.find(text)?.value?.let { return parseGeo(it) }

        var textWithoutUrls = text
        for (match in webUrl.findAll(text)) {
            val url = trimUrlPunctuation(match.value)
            parseMapUrl(url)?.let { return it }
            if (SafeGoogleMapsRedirectResolver.canResolve(url)) {
                resolver.resolve(url)?.let(::parseMapUrl)?.let { return it }
            }
            textWithoutUrls = textWithoutUrls.replaceRange(match.range, " ".repeat(match.value.length))
        }

        return pair.find(textWithoutUrls)?.let { location(it.groupValues[1], it.groupValues[2], null) }
    }

    private fun parseGeo(text: String): ParsedLocation? {
        val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("geo", ignoreCase = true)) return null
        val schemeSpecificPart = uri.encodedSchemeSpecificPart ?: return null
        val encodedQuery = schemeSpecificPart.substringAfter('?', missingDelimiterValue = "")
        val hasQuery = '?' in schemeSpecificPart
        val q = encodedQuery.split('&').firstNotNullOfOrNull { parameter ->
            val key = parameter.substringBefore('=', missingDelimiterValue = parameter)
            if (Uri.decode(key).equals("q", ignoreCase = true)) {
                parameter.substringAfter('=', missingDelimiterValue = "")
            } else {
                null
            }
        }
        if (q != null) {
            val decoded = Uri.decode(q).replace('+', ' ')
            val match = pair.find(decoded)
            if (match != null) {
                val remainder = decoded.substring(match.range.last + 1).trim()
                val label = remainder.removePrefix("(").removeSuffix(")").trim().takeIf {
                    remainder.startsWith('(') && remainder.endsWith(')') && it.isNotEmpty()
                }
                location(match.groupValues[1], match.groupValues[2], label)?.let { return it }
            }
            return null
        }
        if (hasQuery) return null
        val coordinates = Uri.decode(schemeSpecificPart.substringBefore('?'))
        val match = pair.find(coordinates) ?: return null
        return location(match.groupValues[1], match.groupValues[2], null)
    }

    internal fun parseMapUrl(url: String): ParsedLocation? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        if (!isGoogleMapsHost(uri.host)) return null
        val name = placeName(uri)

        val latFirstDestinations = destination.findAll(url).toList()
        val lngFirstDestinations = destinationLngFirst.findAll(url).toList()
        latFirstDestinations.lastOrNull()?.let { match ->
            return location(match.groupValues[1], match.groupValues[2], name)
        }
        lngFirstDestinations.lastOrNull()?.let { match ->
            return location(match.groupValues[2], match.groupValues[1], name)
        }
        if (destinationMarker.containsMatchIn(url)) return null

        for (key in listOf("query", "q", "ll", "destination")) {
            val value = uri.getQueryParameter(key) ?: continue
            val match = pair.find(Uri.decode(value)) ?: return null
            return location(match.groupValues[1], match.groupValues[2], name)
        }

        val at = viewport.find(url)
        return at?.let { location(it.groupValues[1], it.groupValues[2], name) }
    }

    private fun placeName(uri: Uri): String? {
        val segments = uri.pathSegments
        val placeIndex = segments.indexOf("place")
        if (placeIndex < 0 || placeIndex + 1 >= segments.size) return null
        return Uri.decode(segments[placeIndex + 1])
            .replace('+', ' ')
            .trim()
            .takeIf { it.length in 2..120 && pair.matchEntire(it) == null }
    }

    private fun trimUrlPunctuation(value: String): String {
        var result = value.trimEnd('.', ',', ';', ':', '!', '?')
        while (result.endsWith(')') && result.count { it == ')' } > result.count { it == '(' }) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun location(latText: String, lngText: String, name: String?): ParsedLocation? {
        val lat = latText.toDoubleOrNull() ?: return null
        val lng = lngText.toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            return null
        }
        return ParsedLocation(lat, lng, name?.trim()?.takeIf(String::isNotEmpty))
    }

    internal fun isGoogleMapsHost(host: String?): Boolean {
        val normalized = host?.lowercase() ?: return false
        return normalized == "google.com" || normalized.endsWith(".google.com") ||
            normalized == "maps.app.goo.gl" || normalized == "goo.gl"
    }
}

/** Resolves a small HTTPS redirect chain without reading response bodies. */
object SafeGoogleMapsRedirectResolver : RedirectResolver {
    private const val MAX_REDIRECTS = 5
    private const val TIMEOUT_MILLIS = 4_000

    internal fun canResolve(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "maps.app.goo.gl" || host == "goo.gl")
    }

    override suspend fun resolve(url: String): String? = withContext(Dispatchers.IO) {
        if (!canResolve(url)) return@withContext null
        var current = url
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (current != url && IntentParser.parseMapUrl(current) != null) return@withContext current
            val parsed = runCatching { URL(current) }.getOrNull() ?: return@withContext null
            if (parsed.protocol != "https" || parsed.userInfo != null ||
                (parsed.port != -1 && parsed.port != 443) || !IntentParser.isGoogleMapsHost(parsed.host)) {
                return@withContext null
            }
            val connection = (parsed.openConnection() as? HttpURLConnection) ?: return@withContext null
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "AreWeThereYet/1")
                connection.setRequestProperty("Range", "bytes=0-0")
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) return@withContext null
                    val location = connection.getHeaderField("Location") ?: return@withContext null
                    current = URL(parsed, location).toString()
                } else {
                    return@withContext current.takeIf { code in 200..299 }
                }
            } finally {
                connection.disconnect()
            }
        }
        null
    }
}
