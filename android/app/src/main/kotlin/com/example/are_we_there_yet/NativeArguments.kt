package com.example.are_we_there_yet

class InvalidArgumentsException(message: String) : IllegalArgumentException(message)
data class Place(val lat: Double, val lng: Double, val name: String)

object NativeArguments {
    const val MIN_RADIUS = 50.0
    /** The largest radius any setting allows; [TripStore.maxRadius] limits the sheet further. */
    const val MAX_RADIUS = 100_000.0
    /** Choices for the largest radius the destination sheet offers. */
    val MAX_RADIUS_CHOICES = listOf(10_000.0, 25_000.0, 50_000.0, 100_000.0)
    const val MAX_ALERT_MINUTES = 120

    fun trip(arguments: Any?): Trip {
        val values = arguments as? Map<*, *> ?: throw InvalidArgumentsException("Expected trip arguments")
        val id = (values["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }
            ?: throw InvalidArgumentsException("A valid id is required")
        val name = (values["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
            ?: throw InvalidArgumentsException("A destination name is required")
        val lat = (values["lat"] as? Number)?.toDouble() ?: throw InvalidArgumentsException("Latitude is required")
        val lng = (values["lng"] as? Number)?.toDouble() ?: throw InvalidArgumentsException("Longitude is required")
        val radius = (values["radius"] as? Number)?.toDouble() ?: throw InvalidArgumentsException("Radius is required")
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lng.isFinite() || lng !in -180.0..180.0 ||
            !radius.isFinite() || radius !in MIN_RADIUS..MAX_RADIUS
        ) {
            throw InvalidArgumentsException("Coordinates or radius are outside the supported range")
        }
        val alertMinutes = when (val value = values["alertMinutes"]) {
            null -> null
            is Int, is Long -> (value as Number).toInt().takeIf { it in 1..MAX_ALERT_MINUTES }
                ?: throw InvalidArgumentsException("Alert time must be 1 to $MAX_ALERT_MINUTES minutes")
            else -> throw InvalidArgumentsException("Alert time must be whole minutes")
        }
        return Trip(id, name, lat, lng, radius, startDistance = null, paused = false, alertMinutes = alertMinutes)
    }

    fun place(arguments: Any?): Place {
        val trip = trip(
            (arguments as? Map<*, *> ?: throw InvalidArgumentsException("Expected a place")) +
                mapOf("id" to "place", "radius" to MIN_RADIUS),
        )
        return Place(trip.lat, trip.lng, trip.name)
    }

    fun maxRadius(arguments: Any?): Double = ((arguments as? Map<*, *>)?.get("meters") as? Number)?.toDouble()
        ?.takeIf { it in MAX_RADIUS_CHOICES }
        ?: throw InvalidArgumentsException("meters must be one of $MAX_RADIUS_CHOICES")

    fun enabled(arguments: Any?): Boolean = (arguments as? Map<*, *>)?.get("enabled") as? Boolean
        ?: throw InvalidArgumentsException("enabled must be true or false")
}
