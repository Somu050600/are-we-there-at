package com.example.are_we_there_yet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
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
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val source = FakeLocationSource()
    private val events = mutableListOf<TripSnapshot>()
    private val trip = Trip("a", "Station", 12.9784, 77.5710, 200.0, startDistance = null, paused = false)

    private class FakeLocationSource : LocationSource {
        var listener: ((Fix) -> Unit)? = null
        var unavailable: (() -> Unit)? = null
        var plan: LocationPlan? = null
        val interval get() = plan?.intervalMillis
        private var time = 0L
        override fun start(plan: LocationPlan, onFix: (Fix) -> Unit, onUnavailable: () -> Unit) {
            this.plan = plan
            listener = onFix
            unavailable = onUnavailable
        }
        override fun stop() {
            listener = null
            unavailable = null
            plan = null
        }
        /** Fixes arrive 10 s apart. */
        fun at(lat: Double, lng: Double, speed: Double? = null, accuracy: Double? = null) {
            time += 10_000
            listener!!.invoke(Fix(lat, lng, speed, time, accuracy))
        }
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
    // ~13.5 km north: low-accuracy territory.
    private fun veryFar() = source.at(13.10, 77.5710)

    /** Pending low-power fallback alarms; the service only ever sets one. */
    private fun fallbackAlarms() = shadowOf(alarms).scheduledAlarms.size

    private fun battery(percent: Int, plugged: Int = 0) {
        context.sendBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, percent)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun batteryNotice() = shadowOf(manager).getNotification(TripNotifications.BATTERY_ID)

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
    fun `far away checks less often without GPS`() {
        started()
        veryFar()
        assertEquals(LocationPlan(60_000L, highAccuracy = false), source.plan)
        source.at(12.9955, 77.5710) // ~1.9 km
        assertEquals(LocationPlan(5_000L, highAccuracy = true), source.plan)
    }

    @Test
    fun `quiet low-accuracy updates fall back to GPS for the rest of the trip`() {
        val controller = started()
        assertEquals(0, fallbackAlarms())
        veryFar()
        assertEquals(1, fallbackAlarms())
        veryFar() // each fix replaces the alarm
        assertEquals(1, fallbackAlarms())

        command(controller, TripService.actionIntent(context, TripService.ACTION_LOW_POWER_TIMEOUT))
        assertEquals(LocationPlan(60_000L, highAccuracy = true), source.plan)
        assertEquals(0, fallbackAlarms())
        veryFar()
        assertEquals(LocationPlan(60_000L, highAccuracy = true), source.plan)
    }

    @Test
    fun `unavailable low-accuracy location falls back to GPS at once`() {
        started()
        veryFar()
        source.unavailable!!.invoke()
        assertEquals(LocationPlan(60_000L, highAccuracy = true), source.plan)
    }

    @Test
    fun `unavailable GPS location changes nothing`() {
        started()
        far()
        source.unavailable!!.invoke()
        assertEquals(TripMath.NEAR_PLAN, source.plan)
        veryFar()
        assertEquals(LocationPlan(60_000L, highAccuracy = false), source.plan)
    }

    @Test
    fun `losing the GPS signal shows waiting until the next fix`() {
        started()
        far()
        source.unavailable!!.invoke()
        assertTrue(progressText()!!.startsWith("Waiting for GPS"))
        assertTrue(events.last().waitingForSignal)
        assertNull(events.last().eta)
        near()
        assertTrue(progressText()!!.endsWith("%)"))
        assertFalse(events.last().waitingForSignal)
        assertEquals(events.last().fix!!.timeMillis, events.last().toMap()["fixTimeMillis"])
    }

    @Test
    fun `pause and stop cancel the fallback alarm`() {
        val controller = started()
        veryFar()
        command(controller, TripService.actionIntent(context, TripService.ACTION_PAUSE))
        assertEquals(0, fallbackAlarms())
        command(controller, TripService.actionIntent(context, TripService.ACTION_RESUME))
        veryFar()
        assertEquals(1, fallbackAlarms())
        command(controller, TripService.actionIntent(context, TripService.ACTION_STOP))
        assertEquals(0, fallbackAlarms())
    }

    @Test
    fun `a late fallback alarm without a trip stops the service`() {
        val controller = Robolectric.buildService(TripService::class.java).create()
        command(controller, TripService.actionIntent(context, TripService.ACTION_LOW_POWER_TIMEOUT))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertNull(source.listener)
    }

    @Test
    fun `a low-accuracy fix inside the radius switches to GPS instead of ringing`() {
        started()
        veryFar()
        source.at(12.9785, 77.5711, accuracy = 1_500.0)
        assertEquals(TripStatus.TRACKING, events.last().status)
        assertEquals(TripMath.NEAR_PLAN, source.plan)
        source.at(12.9785, 77.5711, accuracy = 10.0)
        assertEquals(TripStatus.ARRIVED, events.last().status)
    }

    @Test
    fun `low battery warns once per threshold while tracking`() {
        val controller = started()
        battery(50)
        assertNull(batteryNotice())
        battery(19)
        assertEquals("Battery at 19%", batteryNotice()!!.extras.getString(Notification.EXTRA_TITLE))
        manager.cancel(TripNotifications.BATTERY_ID)
        battery(18)
        assertNull(batteryNotice())
        battery(18, plugged = BatteryManager.BATTERY_PLUGGED_AC)
        assertNull(batteryNotice())

        battery(9)
        assertEquals("Battery at 9%", batteryNotice()!!.extras.getString(Notification.EXTRA_TITLE))
        command(controller, TripService.actionIntent(context, TripService.ACTION_STOP))
        assertNull(batteryNotice())
    }

    @Test
    fun `plugging in clears the battery warning`() {
        started()
        battery(15)
        battery(15, plugged = BatteryManager.BATTERY_PLUGGED_USB)
        assertNull(batteryNotice())
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
    fun `moving fixes report bearing, speed and eta`() {
        started()
        source.at(12.9955, 77.5710) // ~1.9 km north
        assertNull(events.last().eta)
        source.at(12.9910, 77.5710) // ~500 m closer, 10 s later
        val moving = events.last()
        assertEquals(180.0, moving.bearing!!, 1.0)
        assertEquals(50.0, moving.speed!!, 2.0)
        assertTrue(moving.eta!! in 20.0..35.0)
        assertEquals(12.9910, moving.fix!!.lat, 1e-9)
        val map = moving.toMap()
        assertEquals(moving.eta, map["etaSeconds"])
        assertEquals(moving.bearing, map["bearingDegrees"])
    }

    @Test
    fun `reported fix speed is preferred over derived speed`() {
        started()
        source.at(12.9955, 77.5710, speed = 12.0)
        assertEquals(12.0, events.last().speed!!, 1e-9)
    }

    @Test
    fun `a reported near-zero speed falls back to the speed between fixes`() {
        started()
        source.at(12.9955, 77.5710, speed = 0.0)
        source.at(12.9910, 77.5710, speed = 0.18)
        assertEquals(50.0, events.last().speed!!, 2.0)
        assertTrue(events.last().eta != null)
    }

    @Test
    fun `fast updates while the direction screen is open`() {
        val controller = started()
        veryFar() // normally 60 s
        assertEquals(60_000L, source.interval)
        command(controller, TripService.fastIntent(context, true))
        assertEquals(TripMath.FAST_PLAN, source.plan)
        source.at(13.09, 77.5710)
        assertEquals(TripMath.FAST_PLAN, source.plan)
        command(controller, TripService.fastIntent(context, false))
        assertEquals(60_000L, source.interval)
    }

    @Test
    fun `fast updates do not wake a paused trip`() {
        val controller = started()
        far()
        command(controller, TripService.actionIntent(context, TripService.ACTION_PAUSE))
        command(controller, TripService.fastIntent(context, true))
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
