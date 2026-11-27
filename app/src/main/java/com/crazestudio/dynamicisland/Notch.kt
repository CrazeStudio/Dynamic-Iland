package com.crazestudio.dynamicisland

import android.content.Context
import android.os.Build
import android.view.WindowManager

/** Finds the size and position of the camera cutout. All values in px. */
object Notch {
    /** w, h = cutout size; cx = horizontal offset of the cutout centre from the screen centre. */
    class Info(val w: Int, val h: Int, val cx: Int)

    fun detect(ctx: Context, prefs: Prefs): Info {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = wm.currentWindowMetrics
                val r = metrics.windowInsets.displayCutout?.boundingRectTop
                if (r != null && !r.isEmpty) {
                    return Info(r.width(), r.height(), r.centerX() - metrics.bounds.width() / 2)
                }
            } catch (_: Throwable) {
            }
        }
        if (prefs.cutW > 0 && prefs.cutH > 0) return Info(prefs.cutW, prefs.cutH, prefs.cutCx)
        return Info(ctx.dp(84), statusBarHeight(ctx), 0)
    }

    private fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else ctx.dp(24)
    }
}
