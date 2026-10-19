package com.example.are_we_there_yet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class AlarmRecord(val id: String, val latitude: Double, val longitude: Double, val radius: Float, val name: String?)
data class AlarmSnapshot(val active: List<AlarmRecord>, val pendingRemovals: Set<String>)
class AlarmStoreException(message: String) : IllegalStateException(message)

interface AlarmStateStore {
    fun snapshot(): AlarmSnapshot
    fun saveActive(alarm: AlarmRecord)
    fun beginRemoval(id: String)
    fun finishRemoval(id: String)
    fun replaceActive(alarms: List<AlarmRecord>)
    fun activeAlarm(id: String): AlarmRecord?
}

interface AlarmPersistence {
    fun activeJson(): String
    fun pendingRemovals(): Set<String>
    fun commit(activeJson: String, pendingRemovals: Set<String>): Boolean
}

private class SharedPreferencesAlarmPersistence(private val preferences: SharedPreferences) : AlarmPersistence {
    override fun activeJson() = preferences.getString("active", "[]") ?: "[]"
    override fun pendingRemovals() = preferences.getStringSet("pending_removals", emptySet())?.toSet().orEmpty()
    override fun commit(activeJson: String, pendingRemovals: Set<String>) = preferences.edit()
        .putString("active", activeJson)
        .putStringSet("pending_removals", pendingRemovals.toSet())
        .commit()
}

class AlarmStore : AlarmStateStore {
    private val persistence: AlarmPersistence

    constructor(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        persistence = SharedPreferencesAlarmPersistence(preferences)
    }

    internal constructor(persistence: AlarmPersistence) {
        this.persistence = persistence
    }

    @Synchronized override fun snapshot() = AlarmSnapshot(readActive(), readPending())

    @Synchronized override fun saveActive(alarm: AlarmRecord) {
        val active = readActive().associateByTo(linkedMapOf()) { it.id }
        active[alarm.id] = alarm
        write(active.values, readPending() - alarm.id)
    }

    /** Suppress callbacks before asynchronous GMS removal starts. */
    @Synchronized override fun beginRemoval(id: String) = write(readActive().filterNot { it.id == id }, readPending() + id)
    @Synchronized override fun finishRemoval(id: String) = write(readActive().filterNot { it.id == id }, readPending() - id)

    @Synchronized override fun replaceActive(alarms: List<AlarmRecord>) {
        write(alarms, readPending() - alarms.mapTo(hashSetOf()) { it.id })
    }

    @Synchronized override fun activeAlarm(id: String): AlarmRecord? =
        if (id in readPending()) null else readActive().firstOrNull { it.id == id }

    @Synchronized fun isNotificationAllowed(id: String) = activeAlarm(id) != null

    private fun readActive(): List<AlarmRecord> = runCatching {
        val array = JSONArray(persistence.activeJson())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val alarm = AlarmRecord(
                    item.getString("id"), item.getDouble("lat"), item.getDouble("lng"),
                    item.getDouble("radius").toFloat(),
                    if (item.isNull("name")) null else item.getString("name"),
                )
                if (NativeArguments.isValid(alarm)) add(alarm)
            }
        }
    }.getOrDefault(emptyList())

    private fun readPending() = persistence.pendingRemovals()

    private fun write(active: Collection<AlarmRecord>, pending: Set<String>) {
        val json = JSONArray()
        active.sortedBy { it.id }.forEach { alarm ->
            json.put(JSONObject().put("id", alarm.id).put("lat", alarm.latitude)
                .put("lng", alarm.longitude).put("radius", alarm.radius.toDouble())
                .put("name", alarm.name ?: JSONObject.NULL))
        }
        if (!persistence.commit(json.toString(), pending.toSet())) {
            throw AlarmStoreException("Could not persist native alarm state")
        }
    }

    companion object {
        internal const val PREFERENCES = "native_alarm_state_v1"
    }
}
