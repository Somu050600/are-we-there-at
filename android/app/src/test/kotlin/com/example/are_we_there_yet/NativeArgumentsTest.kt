package com.example.are_we_there_yet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeArgumentsTest {
    private fun args(vararg overrides: Pair<String, Any?>) =
        mapOf<String, Any?>("id" to "a", "name" to "Edge", "lat" to 1, "lng" to 2, "radius" to 100) + overrides

    @Test
    fun `numeric platform values parse at valid boundaries`() {
        val trip = NativeArguments.trip(args("lat" to 90, "lng" to -180, "radius" to 50))

        assertEquals(90.0, trip.lat, 0.0)
        assertEquals(-180.0, trip.lng, 0.0)
        assertEquals(50.0, trip.radius, 0.0)
        assertEquals("Edge", trip.name)
        assertNull(trip.startDistance)
    }

    @Test
    fun `missing malformed and unsafe trip values are rejected`() {
        val invalid = listOf(
            emptyMap(),
            args("id" to ""),
            args("name" to "  "),
            args("name" to null),
            args("lat" to Double.NaN),
            args("lat" to 91),
            args("lng" to "2"),
            args("radius" to 49),
            args("radius" to 100_001),
        )

        invalid.forEach { values ->
            assertThrows(InvalidArgumentsException::class.java) { NativeArguments.trip(values) }
        }
    }

    @Test
    fun `the time alert is optional whole minutes up to two hours`() {
        assertNull(NativeArguments.trip(args()).alertMinutes)
        assertNull(NativeArguments.trip(args("alertMinutes" to null)).alertMinutes)
        assertEquals(10, NativeArguments.trip(args("alertMinutes" to 10)).alertMinutes)
        assertEquals(120, NativeArguments.trip(args("alertMinutes" to 120L)).alertMinutes)
        listOf(0, 121, "10", 10.5).forEach { minutes ->
            assertThrows(InvalidArgumentsException::class.java) {
                NativeArguments.trip(args("alertMinutes" to minutes))
            }
        }
    }

    @Test
    fun `radius goes up to 100 km and the setting takes only its choices`() {
        assertEquals(100_000.0, NativeArguments.trip(args("radius" to 100_000)).radius, 0.0)
        assertEquals(25_000.0, NativeArguments.maxRadius(mapOf("meters" to 25_000)), 0.0)
        listOf(0, 12_345, 200_000, "10000").forEach { meters ->
            assertThrows(InvalidArgumentsException::class.java) { NativeArguments.maxRadius(mapOf("meters" to meters)) }
        }
    }

    @Test
    fun `enabled flag must be a boolean`() {
        assertEquals(true, NativeArguments.enabled(mapOf("enabled" to true)))
        assertThrows(InvalidArgumentsException::class.java) { NativeArguments.enabled(mapOf("enabled" to "yes")) }
    }

    @Test
    fun `a place needs valid coordinates and a name`() {
        val place = NativeArguments.place(mapOf("lat" to 12.9, "lng" to 77.5, "name" to "Station"))
        assertEquals(Place(12.9, 77.5, "Station"), place)
        assertThrows(InvalidArgumentsException::class.java) {
            NativeArguments.place(mapOf("lat" to 91, "lng" to 77.5, "name" to "x"))
        }
    }

    @Test
    fun `searches need a query, and Google searches a session`() {
        assertEquals(
            PlaceSearch("Beyond Bajji", google = true, session = "a-1"),
            NativeArguments.placeSearch(mapOf("query" to " Beyond Bajji ", "google" to true, "session" to "a-1")),
        )
        assertNull(NativeArguments.placeSearch(mapOf("query" to "MG Road", "google" to false)).session)
        assertThrows(InvalidArgumentsException::class.java) {
            NativeArguments.placeSearch(mapOf("query" to "x", "google" to false))
        }
        assertThrows(InvalidArgumentsException::class.java) {
            NativeArguments.placeSearch(mapOf("query" to "MG Road", "google" to true))
        }
        assertThrows(InvalidArgumentsException::class.java) {
            NativeArguments.placeSearch(mapOf("query" to "MG Road", "google" to true, "session" to "a b"))
        }
    }

    @Test
    fun `place ids and pasted text are checked`() {
        assertEquals("ChIJ_a-1" to null, NativeArguments.placeId(mapOf("placeId" to "ChIJ_a-1")))
        assertThrows(InvalidArgumentsException::class.java) { NativeArguments.placeId(mapOf("placeId" to "../x")) }
        assertEquals("geo:1,2", NativeArguments.text(mapOf("text" to " geo:1,2 ")))
        assertThrows(InvalidArgumentsException::class.java) { NativeArguments.text(mapOf("text" to "  ")) }
    }
}
