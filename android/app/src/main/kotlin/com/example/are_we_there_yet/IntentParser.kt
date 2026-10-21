package com.example.are_we_there_yet

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun interface RedirectResolver {
    /** Where [url] leads; null when it leads nowhere useful. Throws [IOException] when offline. */
    suspend fun resolve(url: String): String?
}

/** A share's short link couldn't be opened, so where it points is unknown. Worth a retry. */
class ShareUnreachableException(cause: IOException) : IOException("Couldn't reach Google Maps", cause)

/** Finds a place by its name or address; returns null when it can't. */
fun interface PlaceGeocoder {
    suspend fun locate(query: String): IntentParser.ParsedLocation?

    companion object {
        val None = PlaceGeocoder { null }
    }
}

/**
 * Reads a destination from shared text: geo: URIs, map links (Google, Apple, OpenStreetMap,
 * Waze, Bing, HERE, Yandex, or any link with lat/lng parameters), plus codes, and coordinates
 * written as decimals or degrees. It never scrapes HTML for coordinate-shaped arrays. When a
 * share names a place without coordinates (what the Google Maps app sends for a named place),
 * [PlaceFinder] finds the exact place by its Maps id; failing that, the place is looked up with
 * [PlaceGeocoder] and the result is marked approximate.
 */
object IntentParser {
    data class ParsedLocation(
        val lat: Double,
        val lng: Double,
        val name: String? = null,
        val approximate: Boolean = false,
    )

    private const val NUMBER = """[+-]?(?:\d+(?:\.\d+)?|\.\d+)"""
    private const val DEGREES = """(\d{1,3}(?:\.\d+)?)\s*[°º˚]\s*(?:(\d{1,2}(?:\.\d+)?)\s*['′’]\s*)?(?:(\d{1,2}(?:\.\d+)?)\s*(?:["″”]|''|′′)\s*)?"""
    private val pair = Regex("""(?<![\w.])($NUMBER)\s*,\s*($NUMBER)(?![\w.])""")
    private val degrees = Regex("""$DEGREES([NSns])[\s,;/]*$DEGREES([EWew])""")
    private val hemisphereAfter = Regex("""(?<![\w.])(\d{1,2}(?:\.\d+)?)\s*°?\s*([NS])[\s,;/]+(\d{1,3}(?:\.\d+)?)\s*°?\s*([EW])(?![A-Za-z])""")
    private val hemisphereBefore = Regex("""(?<![A-Za-z])([NS])\s*(\d{1,2}(?:\.\d+)?)\s*°?[\s,;/]+([EW])\s*(\d{1,3}(?:\.\d+)?)(?![\d.])""")
    private val destination = Regex("""!3d($NUMBER)!4d($NUMBER)""", RegexOption.IGNORE_CASE)
    private val destinationLngFirst = Regex("""!2d($NUMBER)!3d($NUMBER)""", RegexOption.IGNORE_CASE)
    private val destinationMarker = Regex("""!(?:3d[^!/?&#]+!4d|2d[^!/?&#]+!3d)[^!/?&#]+""", RegexOption.IGNORE_CASE)
    private val viewport = Regex("""@($NUMBER),($NUMBER)""")
    private val webUrl = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val bareUrl = Regex("""(?<![\w@/.:-])(?:[a-z0-9-]+\.)+[a-z]{2,}/[^\s<>"]*""", RegexOption.IGNORE_CASE)
    private val geoLine = Regex("""(?im)\bgeo:[^\r\n]+""")
    private val plusCode = Regex("""(?<![0-9A-Za-z+])([23456789CFGHJMPQRVWX]{8}\+[23456789CFGHJMPQRVWX]{0,7}|[23456789CFGHJMPQRVWX]{2,6}0{2,6}\+)(?![0-9A-Za-z+])""", RegexOption.IGNORE_CASE)
    private val invisible = Regex("""[​-‍⁠﻿]""")
    private val genericLabels = setOf(
        "dropped pin", "pinned location", "shared location", "location", "my location", "current location",
        "see", "here", "check this out", "check out this place", "directions", "google maps", "apple maps",
    )
    private const val PLUS_ALPHABET = "23456789CFGHJMPQRVWX"

