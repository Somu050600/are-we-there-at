package com.example.are_we_there_yet

import android.content.Intent
import android.os.BatteryManager

/** Battery state from an `ACTION_BATTERY_CHANGED` broadcast. */
data class BatteryLevel(val percent: Int, val charging: Boolean) {
    companion object {
        fun fromIntent(intent: Intent): BatteryLevel? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return BatteryLevel(level * 100 / scale, charging)
        }
    }
}

/**
 * Decides when to warn that the battery may die before arrival: once per
 * threshold on the way down, re-armed when the phone is plugged in.
 */
class BatteryWatch(private val thresholds: List<Int> = listOf(20, 10)) {
    private var warnedAt: Int? = null

    /** The threshold to warn about now, or null. */
    fun update(level: BatteryLevel): Int? {
        if (level.charging) {
            warnedAt = null
            return null
        }
        val crossed = thresholds.filter { level.percent <= it }.minOrNull() ?: return null
        if (warnedAt?.let { crossed >= it } == true) return null
        warnedAt = crossed
        return crossed
    }
}
