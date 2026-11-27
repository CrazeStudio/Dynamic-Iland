package com.crazestudio.dynamicisland

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Receives every notification and forwards it to the island. */
class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        IslandBus.controller?.onListenerReady()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) IslandBus.controller?.onNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) IslandBus.controller?.onNotificationRemoved(sbn.key)
    }
}
