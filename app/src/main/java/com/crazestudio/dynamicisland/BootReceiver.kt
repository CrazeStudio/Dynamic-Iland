package com.crazestudio.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).enabled && Settings.canDrawOverlays(context)) {
            try {
                context.startForegroundService(Intent(context, IslandService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
