package com.example.are_we_there_yet

import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen arrival alert shown over the lock screen. */
class ArrivalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val name = intent.getStringExtra(TripNotifications.EXTRA_NAME) ?: "your destination"
        val headline = intent.getStringExtra(TripNotifications.EXTRA_HEADLINE) ?: "Arrived"
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#1B1D22"))
                setPadding(dp(32), dp(32), dp(32), dp(32))
                addView(label(headline, 20f, "#B9C3DB"))
                addView(label(name, 34f, "#FFFFFF"))
                addView(
                    Button(context).apply {
                        text = "Stop alarm"
                        textSize = 20f
                        isAllCaps = false
                        setTextColor(Color.parseColor("#1B1D22"))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(36).toFloat()
                            setColor(Color.parseColor("#B9C3DB"))
                        }
                        setOnClickListener { stopAlarm(context); finish() }
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(48) },
                )
            },
        )
    }

    private fun label(value: String, size: Float, color: String) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.parseColor(color))
        gravity = Gravity.CENTER
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun stopAlarm(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(TripNotifications.ARRIVAL_ID)
        }
    }
}

class ArrivalStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ArrivalActivity.stopAlarm(context)
}
