package com.crazestudio.dynamicisland

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Owns the overlay window and decides what the island shows.
 *
 * Priority: call > notification / charging (queued) > media.
 * Media sits in the compact state while nothing else is happening.
 */
class IslandController(private val ctx: Context) : ContentFactory.Callbacks {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private val h = Handler(Looper.getMainLooper())
    private val view = IslandView(ctx)

    private var attached = false
    private var idle = true
    private var notch = Notch.detect(ctx, prefs)
    private var factory = ContentFactory(ctx, notch, this)

    private var media: MediaInfo? = null
    private var mediaSnoozed = false
    private var mediaUi: ContentFactory.MediaUi? = null
    private var tracker: MediaTracker? = null

    private var current: Live? = null
    private val pending = ArrayDeque<Live>()
    private val silenced = HashSet<String>()
    private var expanded = false
    private var focusMedia = false
    private var fsHidden = false

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        view.listener = object : IslandView.Listener {
            override fun onTap() = handleTap()
            override fun onLongPress() = handleLongPress()
            override fun onSwipeUp() = handleSwipeUp()
            override fun onSwipe(dir: Int) = handleSwipe()
        }
        view.setOnApplyWindowInsetsListener { _, insets ->
            onInsets(insets)
            insets
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        tracker = MediaTracker(ctx) { onMedia(it) }
        if (prefs.showMedia) tracker?.start()
        h.post(ticker)
        render(false)
    }

    fun stop() {
        h.removeCallbacksAndMessages(null)
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        tracker?.stop()
        tracker = null
        detachNow()
    }

    fun onListenerReady() {
        if (prefs.showMedia) tracker?.start()
    }

    fun onConfigChanged() {
        refreshSettings()
    }

    /** Called after a setting or the screen configuration changed. */
    fun refreshSettings() {
        notch = Notch.detect(ctx, prefs)
        factory = ContentFactory(ctx, notch, this)
        if (prefs.showMedia) tracker?.start() else {
            tracker?.stop()
            media = null
        }
        if (!prefs.showNotifications && current is Live.Notif) finishCurrent(false)
        if (attached) updateWindow()
        render(false)
    }

    // ------------------------------------------------------------------ events in

