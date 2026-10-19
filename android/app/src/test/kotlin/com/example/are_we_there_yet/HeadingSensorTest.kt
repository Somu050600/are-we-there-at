package com.example.are_we_there_yet

import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingSensorTest {
    @Test
    fun `headings are normalized to 0 until 360`() {
        assertEquals(350.0, HeadingSensor.normalize(-10.0), 1e-9)
        assertEquals(10.0, HeadingSensor.normalize(370.0), 1e-9)
        assertEquals(0.0, HeadingSensor.normalize(360.0), 1e-9)
    }

    @Test
    fun `axes follow the display rotation`() {
        assertEquals(SensorManager.AXIS_X to SensorManager.AXIS_Y, HeadingSensor.axesFor(Surface.ROTATION_0))
        assertEquals(SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X, HeadingSensor.axesFor(Surface.ROTATION_90))
    }

    @Test
    fun `upright means pitched more than 45 degrees`() {
        assertEquals(true, HeadingSensor.isUpright(Math.toRadians(-80.0)))
        assertEquals(false, HeadingSensor.isUpright(Math.toRadians(-20.0)))
    }
}
