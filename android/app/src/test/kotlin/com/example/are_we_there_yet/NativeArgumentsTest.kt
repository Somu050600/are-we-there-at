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
            args("radius" to 10_001),
        )

        invalid.forEach { values ->
            assertThrows(InvalidArgumentsException::class.java) { NativeArguments.trip(values) }
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
}