    @Suppress("DEPRECATION")
    fun onNotification(sbn: StatusBarNotification) {
        if (!prefs.showNotifications) return
        val pkg = sbn.packageName
        if (pkg == ctx.packageName || pkg in prefs.blocked) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if (n.priority <= Notification.PRIORITY_MIN) return

        val e = n.extras ?: return
        val isCall = n.category == Notification.CATEGORY_CALL
        val max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val prog = e.getInt(Notification.EXTRA_PROGRESS, 0)
        val indet = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val inProgress = (max > 0 && prog < max) || indet
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (ongoing && !isCall && !inProgress) return

        val title = (e.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        if (title.isBlank() && text.isBlank()) return

        val key = sbn.key
        if (inProgress && key in silenced && current?.key != key) return

        val (label, icon) = appInfo(pkg, e)
        val avatar: Drawable? = try {
            n.getLargeIcon()?.loadDrawable(ctx)
        } catch (_: Exception) {
            null
        }
        val actions = (n.actions ?: emptyArray())
            .filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .map { Act(it.title.toString(), it.actionIntent) }

        post(
            Live.Notif(
                key = key, appName = label, icon = icon, avatar = avatar,
                title = title, text = text,
                progress = if (inProgress) prog else 0,
                progressMax = if (inProgress) max else 0,
                indeterminate = inProgress && indet,
                contentIntent = n.contentIntent, actions = actions, isCall = isCall
            )
        )
    }

    fun onNotificationRemoved(key: String) {
        silenced.remove(key)
        pending.removeAll { it.key == key }
        if (current?.key == key) finishCurrent(true)
    }

    private fun onMedia(info: MediaInfo?) {
        val old = media
        media = info
        if (info == null || old == null || old.title != info.title || (info.playing && !old.playing)) {
            mediaSnoozed = false
        }
        if (info == null) {
            focusMedia = false
            if (expanded && current == null) expanded = false
        }
        scheduleTimeout()
        render()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> if (prefs.showCharging) post(Live.Charging(batteryLevel(), false))
                Intent.ACTION_POWER_DISCONNECTED -> {
                    pending.removeAll { it is Live.Charging }
                    if (current is Live.Charging) finishCurrent(true)
                }
                Intent.ACTION_BATTERY_LOW -> if (prefs.showCharging) post(Live.Charging(batteryLevel(), true))
            }
        }
    }

    // ------------------------------------------------------------------ queue

    private fun Live.isCall() = this is Live.Notif && this.isCall

    private fun post(t: Live) {
        val cur = current
        if (cur != null && cur.key == t.key) {
            current = t
        } else if (t.isCall()) {
            if (cur != null && !cur.isCall()) pending.addFirst(cur)
            current = t
            expanded = (t as Live.Notif).actions.size >= 2
            focusMedia = false
            buzz()
        } else if (cur == null) {
            current = t
            expanded = false
            focusMedia = false
            buzz()
        } else {
            pending.removeAll { it.key == t.key }
            pending.addLast(t)
            return
        }
        scheduleTimeout()
        render()
    }

    private fun finishCurrent(animate: Boolean) {
        (current as? Live.Notif)?.let { if (it.hasProgress) silenced.add(it.key) }
        current = pending.removeFirstOrNull()
        expanded = current?.isCall() == true && (current as Live.Notif).actions.size >= 2
        scheduleTimeout()
        render(animate)
    }

    private val timeout = Runnable {
        if (mediaShown() && expanded) {
            expanded = false
            render()
        } else if (current != null) {
            finishCurrent(true)
        }
    }

    private fun scheduleTimeout() {
        h.removeCallbacks(timeout)
        val t = current
        if (t != null && t.isCall()) return
        if (mediaShown() && expanded) {
            h.postDelayed(timeout, 8000)
        } else if (t != null) {
            val secs = prefs.autoDismissSec.coerceIn(2, 30)
            h.postDelayed(timeout, (if (expanded) secs * 3 else secs) * 1000L)
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun activeMedia(): MediaInfo? {
        val m = media ?: return null
        if (!prefs.showMedia || mediaSnoozed) return null
        return if (current == null || focusMedia) m else null
    }

    private fun mediaShown(): Boolean {
        val m = activeMedia() ?: return false
        return expanded || m.playing
    }

    private val detachRunnable = Runnable { if (idle) detachNow() }

    private fun render(animate: Boolean = true) {
        h.removeCallbacks(detachRunnable)
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape && prefs.hideInLandscape) {
            idle = true
            detachNow()
            return
        }

        val m = activeMedia()
        val t = current
        val built: ContentFactory.Built? = when {
            m != null && (expanded || m.playing) ->
                if (expanded) factory.mediaExpanded(m) else factory.mediaCompact(m)
            t != null -> if (expanded) factory.liveExpanded(t) else factory.livePeek(t)
            else -> null
        }
        mediaUi = built?.ui

        if (built == null) {
            goIdle(animate)
            return
        }
        idle = false
        val first = !attached
        attach()
        if (!attached) return
        if (first) view.snapTo(idleW(), idleH(), idleH() / 2f)
        view.show(built.view, built.w, built.h, built.radius, animate && !first || first)
    }

    private fun goIdle(animate: Boolean) {
        idle = true
        mediaUi = null
        if (!attached) {
            if (prefs.alwaysShowPill) {
                attach()
                if (attached) {
                    view.snapTo(idleW(), idleH(), idleH() / 2f)
                    view.show(null, idleW(), idleH(), idleH() / 2f, false)
                }
            }
            return
        }
        view.show(null, idleW(), idleH(), idleH() / 2f, animate)
        if (!prefs.alwaysShowPill) h.postDelayed(detachRunnable, 420)
    }

    private fun idleW() = if (prefs.idleWidth > 0) ctx.dp(prefs.idleWidth) else maxOf(notch.w, ctx.dp(28))
    private fun idleH() = if (prefs.idleHeight > 0) ctx.dp(prefs.idleHeight) else maxOf(notch.h, ctx.dp(24))

    // ------------------------------------------------------------------ window

    private fun layoutParams(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (fsHidden) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = notch.cx + ctx.dp(prefs.offsetX)
            y = ctx.dp(prefs.offsetY)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "DynamicIsland"
        }
    }

    private fun attach() {
        if (attached) return
        try {
            view.alpha = if (fsHidden) 0f else 1f
            wm.addView(view, layoutParams())
            attached = true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot add overlay (permission missing?)", e)
        }
    }

    private fun detachNow() {
        if (!attached) return
        try {
            wm.removeViewImmediate(view)
        } catch (_: Exception) {
        }
        view.clear()
        attached = false
    }

    private fun updateWindow() {
        if (!attached) return
        try {
            view.alpha = if (fsHidden) 0f else 1f
            wm.updateViewLayout(view, layoutParams())
        } catch (_: Exception) {
        }
    }

    /** Experimental: hide while the status bar is hidden (fullscreen apps). Android 11+. */
    private fun onInsets(i: WindowInsets) {
        if (Build.VERSION.SDK_INT < 30) return
        val hidden = prefs.hideInFullscreen && !i.isVisible(WindowInsets.Type.statusBars())
        if (hidden != fsHidden) {
            fsHidden = hidden
            h.post { updateWindow() }
        }
    }

    // ------------------------------------------------------------------ gestures

    private fun handleTap() {
        if (!expanded) {
            if (current != null || mediaShown()) setExpanded(true)
            return
        }
        val t = current
        if (mediaShown()) {
            openPi(media?.controller?.sessionActivity)
            setExpanded(false)
        } else if (t is Live.Notif && !t.isCall) {
            runAction(t.contentIntent, true)
        } else {
            setExpanded(false)
        }
    }

    private fun handleLongPress() {
        if (expanded) {
            setExpanded(false)
            return
        }
        val t = current
        if (mediaShown()) openPi(media?.controller?.sessionActivity)
        else if (t is Live.Notif) runAction(t.contentIntent, true)
    }

    private fun handleSwipeUp() {
        when {
            expanded -> setExpanded(false)
            mediaShown() -> {
                mediaSnoozed = true
                render()
            }
            current != null -> finishCurrent(true)
        }
    }

    private fun handleSwipe() {
        if (media != null && current != null) {
            focusMedia = !focusMedia
            expanded = false
            scheduleTimeout()
            render()
        } else if (pending.isNotEmpty()) {
            finishCurrent(true)
        }
    }

    private fun setExpanded(v: Boolean) {
        expanded = v
        if (v) buzz()
        scheduleTimeout()
        render()
    }

    // ------------------------------------------------------------------ actions

    override fun runAction(pi: PendingIntent?, finish: Boolean) {
        openPi(pi)
        val t = current
        if (finish && t != null && !mediaShown()) finishCurrent(true) else if (finish) setExpanded(false)
    }

    override fun mediaCmd(cmd: Int) {
        val m = media ?: return
        val c = m.controller.transportControls
        when (cmd) {
            0 -> c.skipToPrevious()
            1 -> if (m.playing) c.pause() else c.play()
            2 -> c.skipToNext()
        }
        scheduleTimeout()
    }

    private fun openPi(pi: PendingIntent?) {
        if (pi == null) return
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
                pi.send(ctx, 0, null, null, null, null, opts)
            } else {
                pi.send()
            }
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent failed", e)
        }
    }

    private fun buzz() {
        if (prefs.haptics && attached) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ------------------------------------------------------------------ helpers

    private val ticker = object : Runnable {
        override fun run() {
            mediaUi?.let { u ->
                val m = u.info
                u.progress.progress = if (m.duration > 0) (m.position() * 1000 / m.duration).toInt() else 0
            }
            h.postDelayed(this, 500)
        }
    }

    private fun batteryLevel(): Int {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level < 0 || scale <= 0) 0 else level * 100 / scale
    }

    @Suppress("DEPRECATION")
    private fun appInfo(pkg: String, extras: Bundle): Pair<String, Drawable?> {
        val pm = ctx.packageManager
        return try {
            val ai = extras.getParcelable<ApplicationInfo>("android.appInfo")
            if (ai != null) {
                Pair(pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
            } else {
                Pair(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), pm.getApplicationIcon(pkg))
            }
        } catch (_: Exception) {
            Pair(pkg, null)
        }
    }

    /** Used by the settings screen's preview buttons. */
    fun demo(kind: String) {
        val icon = try {
            ctx.packageManager.getApplicationIcon(ctx.packageName)
        } catch (_: Exception) {
            null
        }
        when (kind) {
            "notif" -> post(
                Live.Notif(
                    "demo", "Dynamic Island", icon, null, "Hello from your island",
                    "Tap to expand. Swipe up to dismiss, swipe sideways to switch activities.",
                    0, 0, false, null, listOf(Act("Open", null), Act("Later", null)), false
                )
            )
            "call" -> {
                post(
                    Live.Notif(
                        "demo_call", "Phone", icon, null, "Craze Studio", "Incoming call",
                        0, 0, false, null, listOf(Act("Decline", null), Act("Answer", null)), true
                    )
                )
                h.postDelayed({ if (current?.key == "demo_call") finishCurrent(true) }, 8000)
            }
            "charge" -> post(Live.Charging(batteryLevel(), false))
            "progress" -> post(
                Live.Notif(
                    "demo_progress", "Downloads", icon, null, "Downloading update", "72% complete",
                    72, 100, false, null, emptyList(), false
                )
            )
        }
    }

    companion object {
        private const val TAG = "DynamicIsland"
    }
}
