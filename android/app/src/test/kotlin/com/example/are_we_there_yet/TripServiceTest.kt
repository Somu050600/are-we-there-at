package com.example.are_we_there_yet

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
class TripServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val source = FakeLocationSource()
    private val events = mutableListOf<TripSnapshot>()
    private val trip = Trip("a", "Station", 12.9784, 77.5710, 200.0, startDistance = null, paused = false)

    private class FakeLocationSource : LocationSource {
        var listener: ((Double, Double) -> Unit)? = null
        var interval: Long? = null
        override fun start(intervalMillis: Long, onLocation: (Double, Double) -> Unit) {
            interval = intervalMillis
            listener = onLocation
        }
        override fun stop() {
            listener = null
            interval = null
        }
        fun at(lat: Double, lng: Double) = listener!!.invoke(lat, lng)
    }

    @Before
    fun setUp() {
        context.getSharedPreferences(TripStore.PREFERENCES, 0).edit().clear().commit()
        TripService.sourceFactory = { source }
        TripEvents.listener = { events += it }
    }

    @After
    fun tearDown() {
        TripService.sourceFactory = TripService.DEFAULT_SOURCE
        TripEvents.listener = null
    }

    private fun command(controller: ServiceController<TripService>, intent: Intent?) =
        controller.withIntent(intent).startCommand(0, 1)

    private fun started(): ServiceController<TripService> {
        val controller = Robolectric.buildService(TripService::class.java).create()
        command(controller, TripService.startIntent(context, trip))
        return controller
    }

    private fun progressText(): String? = shadowOf(manager).getNotification(TripNotifications.PROGRESS_ID)
        ?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    // ~1.9 km and ~0.5 km north of the destination.
    private fun far() = source.at(12.9955, 77.5710)
    private fun near() = source.at(12.9829, 77.5710)

    @Test
    fun `start goes foreground, stores the trip and asks for fast first fix`() {
        val controller = started()
        assertEquals(TripNotifications.PROGRESS_ID, shadowOf(controller.get()).lastForegroundNotificationId)
        assertEquals("Calculating… remaining (0%)", progressText())
        assertEquals(trip, TripStore(context).active())
        assertEquals(5_000L, source.interval)
        assertEquals(TripStatus.TRACKING, events.last().status)
    }

    @Test
    fun `fixes record the start distance and report progress`() {
        started()
        far()
        val first = events.last()
        assertEquals(TripStatus.TRACKING, first.status)
        assertEquals(0.0, first.progress!!, 1e-9)
        assertEquals(TripStore(context).active()!!.startDistance!!, first.distance!!, 1e-6)

        near()
        assertTrue(events.last().progress!! > 0.7)
        assertTrue(progressText()!!.endsWith("%)"))
    }

    @Test
    fun `interval slows down when far away`() {
        started()
        source.at(13.10, 77.5710) // ~13 km
        assertEquals(30_000L, source.interval)
        near()
        assertEquals(5_000L, source.interval)
    }

    @Test
    fun `arrival rings once, clears the trip and stops the service`() {
        val controller = started()
        far()
        source.at(12.9785, 77.5711)
        assertEquals(TripStatus.ARRIVED, events.last().status)
        assertNull(TripStore(context).active())
        assertNull(source.listener)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        val alarm = shadowOf(manager).getNotification(TripNotifications.ARRIVAL_ID)
        assertEquals("Arrived at Station", alarm.extras.getString(Notification.EXTRA_TITLE))
        assertNull(shadowOf(manager).getNotification(TripNotifications.PROGRESS_ID))
    }

    @Test
    fun `pause stops location and resume restarts it`() {
        val controller = started()
        far()
        command(controller, TripService.actionIntent(context, TripService.ACTION_PAUSE))
        assertNull(source.listener)
        assertTrue(TripStore(context).active()!!.paused)
        assertEquals(TripStatus.PAUSED, events.last().status)
        assertTrue(progressText()!!.startsWith("Paused"))

        command(controller, TripService.actionIntent(context, TripService.ACTION_RESUME))
        assertFalse(TripStore(context).active()!!.paused)
        assertEquals(TripStatus.TRACKING, events.last().status)
        near()
        assertEquals(TripStatus.TRACKING, events.last().status)
    }

    @Test
    fun `stop clears everything`() {
        val controller = started()
        command(controller, TripService.actionIntent(context, TripService.ACTION_STOP))
        assertNull(TripStore(context).active())
        assertNull(source.listener)
        assertEquals(TripStatus.IDLE, events.last().status)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `starting another destination replaces the trip`() {
        val controller = started()
        far()
        val other = trip.copy(id = "b", name = "Office")
        command(controller, TripService.startIntent(context, other))
        assertEquals("b", TripStore(context).active()!!.id)
        assertNull(TripStore(context).active()!!.startDistance)
        assertEquals("b", events.last().trip!!.id)
    }

    @Test
    fun `system restart without intent resumes a stored trip`() {
        TripStore(context).saveActive(trip.copy(startDistance = 1_000.0))
        val controller = Robolectric.buildService(TripService::class.java).create()
        command(controller, null)
        assertEquals(5_000L, source.interval)
        near()
        assertEquals(TripStatus.TRACKING, events.last().status)
    }

    @Test
    fun `system restart without a stored trip stops`() {
        val controller = Robolectric.buildService(TripService::class.java).create()
        command(controller, null)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertNull(source.listener)
    }

    @Test
    fun `boot turns an interrupted trip into a resume notice`() {
        TripStore(context).saveActive(trip)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        // Kept so that opening the app from the notice resumes it.
        assertEquals(trip, TripStore(context).active())
        val notice = shadowOf(manager).getNotification(TripNotifications.RESTART_ID)
        assertEquals(
            "Trip to Station stopped by restart. Tap to resume.",
            notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun `explicit recover resumes the stored trip`() {
        TripStore(context).saveActive(trip.copy(startDistance = 1_000.0))
        val controller = Robolectric.buildService(TripService::class.java).create()
        assertTrue(TripService.isRunning)
        command(controller, TripService.actionIntent(context, TripService.ACTION_RECOVER))
        near()
        assertEquals(TripStatus.TRACKING, events.last().status)
        controller.destroy()
        assertFalse(TripService.isRunning)
    }

    @Test
    fun `resuming a trip clears the restart notice`() {
        TripStore(context).saveActive(trip)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val controller = Robolectric.buildService(TripService::class.java).create()
        command(controller, TripService.actionIntent(context, TripService.ACTION_RECOVER))
        assertNull(shadowOf(manager).getNotification(TripNotifications.RESTART_ID))
    }

    @Test
    fun `boot without a trip posts nothing`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(manager).getNotification(TripNotifications.RESTART_ID))
    }
}
