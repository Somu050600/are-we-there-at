package com.example.are_we_there_yet

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlarmStoreTest {
    private lateinit var store: AlarmStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(AlarmStore.PREFERENCES, 0).edit().clear().commit()
        store = AlarmStore(context)
    }

    @Test
    fun `active alarms survive a new store instance with their destination name`() {
        val expected = AlarmRecord("train", 51.5, -0.12, 250f, "Waterloo")
        store.saveActive(expected)

        val reloaded = AlarmStore(ApplicationProvider.getApplicationContext<android.content.Context>()).snapshot()

        assertEquals(listOf(expected), reloaded.active)
        assertTrue(reloaded.pendingRemovals.isEmpty())
    }

    @Test
    fun `begin removal immediately suppresses a stale transition and is durable`() {
        store.saveActive(AlarmRecord("old", 1.0, 2.0, 100f, "Old place"))
        store.beginRemoval("old")

        val reloaded = AlarmStore(ApplicationProvider.getApplicationContext<android.content.Context>())

        assertFalse(reloaded.isNotificationAllowed("old"))
        assertEquals(setOf("old"), reloaded.snapshot().pendingRemovals)
        assertTrue(reloaded.snapshot().active.isEmpty())
    }

    @Test
    fun `successful reactivation clears an earlier removal tombstone`() {
        store.beginRemoval("same")
        store.saveActive(AlarmRecord("same", 3.0, 4.0, 120f, "Again"))

        assertTrue(store.isNotificationAllowed("same"))
        assertTrue(store.snapshot().pendingRemovals.isEmpty())
    }

    @Test
    fun `a failed durable commit is reported to the caller`() {
        val failing = object : AlarmPersistence {
            override fun activeJson() = "[]"
            override fun pendingRemovals() = emptySet<String>()
            override fun commit(activeJson: String, pendingRemovals: Set<String>) = false
        }
        val failingStore = AlarmStore(failing)

        assertThrows(AlarmStoreException::class.java) {
            failingStore.saveActive(AlarmRecord("lost", 1.0, 2.0, 100f, "Lost"))
        }
    }
}
