package com.example.are_we_there_yet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        NativeOperationQueue.shared.submit(
            onCancelled = { pending.finish() },
            onFailure = { failure ->
                Log.e("BootReceiver", "Could not start geofence restoration", failure)
                pending.finish()
            },
        ) { done ->
            GeofenceReconciler(AlarmStore(context), GeofenceManager(context)).restore { result ->
                result.exceptionOrNull()?.let { Log.e("BootReceiver", "Could not restore geofences", it) }
                done()
                pending.finish()
            }
        }
    }
}
