package com.crazestudio.dynamicisland

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("island_prefs", Context.MODE_PRIVATE)

    var enabled by bool("enabled", false)
    var showNotifications by bool("show_notifications", true)
    var showMedia by bool("show_media", true)
    var showCharging by bool("show_charging", true)
    var haptics by bool("haptics", true)
    var hideInLandscape by bool("hide_landscape", true)
    var hideInFullscreen by bool("hide_fullscreen", false)
    var alwaysShowPill by bool("always_pill", false)

    /** which side of the camera the activity dot sits on */
    var dotRight by bool("dot_right", false)

    /** seconds a notification peek stays visible */
    var autoDismissSec by int("auto_dismiss", 5)

    /** manual calibration, in dp */
    var offsetX by int("offset_x", 0)
    var offsetY by int("offset_y", 0)
    var idleWidth by int("idle_w", 0)   // 0 = auto (notch width)
    var idleHeight by int("idle_h", 0)  // 0 = auto (notch height)

    /** cutout detected by the settings screen (px), used on Android 10 */
    var cutW by int("cut_w", 0)
    var cutH by int("cut_h", 0)
    var cutCx by int("cut_cx", 0)

    var blocked: Set<String>
        get() = sp.getStringSet("blocked", DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED
        set(v) {
            sp.edit().putStringSet("blocked", v).apply()
        }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    companion object {
        val DEFAULT_BLOCKED = setOf("android", "com.android.systemui")
    }
}
