package com.example.are_we_there_yet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeArgumentsTest {
    @Test
    fun `numeric platform values parse at valid boundaries`() {
        val alarm = NativeArguments.alarm(
            mapOf("id" to "a", "lat" to 90, "lng" to -180, "radius" to 50, "name" to "Edge"),
        )

        assertEquals(90.0, alarm.latitude, 0.0)
        assertEquals(-180.0, alarm.longitude, 0.0)
        assertEquals(50f, alarm.radius, 0f)
        assertEquals("Edge", alarm.name)
    }

    @Test
    fun `missing malformed and unsafe alarm values are rejected`() {
        val invalid = listOf(
            emptyMap<String, Any?>(),
            mapOf("id" to "", "lat" to 1, "lng" to 2, "radius" to 100),
            mapOf("id" to "a", "lat" to Double.NaN, "lng" to 2, "radius" to 100),
            mapOf("id" to "a", "lat" to 91, "lng" to 2, "radius" to 100),
            mapOf("id" to "a", "lat" to 1, "lng" to 2, "radius" to 49),
            mapOf("id" to "a", "lat" to 1, "lng" to 2, "radius" to 10001),
        )

        invalid.forEach { args ->
            assertThrows(InvalidArgumentsException::class.java) { NativeArguments.alarm(args) }
        }
    }

    @Test
    fun `sync rejects more than the platform geofence limit and duplicate ids`() {
        val tooMany = List(101) { index -> alarmMap("id-$index") }
        assertThrows(InvalidArgumentsException::class.java) {
            NativeArguments.sync(mapOf("alarms" to tooMany, "inactiveIds" to emptyList<String>()))
        }
        assertThrows(InvalidArgumentsException::class.java) {
            NativeArguments.sync(
                mapOf("alarms" to listOf(alarmMap("same"), alarmMap("same")), "inactiveIds" to emptyList<String>()),
            )
        }
    }

    private fun alarmMap(id: String) =
        mapOf("id" to id, "lat" to 1, "lng" to 2, "radius" to 100, "name" to id)
}
