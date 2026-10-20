package com.example.are_we_there_yet

import android.content.Intent
import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatteryWatchTest {
    private fun on(percent: Int) = BatteryLevel(percent, charging = false)

    @Test
    fun `warns once per threshold on the way down`() {
        val watch = BatteryWatch()
        assertNull(watch.update(on(50)))
        assertEquals(20, watch.update(on(20)))
        assertNull(watch.update(on(19)))
        assertNull(watch.update(on(11)))
        assertEquals(10, watch.update(on(10)))
        assertNull(watch.update(on(5)))
    }

    @Test
    fun `an already low battery warns about the lowest threshold only`() {
        val watch = BatteryWatch()
        assertEquals(10, watch.update(on(8)))
        assertNull(watch.update(on(7)))
    }

    @Test
    fun `charging re-arms the warnings`() {
        val watch = BatteryWatch()
        assertEquals(20, watch.update(on(18)))
        assertNull(watch.update(BatteryLevel(18, charging = true)))
        assertEquals(20, watch.update(on(18)))
    }

    @Test
    fun `reads level, scale and plug state from the broadcast`() {
        fun intent(level: Int, scale: Int, plugged: Int = 0) = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
        assertEquals(BatteryLevel(15, charging = false), BatteryLevel.fromIntent(intent(30, 200)))
        assertEquals(
            BatteryLevel(15, charging = true),
            BatteryLevel.fromIntent(intent(15, 100, BatteryManager.BATTERY_PLUGGED_USB)),
        )
        assertNull(BatteryLevel.fromIntent(Intent(Intent.ACTION_BATTERY_CHANGED)))
    }
}
