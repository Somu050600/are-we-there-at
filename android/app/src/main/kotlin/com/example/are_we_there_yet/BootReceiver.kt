package com.example.are_we_there_yet

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> noticeInterruptedTrip(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                noticeInterruptedTrip(context)
                val pending = goAsync()
                LegacyGeofences.remove(context) { pending.finish() }
            }
        }
    }

    /**
     * Android does not allow starting location tracking from the background, so
     * ask the user; opening the app resumes the stored trip.
     */
    private fun noticeInterruptedTrip(context: Context) {
        val trip = TripStore(context).active() ?: return
        context.getSystemService(NotificationManager::class.java)
            .notify(TripNotifications.RESTART_ID, TripNotifications.restartNotice(context, trip))
    }
}
