package com.example.are_we_there_yet

class InvalidArgumentsException(message: String) : IllegalArgumentException(message)

object NativeArguments {
    const val MIN_RADIUS = 50.0
    const val MAX_RADIUS = 10_000.0

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
        return Trip(id, name, lat, lng, radius, startDistance = null, paused = false)
    }

    fun enabled(arguments: Any?): Boolean = (arguments as? Map<*, *>)?.get("enabled") as? Boolean
        ?: throw InvalidArgumentsException("enabled must be true or false")
}
