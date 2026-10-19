package com.example.are_we_there_yet

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Android does not allow starting location tracking from boot, so ask the user.
            Intent.ACTION_BOOT_COMPLETED -> {
                val store = TripStore(context)
                val trip = store.active() ?: return
                store.clearActive()
                context.getSystemService(NotificationManager::class.java)
                    .notify(TripNotifications.RESTART_ID, TripNotifications.restartNotice(context, trip))
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                LegacyGeofences.remove(context) { pending.finish() }
            }
        }
    }
}
