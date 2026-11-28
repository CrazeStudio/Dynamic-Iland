package com.crazestudio.dynamicisland

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.SystemClock

/**
 * A button on the island. Either fires a [PendingIntent] (real notification actions),
 * runs [onClick] (built-in buttons), or both. [closes] = hide the card afterwards.
 */
class Act(
    val title: String,
    val intent: PendingIntent?,
    val onClick: (() -> Unit)? = null,
    val closes: Boolean = true
)

/** Short-lived things the island announces: notifications, calls, charging. */
sealed class Live(val key: String) {
    class Notif(
        key: String,
        val appName: String,
        val icon: Drawable?,
        val avatar: Drawable?,
        val title: String,
        val text: String,
        val progress: Int,
        val progressMax: Int,
        val indeterminate: Boolean,
        val contentIntent: PendingIntent?,
        val actions: List<Act>,
        val isCall: Boolean
    ) : Live(key) {
        val hasProgress: Boolean get() = progressMax > 0 || indeterminate
    }

    class Charging(val percent: Int, val low: Boolean) : Live("charging")
}

class MediaInfo(
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val duration: Long,
    private val basePos: Long,
    private val baseTime: Long,
    private val speed: Float,
    val controller: MediaController
) {
    fun position(): Long {
        val p = if (playing) basePos + ((SystemClock.elapsedRealtime() - baseTime) * speed).toLong() else basePos
        return if (duration > 0) p.coerceIn(0L, duration) else p.coerceAtLeast(0L)
    }
}
