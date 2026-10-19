package com.example.are_we_there_yet

class InvalidArgumentsException(message: String) : IllegalArgumentException(message)
data class SyncArguments(val alarms: List<AlarmRecord>, val inactiveIds: Set<String>)

object NativeArguments {
    const val MAX_GEOFENCES = 100

    fun alarm(arguments: Any?): AlarmRecord {
        val values = arguments as? Map<*, *> ?: throw InvalidArgumentsException("Expected alarm arguments")
        val id = (values["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }
            ?: throw InvalidArgumentsException("A valid id is required")
        val lat = (values["lat"] as? Number)?.toDouble() ?: throw InvalidArgumentsException("Latitude is required")
        val lng = (values["lng"] as? Number)?.toDouble() ?: throw InvalidArgumentsException("Longitude is required")
        val radius = (values["radius"] as? Number)?.toFloat() ?: throw InvalidArgumentsException("Radius is required")
        val rawName = values["name"]
        if (rawName != null && rawName !is String) throw InvalidArgumentsException("Name must be text")
        val name = (rawName as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null && name.length > 200) throw InvalidArgumentsException("Name is too long")
        return AlarmRecord(id, lat, lng, radius, name).also {
            if (!isValid(it)) throw InvalidArgumentsException("Coordinates or radius are outside the supported range")
        }
    }

    fun id(arguments: Any?): String = ((arguments as? Map<*, *>)?.get("id") as? String)
        ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }
        ?: throw InvalidArgumentsException("A valid id is required")

    fun sync(arguments: Any?): SyncArguments {
        val values = arguments as? Map<*, *> ?: throw InvalidArgumentsException("Expected sync arguments")
        val rawAlarms = values["alarms"] as? List<*> ?: throw InvalidArgumentsException("Alarms are required")
        if (rawAlarms.size > MAX_GEOFENCES) throw InvalidArgumentsException("Android supports at most 100 active geofences")
        val alarms = rawAlarms.map(::alarm)
        if (alarms.map { it.id }.toSet().size != alarms.size) throw InvalidArgumentsException("Alarm ids must be unique")
        val rawInactive = values["inactiveIds"] ?: emptyList<String>()
        val list = rawInactive as? List<*> ?: throw InvalidArgumentsException("Inactive ids must be a list")
        if (list.size > 10_000) throw InvalidArgumentsException("Too many inactive ids")
        val inactive = list.map {
            (it as? String)?.trim()?.takeIf { id -> id.isNotEmpty() && id.length <= 100 }
                ?: throw InvalidArgumentsException("Inactive ids must be valid strings")
        }.toSet()
        if (alarms.any { it.id in inactive }) throw InvalidArgumentsException("An alarm cannot be both active and inactive")
        return SyncArguments(alarms, inactive)
    }

    internal fun isValid(alarm: AlarmRecord) = alarm.id.isNotBlank() && alarm.id.length <= 100 &&
        alarm.latitude.isFinite() && alarm.latitude in -90.0..90.0 &&
        alarm.longitude.isFinite() && alarm.longitude in -180.0..180.0 &&
        alarm.radius.isFinite() && alarm.radius in 50f..10_000f &&
        (alarm.name == null || alarm.name.length <= 200)
}
