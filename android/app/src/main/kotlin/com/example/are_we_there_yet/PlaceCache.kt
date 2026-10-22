package com.example.are_we_there_yet

import android.content.Context

/**
 * Where places found on Google are, by Maps id (CID), so sharing the same place again costs no
 * request. Keeps the most recently found [MAX_PLACES].
 */
class PlaceCache(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun get(cid: String): IntentParser.ParsedLocation? {
        val parts = preferences.getString(cid, null)?.split(',') ?: return null
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return IntentParser.ParsedLocation(lat, lng)
    }

    @Synchronized
    fun put(cid: String, location: IntentParser.ParsedLocation) {
        val editor = preferences.edit().putString(cid, "${location.lat},${location.lng},${clock()}")
        val others = preferences.all.filterKeys { it != cid }
        if (others.size >= MAX_PLACES) {
            others.entries
                .sortedBy { (it.value as? String)?.substringAfterLast(',')?.toLongOrNull() ?: 0L }
                .take(others.size - MAX_PLACES + 1)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    companion object {
        internal const val PREFERENCES = "place_cache_v1"
        internal const val MAX_PLACES = 500
    }
}
