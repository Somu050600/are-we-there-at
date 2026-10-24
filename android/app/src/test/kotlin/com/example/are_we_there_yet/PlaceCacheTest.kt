package com.example.are_we_there_yet

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaceCacheTest {
    private var now = 0L
    private val cache = PlaceCache(ApplicationProvider.getApplicationContext()) { now }

    @Test
    fun `a found place is remembered by its Maps id`() {
        assertNull(cache.get("1"))
        cache.put("1", IntentParser.ParsedLocation(14.4372674, 79.9705642))
        val found = cache.get("1")!!
        assertEquals(14.4372674, found.lat, 0.0)
        assertEquals(79.9705642, found.lng, 0.0)
    }

    @Test
    fun `the oldest places make room for new ones`() {
        for (index in 0 until PlaceCache.MAX_PLACES) {
            now = index.toLong()
            cache.put("$index", IntentParser.ParsedLocation(1.0, 2.0))
        }
        now = 10_000
        cache.put("new", IntentParser.ParsedLocation(3.0, 4.0))

        assertNull(cache.get("0"))
        assertNotNull(cache.get("1"))
        assertNotNull(cache.get("new"))
    }
}
