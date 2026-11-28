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
import android.graphics.Color
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
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Owns the overlay window and decides what the island shows.
 *
 * [windowType] TYPE_ACCESSIBILITY_OVERLAY (preferred) draws above the status bar, inside the notch.
 * TYPE_APPLICATION_OVERLAY (fallback) is drawn below the status bar.
 *
 * Flow for anything that keeps running (music, downloads, charging):
 *   peek  ->  (after the auto-dismiss time)  ->  small coloured dot in the notch
 *   tap the dot to expand it again. When the activity ends, the dot disappears.
 * One-off things (messages) just peek and then hide in the notch.
 */
class IslandController(
    private val ctx: Context,
    private val windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
) : ContentFactory.Callbacks {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private val h = Handler(Looper.getMainLooper())
    private val view = IslandView(ctx)

    private var attached = false
    private var idle = true
    private var dotMode = false
    private var shownDot: Int? = null
    private var notch = Notch.detect(ctx, prefs)
    private var factory = ContentFactory(ctx, notch, this)

    private var media: MediaInfo? = null
    private var mediaSnoozed = false      // dismissed by the user
    private var mediaCollapsed = false    // shrunk to a dot after the timeout
    private var mediaUi: ContentFactory.MediaUi? = null
    private var tracker: MediaTracker? = null

    private var current: Live? = null
    private val pending = ArrayDeque<Live>()
    /** Ongoing activities (downloads, charging) that are shown as a dot. */
    private val background = LinkedHashMap<String, Live>()
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
        // already plugged in when the island starts: show the charging dot
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (prefs.showCharging && (bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) {
            background[CHARGING_KEY] = Live.Charging(batteryLevel(), false)
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
        if (!prefs.showNotifications) {
            background.values.removeAll { it is Live.Notif }
            if (current is Live.Notif) finishCurrent(false, false)
        }
        if (!prefs.showCharging) background.remove(CHARGING_KEY)
        shownDot = null
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
                key = sbn.key, appName = label, icon = icon, avatar = avatar,
                title = title, text = text,
                progress = if (inProgress) prog else 0,
                progressMax = if (inProgress) max else 0,
                indeterminate = inProgress && indet,
                contentIntent = n.contentIntent, actions = actions, isCall = isCall
            )
        )
    }

    fun onNotificationRemoved(key: String) {
        background.remove(key)
        pending.removeAll { it.key == key }
        if (current?.key == key) finishCurrent(true, false) else if (idle) render()
    }

    private fun onMedia(info: MediaInfo?) {
        val old = media
        media = info
        if (info == null || old == null || old.title != info.title || (info.playing && !old.playing)) {
            mediaSnoozed = false
            mediaCollapsed = false
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
                    background.remove(CHARGING_KEY)
                    pending.removeAll { it is Live.Charging }
                    if (current is Live.Charging) finishCurrent(true, false) else if (idle) render()
                }
                Intent.ACTION_BATTERY_LOW -> if (prefs.showCharging) post(Live.Charging(batteryLevel(), true))
            }
        }
    }

    // ------------------------------------------------------------------ queue

    private fun Live.isCall() = this is Live.Notif && this.isCall

    /** Things that keep running and should stay visible as a dot. */
    private fun isOngoing(t: Live) = (t is Live.Notif && t.hasProgress) || (t is Live.Charging && !t.low)

    private fun post(t: Live) {
        // an activity that is already running as a dot: update it quietly
        if (background.containsKey(t.key)) {
            if (isOngoing(t)) {
                background[t.key] = t
                return
            }
            background.remove(t.key)
        }
        val cur = current
        if (cur != null && cur.key == t.key) {
            current = t
        } else if (t.isCall()) {
            if (cur != null && !cur.isCall()) pending.addFirst(cur)
            current = t
            expanded = (t as Live.Notif).actions.isNotEmpty()
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

    private fun finishCurrent(animate: Boolean, keep: Boolean = true) {
        val c = current
        if (keep && c != null && isOngoing(c)) background[c.key] = c
        current = pending.removeFirstOrNull()
        expanded = current?.isCall() == true && (current as Live.Notif).actions.isNotEmpty()
        scheduleTimeout()
        render(animate)
    }

    /** Fires after the auto-dismiss time. */
    private val timeout = Runnable {
        when {
            mediaShown() && expanded -> {
                expanded = false
                scheduleTimeout()
                render()
            }
            mediaShown() -> {
                mediaCollapsed = true // music keeps playing: become a dot
                render()
            }
            current != null -> finishCurrent(true) // ongoing ones become a dot, others hide
        }
    }

    private fun scheduleTimeout() {
        h.removeCallbacks(timeout)
        val t = current
        if (t != null && t.isCall()) return
        val secs = prefs.autoDismissSec.coerceIn(2, 30)
        if (mediaShown()) {
            h.postDelayed(timeout, if (expanded) 8000L else secs * 1000L)
        } else if (t != null) {
            h.postDelayed(timeout, (if (expanded) secs * 3 else secs) * 1000L)
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun activeMedia(): MediaInfo? {
        val m = media ?: return null
        if (!prefs.showMedia || mediaSnoozed) return null
        return if (current == null || focusMedia) m else null
    }

    /** True while the media layout (compact or expanded) is on screen. */
    private fun mediaShown(): Boolean {
        val m = activeMedia() ?: return false
        return expanded || (m.playing && !mediaCollapsed)
    }

    private val moveRunnable = Runnable { if (idle) updateWindow() }

    private val detachRunnable = Runnable {
        if (idle) {
            view.animate().alpha(0f).setDuration(220).withEndAction {
                if (idle && view.alpha < 0.05f) detachNow()
            }.start()
        }
    }

    private fun render(animate: Boolean = true) {
        h.removeCallbacks(detachRunnable)
        h.removeCallbacks(moveRunnable)
        if (attached) {
            view.animate().cancel()
            view.alpha = if (fsHidden) 0f else 1f
        }
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape && prefs.hideInLandscape) {
            idle = true
            dotMode = false
            detachNow()
            return
        }

        val m = activeMedia()
        val t = current
        val built: ContentFactory.Built? = when {
            m != null && (expanded || (m.playing && !mediaCollapsed)) ->
                if (expanded) factory.mediaExpanded(m) else factory.mediaCompact(m)
            t != null -> if (expanded) factory.liveExpanded(t) else factory.livePeek(t)
            else -> null
        }
        mediaUi = built?.ui

        if (built == null) {
            goIdle(animate)
            return
        }
        val wasIdle = idle
        idle = false
        dotMode = false
        shownDot = null
        val first = !attached
        attach()
        if (!attached) return
        view.flatTop = prefs.offsetY == 0 // hug the top edge so it merges with the notch
        if (first) view.snapTo(dotSize(), dotSize(), dotSize() / 2f)
        else if (wasIdle) updateWindow() // slide back out of the notch
        view.show(built.view, built.w, built.h, built.radius, true)
    }

    private fun dotColor(): Int? {
        val bgItem = background.values.lastOrNull()
        if (bgItem != null) return if (bgItem is Live.Charging) GREEN else BLUE
        val m = activeMedia()
        if (m != null && m.playing) return PINK
        return null
    }

    private fun dotBuilt(color: Int): ContentFactory.Built {
        val s = dotBox()
        val box = FrameLayout(ctx)
        box.addView(
            View(ctx).apply { background = roundBg(color, ctx.dpf(8f)) },
            FrameLayout.LayoutParams(ctx.dp(10), ctx.dp(10), Gravity.CENTER)
        )
        return ContentFactory.Built(box, s, s, s / 2f)
    }

    /** Nothing to announce: show a dot if something is still running, otherwise hide in the notch. */
    private fun goIdle(animate: Boolean) {
        idle = true
        mediaUi = null
        val color = dotColor()
        dotMode = color != null

        if (!attached) {
            if (color != null) {
                attach()
                if (attached) {
                    val b = dotBuilt(color)
                    view.flatTop = false
                    view.snapTo(b.w, b.h, b.radius)
                    view.show(b.view, b.w, b.h, b.radius, false)
                    shownDot = color
                }
            } else if (prefs.alwaysShowPill) {
                attach()
                if (attached) {
                    view.flatTop = prefs.offsetY == 0
                    view.snapTo(idleW(), idleH(), idleH() / 2f)
                    view.show(null, idleW(), idleH(), idleH() / 2f, false)
                }
            }
            return
        }

        if (color != null) {
            if (shownDot == color) return // already showing this dot
            val b = dotBuilt(color)
            view.flatTop = false
            view.show(b.view, b.w, b.h, b.radius, animate)
            shownDot = color
            h.postDelayed(moveRunnable, 380)
            return
        }
        shownDot = null
        if (prefs.alwaysShowPill) {
            view.flatTop = prefs.offsetY == 0
            view.show(null, idleW(), idleH(), idleH() / 2f, animate)
            return
        }
        // hide: shrink, slide into the notch, fade out
        val d = dotSize()
        view.flatTop = false
        view.show(null, d, d, d / 2f, animate)
        h.postDelayed(moveRunnable, 380)
        h.postDelayed(detachRunnable, 650)
    }

    private fun dotSize() = ctx.dp(10)
    private fun dotBox() = ctx.dp(26)
    private fun idleW() = if (prefs.idleWidth > 0) ctx.dp(prefs.idleWidth) else maxOf(notch.w, ctx.dp(28))
    private fun idleH() = if (prefs.idleHeight > 0) ctx.dp(prefs.idleHeight) else maxOf(notch.h, ctx.dp(24))

    // ------------------------------------------------------------------ window

    private fun layoutParams(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (fsHidden) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // Resting as a dot: sit in the middle of the cutout so it lives inside the notch.
        val inNotch = idle && (dotMode || !prefs.alwaysShowPill)
        val restSize = if (dotMode) dotBox() else dotSize()
        val yPx = if (inNotch) maxOf(0, (notch.h - restSize) / 2) else ctx.dp(prefs.offsetY)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = notch.cx + ctx.dp(prefs.offsetX)
            y = yPx
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
        shownDot = null
    }

    private fun updateWindow() {
        if (!attached) return
        try {
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
            h.post {
                if (attached) view.alpha = if (fsHidden) 0f else 1f
                updateWindow()
            }
        }
    }

    // ------------------------------------------------------------------ gestures

    /** Tap on the dot: bring the running activity back as an expanded card. */
    private fun expandFromDot() {
        val item = background.values.lastOrNull()
        if (item != null) {
            background.remove(item.key)
            current = if (item is Live.Charging) Live.Charging(batteryLevel(), false) else item
            focusMedia = false
        } else {
            mediaCollapsed = false
        }
        expanded = true
        buzz()
        scheduleTimeout()
        render()
    }

    private fun handleTap() {
        if (idle && dotMode) {
            expandFromDot()
            return
        }
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
        if (idle && dotMode) {
            expandFromDot()
            return
        }
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
                mediaCollapsed = true
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

    /** A button on the island was pressed (real notification action or built-in button). */
    override fun runAct(a: Act) {
        try {
            a.onClick?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "button action failed", e)
        }
        runAction(a.intent, a.closes)
    }

    private fun runAction(pi: PendingIntent?, finish: Boolean) {
        openPi(pi)
        val t = current
        if (finish && t != null && !mediaShown()) finishCurrent(true, false) else if (finish) setExpanded(false)
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

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ helpers

    private val ticker = object : Runnable {
        override fun run() {
            mediaUi?.let { u ->
                val m = u.info
                val pos = m.position()
                u.progress.progress = if (m.duration > 0) (pos * 1000 / m.duration).toInt() else 0
                u.elapsed?.text = fmtTime(pos)
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

    // ------------------------------------------------------------------ previews (settings screen)

    private val demoEnds = HashMap<String, Runnable>()

    /** Removes a preview activity after a while so it does not stay forever. */
    private fun endDemo(key: String, afterMs: Long) {
        demoEnds.remove(key)?.let { h.removeCallbacks(it) }
        val r = Runnable {
            demoEnds.remove(key)
            background.remove(key)
            if (current?.key == key) finishCurrent(true, false) else if (idle) render()
        }
        demoEnds[key] = r
        h.postDelayed(r, afterMs)
    }

    private fun openApp() {
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "cannot open app", e)
        }
    }

    private fun answerDemo(icon: Drawable?) {
        toast("Call answered")
        post(
            Live.Notif(
                "demo_call", "Phone", icon, null, "Craze Studio", "On call",
                0, 0, false, null,
                listOf(Act("Hang up", null, { toast("Call ended") }, true)), true
            )
        )
        endDemo("demo_call", 30000)
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
                    0, 0, false, null,
                    listOf(
                        Act("Open", null, { openApp() }, true),
                        Act("Later", null, { toast("Okay, later") }, true)
                    ),
                    false
                )
            )
            "call" -> {
                post(
                    Live.Notif(
                        "demo_call", "Phone", icon, null, "Craze Studio", "Incoming call",
                        0, 0, false, null,
                        listOf(
                            Act("Decline", null, { toast("Call declined") }, true),
                            Act("Answer", null, { answerDemo(icon) }, false)
                        ),
                        true
                    )
                )
                endDemo("demo_call", 15000)
            }
            "charge" -> {
                post(Live.Charging(batteryLevel(), false))
                endDemo(CHARGING_KEY, 15000)
            }
            "progress" -> {
                post(
                    Live.Notif(
                        "demo_progress", "Downloads", icon, null, "Downloading update", "72% complete",
                        72, 100, false, null, emptyList(), false
                    )
                )
                endDemo("demo_progress", 20000)
            }
        }
    }

    companion object {
        private const val TAG = "DynamicIsland"
        private const val CHARGING_KEY = "charging"
        private val GREEN = Color.parseColor("#30D158")
        private val BLUE = Color.parseColor("#0A84FF")
        private val PINK = Color.parseColor("#FF375F")
    }
}