    suspend fun parse(
        intent: Intent,
        resolver: RedirectResolver = SafeGoogleMapsRedirectResolver,
        geocoder: PlaceGeocoder = PlaceGeocoder.None,
        finder: PlaceFinder = PlaceFinder.None,
    ): ParsedLocation? {
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.toString()
            else -> null
        } ?: return null
        return parseText(text, resolver, geocoder, finder)
    }

    internal suspend fun parseText(
        rawText: String,
        resolver: RedirectResolver,
        geocoder: PlaceGeocoder = PlaceGeocoder.None,
        finder: PlaceFinder = PlaceFinder.None,
    ): ParsedLocation? {
        val text = clean(rawText)
        val places = mutableListOf<String>()
        geoLine.find(text)?.value?.let { line ->
            parseGeo(line)?.let { return it }
            val search = geoSearch(line) ?: return null
            places += search
        }

        val links = links(text)
        var textWithoutUrls = text
        for ((range, _) in links) {
            textWithoutUrls = textWithoutUrls.replaceRange(range, " ".repeat(range.last - range.first + 1))
        }
        textWithoutUrls = textWithoutUrls.replace(geoLine, " ")
        var sawMapsLink = false
        var sawOtherLink = false
        var cid: String? = null
        var unreachable: IOException? = null
        for ((_, url) in links) {
            parseMapUrl(url)?.let { return it.withLabel(textLabel(textWithoutUrls)) }
            placeQuery(url)?.let(places::add)
            googleCid(url)?.let { cid = it }
            if (isMapsLink(url)) sawMapsLink = true else sawOtherLink = true
            if (SafeGoogleMapsRedirectResolver.canResolve(url)) {
                val resolved = try {
                    resolver.resolve(url)?.let(::unwrap)
                } catch (offline: IOException) {
                    unreachable = offline
                    null
                }
                if (resolved != null) {
                    parseMapUrl(resolved)?.let { return it.withLabel(textLabel(textWithoutUrls)) }
                    placeQuery(resolved)?.let(places::add)
                    googleCid(resolved)?.let { cid = it }
                }
            }
        }
        fullPlusCode(textWithoutUrls)?.let { return it.withLabel(textLabel(textWithoutUrls)) }
        coordinatesIn(textWithoutUrls)?.let { found ->
            // Next to a named place, whole numbers are more likely part of its address.
            val precise = found.lat % 1.0 != 0.0 || found.lng % 1.0 != 0.0
            if (places.isEmpty() || precise) return found.withLabel(textLabel(textWithoutUrls))
        }
        // Without the link's target there is no Maps id, so a lookup could only guess.
        unreachable?.let { throw ShareUnreachableException(it) }

        // The Maps app shares named places as "Name, address" plus a link that expands to the
        // place without coordinates. Look the place up instead of rejecting the share. Plain
        // text (an address, a short plus code with its town) is looked up too, but never the
        // text around an unrelated link.
        val sharedLines = textWithoutUrls.lines().map(::trimLine).filter(String::isNotEmpty)
        val plainText = sharedLines.joinToString(", ")
        val lookUpText = sawMapsLink || places.isNotEmpty() ||
            (!sawOtherLink && plainText.length in 3..200 && sharedLines.size <= 4)
        if (places.isEmpty() && lookUpText && plainText.isNotEmpty() && plainText.lowercase() !in genericLabels) {
            places += plainText
        }
        // Google's own search, matched by the link's Maps id, finds the exact place.
        for (place in places.distinct()) {
            val found = finder.find(place, cid) ?: continue
            val located = location(found.lat, found.lng, placeLabel(place)) ?: continue
            return located.copy(approximate = found.approximate)
        }
        for (place in places.distinct()) {
            for (query in lookupQueries(place)) {
                val found = geocoder.locate(query) ?: continue
                val located = location(found.lat, found.lng, placeLabel(place)) ?: continue
                return located.copy(approximate = true)
            }
        }
        return null
    }

    /** Removes invisible characters and odd spacing that chat apps add around shared links. */
    private fun clean(text: String): String = text
        .replace(invisible, "")
        .replace(' ', ' ')
        .replace(' ', '\n')
        .replace(' ', '\n')
        .replace("\r\n", "\n")
        .trim()

    private fun trimLine(line: String): String =
        line.trim().trim('"', '\'', '“', '”', '‘', '’', '«', '»', '<', '>', '(', ')', '[', ']', '-', '–', '—', '•', '*', ':', '|', ',', ';')
            .trim()

    /** Every link in the text with its range, including map links pasted without https://. */
    private fun links(text: String): List<Pair<IntRange, String>> {
        val found = webUrl.findAll(text).map { it.range to it.value }.toMutableList()
        for (match in bareUrl.findAll(text)) {
            if (found.any { (range, _) -> match.range.first in range }) continue
            if (isMapsLink("https://${match.value}")) found += match.range to "https://${match.value}"
        }
        return found.sortedBy { it.first.first }.map { (range, value) ->
            val trimmed = trimUrlPunctuation(value)
            val removed = value.length - trimmed.length
            (range.first..range.last - removed) to unwrap(trimmed)
        }
    }

    /** Follows Google's url?q= and consent?continue= wrappers to the link they carry. */
    private fun unwrap(url: String): String {
        var current = url
        repeat(3) {
            val uri = runCatching { Uri.parse(current) }.getOrNull() ?: return current
            val host = uri.host?.lowercase() ?: return current
            val inner = when {
                isGoogleHost(host) && uri.path == "/url" -> uri.getQueryParameter("q") ?: uri.getQueryParameter("url")
                host.startsWith("consent.") && isGoogleHost(host) -> uri.getQueryParameter("continue")
                else -> null
            }?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return current
            current = inner
        }
        return current
    }

    private fun ParsedLocation.withLabel(label: String?): ParsedLocation =
        if (name != null || label == null) this else copy(name = label)

    /** A short title line next to the link, like "Office" or "Majestic bus stand". */
    private fun textLabel(textWithoutUrls: String): String? {
        val line = textWithoutUrls.lines()
            .map { line ->
                listOf(degrees, hemisphereAfter, hemisphereBefore, pair, plusCode)
                    .fold(line) { text, pattern -> text.replace(pattern, " ") }
            }
            .map(::trimLine)
            .firstOrNull(String::isNotEmpty) ?: return null
        if (line.lowercase() in genericLabels || line.endsWith(':')) return null
        return line.takeIf { it.length in 2..60 && hasWords(it) }
    }

    /** The full name and address first, then the address alone if the name confuses the lookup. */
    private fun lookupQueries(place: String): List<String> {
        val address = place.substringAfter(',', missingDelimiterValue = "").trim()
        return listOf(place, address).filter { it.length >= 3 }.distinct()
    }

    /** Coordinates written as "12.97, 77.59", "12.97 N 77.59 E" or "12°58'12\"N 77°35'24\"E". */
    internal fun coordinatesIn(text: String): ParsedLocation? {
        degrees.find(text)?.let { match ->
            val g = match.groupValues
            val lat = degreesValue(g[1], g[2], g[3], g[4]) ?: return null
            val lng = degreesValue(g[5], g[6], g[7], g[8]) ?: return null
            return location(lat, lng, null)
        }
        hemisphereAfter.find(text)?.let { match ->
            val g = match.groupValues
            return location(signed(g[1], g[2]) ?: return null, signed(g[3], g[4]) ?: return null, null)
        }
        hemisphereBefore.find(text)?.let { match ->
            val g = match.groupValues
            return location(signed(g[2], g[1]) ?: return null, signed(g[4], g[3]) ?: return null, null)
        }
        return pair.find(text)?.let { location(it.groupValues[1], it.groupValues[2], null) }
    }

    private fun degreesValue(degreesText: String, minutesText: String, secondsText: String, hemisphere: String): Double? {
        val degrees = degreesText.toDoubleOrNull() ?: return null
        val minutes = minutesText.takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: 0.0
        val seconds = secondsText.takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: 0.0
        if (minutes >= 60 || seconds >= 60) return null
        return signed((degrees + minutes / 60 + seconds / 3600).toString(), hemisphere)
    }

    private fun signed(value: String, hemisphere: String): Double? {
        val number = value.toDoubleOrNull() ?: return null
        return if (hemisphere.uppercase() == "S" || hemisphere.uppercase() == "W") -number else number
    }

    /** Decodes a full Open Location Code such as "7J4VWJ9G+3P" to the centre of its area. */
    internal fun fullPlusCode(text: String): ParsedLocation? {
        val code = plusCode.find(text)?.value?.uppercase() ?: return null
        val digits = code.replace("+", "").trimEnd('0')
        if (digits.length < 2 || digits.length % 2 != 0 && digits.length < 10) return null
        if (PLUS_ALPHABET.indexOf(digits[0]) > 8 || PLUS_ALPHABET.indexOf(digits[1]) > 17) return null
        var lat = -90.0
        var lng = -180.0
        var resolution = 20.0
        var latSize = 20.0
        var lngSize = 20.0
        val pairs = minOf(digits.length, 10) / 2
        for (i in 0 until pairs) {
            lat += PLUS_ALPHABET.indexOf(digits[2 * i]) * resolution
            lng += PLUS_ALPHABET.indexOf(digits[2 * i + 1]) * resolution
            latSize = resolution
            lngSize = resolution
            resolution /= 20
        }
        for (c in digits.drop(10)) {
            val value = PLUS_ALPHABET.indexOf(c)
            latSize /= 5
            lngSize /= 4
            lat += (value / 4) * latSize
            lng += (value % 4) * lngSize
        }
        return location(lat + latSize / 2, lng + lngSize / 2, null)
    }

    /** The Maps id of the place a Google link points at, if it has one. */
    private fun googleCid(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (provider(uri) != Provider.GOOGLE) return null
        return GooglePlaces.cid(Uri.decode(url))
    }

    private fun isMapsLink(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" && uri.scheme != "http") return false
        return provider(uri) != null
    }

    private enum class Provider { GOOGLE, APPLE, OSM, WAZE, BING, HERE, YANDEX }

    private fun provider(uri: Uri): Provider? {
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = uri.path.orEmpty()
        return when {
            host == "maps.app.goo.gl" || host == "goo.gl" ||
                isGoogleHost(host) && (host.startsWith("maps.") || path.startsWith("/maps")) -> Provider.GOOGLE
            host == "maps.apple.com" || host == "maps.apple" || host == "collections.apple.com" -> Provider.APPLE
            host == "openstreetmap.org" || host == "osm.org" || host.endsWith(".openstreetmap.org") -> Provider.OSM
            host == "waze.com" || host.endsWith(".waze.com") -> Provider.WAZE
            (host == "bing.com" || host.endsWith(".bing.com")) && path.startsWith("/maps") -> Provider.BING
            host == "here.com" || host.endsWith(".here.com") -> Provider.HERE
            (host.startsWith("yandex.") || host.endsWith(".yandex.ru") || host.endsWith(".yandex.com")) &&
                path.startsWith("/maps") -> Provider.YANDEX
            else -> null
        }
    }

    /** A place name or search text from a map link that has no coordinates. */
    internal fun placeQuery(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val raw = when (provider(uri)) {
            Provider.GOOGLE -> {
                val segments = uri.pathSegments
                val placeIndex = segments.indexOf("place")
                val searchIndex = segments.indexOf("search")
                when {
                    placeIndex >= 0 -> segments.getOrNull(placeIndex + 1)
                    segments.contains("dir") -> dirDestination(segments)
                    searchIndex >= 0 && segments.getOrNull(searchIndex + 1)?.startsWith("@") == false ->
                        segments[searchIndex + 1]
                    else -> null
                } ?: param(uri, "query", "q", "destination", "daddr")
            }
            Provider.APPLE -> param(uri, "q", "address", "daddr", "name")
            Provider.OSM -> param(uri, "query")
            Provider.WAZE -> param(uri, "q")
            Provider.BING -> param(uri, "where1", "q")
            Provider.HERE -> null
            Provider.YANDEX -> param(uri, "text")
            null -> null
        }
        return raw?.let(::cleanPlace)?.takeIf { it.length in 2..300 && hasWords(it) }
    }

    private fun param(uri: Uri, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        runCatching { uri.getQueryParameter(key) }.getOrNull()?.takeIf(String::isNotBlank)
    }

    /** The last stop of a Google /maps/dir/ route, skipping the camera and data segments. */
    private fun dirDestination(segments: List<String>): String? = segments
        .drop(segments.indexOf("dir") + 1)
        .lastOrNull { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") && !it.startsWith("am=") }

    private fun geoSearch(line: String): String? {
        val uri = runCatching { Uri.parse(line.trim()) }.getOrNull() ?: return null
        val part = uri.encodedSchemeSpecificPart ?: return null
        val q = part.substringAfter('?', missingDelimiterValue = "").split('&').firstNotNullOfOrNull { parameter ->
            parameter.substringAfter("=").takeIf { parameter.substringBefore('=').equals("q", ignoreCase = true) }
        } ?: return null
        return cleanPlace(q).takeIf { it.length in 2..300 && hasWords(it) }
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
        val coordinates = Uri.decode(schemeSpecificPart.substringBefore('?').substringBefore(';'))
        val match = pair.find(coordinates) ?: return null
        return location(match.groupValues[1], match.groupValues[2], null)
    }

    internal fun parseMapUrl(url: String): ParsedLocation? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        return when (provider(uri)) {
            Provider.GOOGLE -> parseGoogle(uri, url)
            Provider.APPLE -> {
                val name = param(uri, "q", "name")?.let(::cleanPlace)?.takeIf { coordinatesIn(it) == null }
                firstCoordinates(uri, "ll", "coordinate", "daddr", "q", "sll", "center")?.withName(name)
            }
            Provider.OSM -> {
                val lat = param(uri, "mlat")
                val lng = param(uri, "mlon")
                if (lat != null && lng != null) return location(lat, lng, null)
                val map = Uri.decode(uri.encodedFragment.orEmpty()).substringAfter("map=", "").split('/')
                if (map.size >= 3) location(map[1], map[2], null) else latLngParams(uri)
            }
            Provider.WAZE -> {
                val to = param(uri, "to")?.removePrefix("ll.")
                (to?.let(::coordinatesIn) ?: firstCoordinates(uri, "ll", "latlng"))
                    ?.withName(param(uri, "q")?.let(::cleanPlace)?.takeIf { coordinatesIn(it) == null })
            }
            Provider.BING -> {
                val cp = param(uri, "cp")?.replace('~', ',')
                val sp = param(uri, "sp")?.substringAfter("point.")?.split('_')
                val point = sp?.takeIf { it.size >= 2 }?.let { location(it[0], it[1], it.getOrNull(2)?.let(::cleanPlace)) }
                point ?: cp?.let(::coordinatesIn)
            }
            Provider.HERE -> {
                val segments = uri.pathSegments
                val fromPath = segments.getOrNull(segments.indexOf("l") + 1)?.takeIf { "l" in segments }
                val fromMap = param(uri, "map")
                (fromPath ?: fromMap)?.let { value ->
                    val parts = Uri.decode(value).split(',')
                    if (parts.size < 2) return null
                    val name = parts.getOrNull(2)?.takeIf { fromPath != null && it.any(Char::isLetter) }?.let(::cleanPlace)
                    location(parts[0], parts[1], name)
                }
            }
            Provider.YANDEX -> {
                // Yandex writes longitude first.
                val value = param(uri, "pt", "ll")?.split('~')?.first()?.split(',') ?: return null
                if (value.size >= 2) location(value[1], value[0], null) else null
            }
            null -> latLngParams(uri)
        }
    }

    private fun ParsedLocation.withName(name: String?): ParsedLocation =
        if (name.isNullOrBlank()) this else copy(name = placeLabel(name))

    private fun firstCoordinates(uri: Uri, vararg keys: String): ParsedLocation? = keys.firstNotNullOfOrNull { key ->
        param(uri, key)?.let { coordinatesIn(Uri.decode(it).replace('+', ' ')) }
    }

    /** Explicit lat/lng query parameters, which many sites and trackers use. */
    private fun latLngParams(uri: Uri): ParsedLocation? {
        val lat = param(uri, "lat", "latitude", "mlat") ?: return null
        val lng = param(uri, "lng", "lon", "long", "longitude", "mlon") ?: return null
        return location(lat, lng, null)
    }

    private fun parseGoogle(uri: Uri, url: String): ParsedLocation? {
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

        for (key in listOf("query", "q", "ll", "destination", "daddr", "center", "cbll", "sll")) {
            val value = param(uri, key) ?: continue
            val coordinates = coordinatesIn(Uri.decode(value).replace('+', ' ')) ?: return null
            return coordinates.withName(name)
        }

        // Coordinates in the path: /maps/place/12.9,77.5, /maps/search/12.9,+77.5, /maps/dir/A/12.9,77.5
        val segments = uri.pathSegments
        val pathValue = when {
            "place" in segments -> segments.getOrNull(segments.indexOf("place") + 1)
            "dir" in segments -> dirDestination(segments)
            "search" in segments -> segments.getOrNull(segments.indexOf("search") + 1)?.takeIf { !it.startsWith("@") }
            else -> null
        }
        if (pathValue != null) {
            val decoded = cleanPlace(pathValue)
            if (!hasWords(decoded)) coordinatesIn(decoded)?.let { return it }
            if ("dir" in segments) return null
        }

        val at = viewport.find(url)
        return at?.let { location(it.groupValues[1], it.groupValues[2], name) }
    }

    private fun placeName(uri: Uri): String? {
        val segments = uri.pathSegments
        val placeIndex = segments.indexOf("place")
        if (placeIndex < 0 || placeIndex + 1 >= segments.size) return null
        return cleanPlace(segments[placeIndex + 1])
            .takeIf { it.length in 2..300 && hasWords(it) }
            ?.let(::placeLabel)
    }

    /** True for a name or address; false for text that is only numbers and N/S/E/W. */
    private fun hasWords(text: String): Boolean = text.any { it.isLetter() && it.uppercaseChar() !in "NSEW" }

    private fun cleanPlace(value: String): String =
        runCatching { Uri.decode(value) }.getOrDefault(value).replace('+', ' ').replace(Regex("""\s+"""), " ").trim()

    /** Maps often names a place by its whole address; keep a long one to its first part. */
    private fun placeLabel(place: String): String? {
        val label = if (place.length <= 60) place else place.substringBefore(',').trim()
        return label.takeIf { it.length in 2..120 }
    }

    private fun trimUrlPunctuation(value: String): String {
        var result = value
        while (true) {
            val before = result
            result = result.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', '”', '’', '»', '>', '*', '_', '`')
            for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
                while (result.endsWith(close) && result.count { it == close } > result.count { it == open }) {
                    result = result.dropLast(1)
                }
            }
            if (result == before) return result
        }
    }

    private fun location(latText: String, lngText: String, name: String?): ParsedLocation? {
        val lat = latText.trim().toDoubleOrNull() ?: return null
        val lng = lngText.trim().toDoubleOrNull() ?: return null
        return location(lat, lng, name)
    }

    private fun location(lat: Double, lng: Double, name: String?): ParsedLocation? {
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            return null
        }
        return ParsedLocation(lat, lng, name?.trim()?.takeIf(String::isNotEmpty))
    }

    /** google.com, google.co.in, google.de and the like. */
    private fun isGoogleHost(host: String): Boolean =
        Regex("""^(?:[a-z0-9-]+\.)*google\.(?:com|[a-z]{2}|co\.[a-z]{2}|com\.[a-z]{2})$""").matches(host)

    internal fun isGoogleMapsHost(host: String?): Boolean {
        val normalized = host?.lowercase() ?: return false
        return isGoogleHost(normalized) || normalized == "maps.app.goo.gl" || normalized == "goo.gl"
    }
}

/**
 * Resolves a small HTTPS redirect chain without reading response bodies. Throws an
 * [IOException] when Google can't be reached.
 */
object SafeGoogleMapsRedirectResolver : RedirectResolver {
    private const val MAX_REDIRECTS = 5
    private const val TIMEOUT_MILLIS = 8_000

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
            val (code, location) = try {
                WebConnections.request(parsed, ::head)
            } catch (offline: IOException) {
                // A later hop failing still leaves the Maps link the short link pointed to.
                if (current != url) return@withContext current
                throw offline
            }
            if (code in 300..399) {
                if (redirectCount == MAX_REDIRECTS) return@withContext null
                current = URL(parsed, location ?: return@withContext null).toString()
            } else {
                return@withContext current.takeIf { code in 200..299 }
            }
        }
        null
    }

    /** The status and redirect target; the body is never read. */
    private fun head(connection: HttpURLConnection): Pair<Int, String?> {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "AreWeThereYet/1")
        connection.setRequestProperty("Range", "bytes=0-0")
        return connection.responseCode to connection.getHeaderField("Location")
    }
}
