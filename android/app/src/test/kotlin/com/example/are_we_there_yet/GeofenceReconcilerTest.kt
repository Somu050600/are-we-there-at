package com.example.are_we_there_yet

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceReconcilerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: AlarmStore

    @Before
    fun setUp() {
        context.getSharedPreferences(AlarmStore.PREFERENCES, 0).edit().clear().commit()
        store = AlarmStore(context)
    }

    @Test
    fun `restore removes durable tombstones before restoring active alarms without initial trigger`() {
        store.saveActive(AlarmRecord("active", 1.0, 2.0, 100f, "Active"))
        store.beginRemoval("stale")
        val gateway = RecordingGateway()

        GeofenceReconciler(store, gateway).restore { assertTrue(it.isSuccess) }

        assertEquals(listOf(setOf("stale")), gateway.removed)
        assertEquals(listOf(listOf("active") to false), gateway.added)
        assertTrue(store.snapshot().pendingRemovals.isEmpty())
    }

    @Test
    fun `failed removal stays durable and suppresses stale notifications`() {
        store.saveActive(AlarmRecord("old", 1.0, 2.0, 100f, "Old"))
        val gateway = RecordingGateway(removeFailure = IllegalStateException("offline"))
        var outcome: Result<Unit>? = null

        GeofenceReconciler(store, gateway).remove("old") { outcome = it }

        assertTrue(outcome?.isFailure == true)
        assertEquals(setOf("old"), store.snapshot().pendingRemovals)
        assertFalse(store.isNotificationAllowed("old"))
    }

    @Test
    fun `failed persistence after GMS add removes the unmanaged geofence and reports failure`() {
        val gateway = RecordingGateway()
        val failingStore = object : AlarmStateStore {
            override fun snapshot() = AlarmSnapshot(emptyList(), emptySet())
            override fun saveActive(alarm: AlarmRecord) = throw AlarmStoreException("disk full")
            override fun beginRemoval(id: String) = Unit
            override fun finishRemoval(id: String) = Unit
            override fun replaceActive(alarms: List<AlarmRecord>) = Unit
            override fun activeAlarm(id: String): AlarmRecord? = null
        }
        var outcome: Result<Unit>? = null

        GeofenceReconciler(failingStore, gateway).add(
            AlarmRecord("unmanaged", 1.0, 2.0, 100f, "Unmanaged"),
        ) { outcome = it }

        assertTrue(outcome?.isFailure == true)
        assertEquals(listOf(setOf("unmanaged")), gateway.removed)
    }

    @Test
    fun `store exception settles reconciliation callback`() {
        val failingStore = object : AlarmStateStore {
            override fun snapshot(): AlarmSnapshot = throw AlarmStoreException("unavailable")
            override fun saveActive(alarm: AlarmRecord) = Unit
            override fun beginRemoval(id: String) = Unit
            override fun finishRemoval(id: String) = Unit
            override fun replaceActive(alarms: List<AlarmRecord>) = Unit
            override fun activeAlarm(id: String): AlarmRecord? = null
        }
        var callbacks = 0
        var outcome: Result<Unit>? = null

        GeofenceReconciler(failingStore, RecordingGateway()).restore {
            callbacks += 1
            outcome = it
        }

        assertEquals(1, callbacks)
        assertTrue(outcome?.isFailure == true)
    }

    private class RecordingGateway(
        private val removeFailure: Throwable? = null,
    ) : GeofenceGateway {
        val removed = mutableListOf<Set<String>>()
        val added = mutableListOf<Pair<List<String>, Boolean>>()

        override fun add(
            alarms: List<AlarmRecord>,
            initialTrigger: Boolean,
            callback: (Result<Unit>) -> Unit,
        ) {
            added += alarms.map { it.id } to initialTrigger
            callback(Result.success(Unit))
        }

        override fun remove(ids: Set<String>, callback: (Result<Unit>) -> Unit) {
            removed += ids
            callback(removeFailure?.let { Result.failure(it) } ?: Result.success(Unit))
        }
    }
}
