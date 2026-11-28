package com.crazestudio.dynamicisland

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Receives every notification and forwards it to the island. */
class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        IslandBus.controller?.onListenerReady()
    }

    /** The system can drop the listener after long inactivity: ask to be bound again. */
    override fun onListenerDisconnected() {
        try {
            requestRebind(ComponentName(this, IslandNotificationListener::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) IslandBus.controller?.onNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) IslandBus.controller?.onNotificationRemoved(sbn.key)
    }
}
