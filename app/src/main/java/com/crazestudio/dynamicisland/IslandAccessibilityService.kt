package com.crazestudio.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts the island in an accessibility overlay window. Unlike a normal overlay,
 * this window type is drawn ABOVE the status bar, so the island can sit inside the notch.
 * The service never reads the screen (canRetrieveWindowContent = false).
 */
class IslandAccessibilityService : AccessibilityService() {

    private var controller: IslandController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // the normal-overlay fallback is not needed any more
        stopService(Intent(this, IslandService::class.java))
        syncEnabled()
    }

    /** Start or stop the island according to the master switch. */
    fun syncEnabled() {
        if (Prefs(this).enabled) {
            val c = controller
            if (c != null) {
                c.refreshSettings()
            } else {
                controller = IslandController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also {
                    IslandBus.controller = it
                    it.start()
                }
            }
        } else {
            release()
        }
    }

    private fun release() {
        val c = controller ?: return
        c.stop()
        if (IslandBus.controller === c) IslandBus.controller = null
        controller = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: IslandAccessibilityService? = null
    }
}

object Access {
    /** True when the user has switched the accessibility service on in system settings. */
    fun isEnabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains(ctx.packageName + "/")
    }
}
