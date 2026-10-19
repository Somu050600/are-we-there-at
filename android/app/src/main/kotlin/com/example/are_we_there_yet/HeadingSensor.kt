package com.example.are_we_there_yet

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface

/** Device heading (azimuth, clockwise from north) from the rotation-vector sensor. */
class HeadingSensor(context: Context, private val displayRotation: () -> Int) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private var onHeading: ((Double) -> Unit)? = null
    private var lastEmit = 0L
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    val available get() = sensor != null

    fun start(onHeading: (Double) -> Unit): Boolean {
        val target = sensor ?: return false
        this.onHeading = onHeading
        return manager.registerListener(this, target, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        onHeading = null
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmit < MIN_INTERVAL_MILLIS) return
        lastEmit = now
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val (x, y) = axesFor(displayRotation())
        SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
        SensorManager.getOrientation(remapped, orientation)
        onHeading?.invoke(normalize(Math.toDegrees(orientation[0].toDouble())))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val MIN_INTERVAL_MILLIS = 50L // at most 20 updates a second

        fun normalize(degrees: Double) = ((degrees % 360) + 360) % 360

        /** Keeps "up" on screen as the reference direction in every orientation. */
        fun axesFor(rotation: Int): Pair<Int, Int> = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    }
}
