package com.crazestudio.dynamicisland

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup + settings screen, built in code so the project has no layout files. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val refreshers = ArrayList<() -> Unit>()
    private lateinit var master: Switch
    private lateinit var notchInfo: TextView

    private val bg = Color.parseColor("#0B0B0D")
    private val card = Color.parseColor("#1C1C1E")
    private val gray = Color.parseColor("#9CA3AF")
    private val green = Color.parseColor("#30D158")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        saveNotch()
        refreshers.forEach { it() }
    }

    // ------------------------------------------------------------------ state

    private fun overlayOk() = Settings.canDrawOverlays(this)
    private fun accessOk() = Access.isEnabled(this)

    private fun listenerOk(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private fun postOk() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun powerOk(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun applyLive() {
        IslandBus.controller?.refreshSettings()
        refreshNotchInfo()
    }

    /** Remember the cutout while the activity is shown (used on Android 10). */
    private fun saveNotch() {
        window.decorView.post {
            if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return@post
            val r = window.decorView.rootWindowInsets?.displayCutout?.boundingRectTop
            if (r != null && !r.isEmpty) {
                prefs.cutW = r.width()
                prefs.cutH = r.height()
                prefs.cutCx = r.centerX() - resources.displayMetrics.widthPixels / 2
                refreshNotchInfo()
            }
        }
    }

    private fun refreshNotchInfo() {
        val n = Notch.detect(this, prefs)
        val d = resources.displayMetrics.density
        notchInfo.text = "Detected cutout: ${(n.w / d).toInt()} x ${(n.h / d).toInt()} dp, " +
            "offset ${(n.cx / d).toInt()} dp from centre"
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(40))
        }
        col.addView(label("Dynamic Island", 30f, Color.WHITE, true))
        col.addView(label("A Dynamic Island for notch and punch-hole phones", 14f, gray).apply { setPadding(0, dp(4), 0, dp(18)) })

        // setup
        col.addView(section("Setup"))
        val setup = cardBox()
        setup.addView(statusRow("Accessibility service", "Required: draws the island above the status bar, inside the notch", { accessOk() }) {
            toast("Find 'Dynamic Island' and switch it on")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        setup.addView(statusRow("Notification access", "Lets the island show notifications and media", { listenerOk() }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        setup.addView(statusRow("Keep running (recommended)", "Stops battery optimisation from closing the island after inactivity", { powerOk() }) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        if (Build.VERSION.SDK_INT >= 33) {
            setup.addView(statusRow("Post notifications", "Shows the small 'running' notification (fallback mode)", { postOk() }) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        setup.addView(statusRow("Display over other apps", "Optional fallback. Sits under the status bar, so prefer accessibility", { overlayOk() }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(setup)

        // master switch
        master = Switch(this).apply {
            text = "Enable Dynamic Island"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val masterBox = cardBox().apply { addView(master) }
        col.addView(masterBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val masterListener = CompoundButton.OnCheckedChangeListener { _, on ->
            if (on) {
                if (!accessOk() && !overlayOk()) {
                    toast("Turn on the Accessibility service first")
                    setSilently(master, false)
                    return@OnCheckedChangeListener
                }
                prefs.enabled = true
                val a = IslandAccessibilityService.instance
                if (a != null) a.syncEnabled()
                else if (!accessOk()) startForegroundService(Intent(this, IslandService::class.java))
            } else {
                prefs.enabled = false
                IslandAccessibilityService.instance?.syncEnabled()
                stopService(Intent(this, IslandService::class.java))
            }
        }
        refreshers.add { setSilently(master, prefs.enabled && (accessOk() || overlayOk()), masterListener) }
        master.setOnCheckedChangeListener(masterListener)

        // features
        col.addView(section("What the island shows"))
        val features = cardBox()
        features.addView(switchRow("Notifications", prefs.showNotifications) { prefs.showNotifications = it; applyLive() })
        features.addView(switchRow("Media playback", prefs.showMedia) { prefs.showMedia = it; applyLive() })
        features.addView(switchRow("Charging and low battery", prefs.showCharging) { prefs.showCharging = it; applyLive() })
        features.addView(switchRow("Haptic feedback", prefs.haptics) { prefs.haptics = it })
        col.addView(features)

        // behaviour
        col.addView(section("Behaviour"))
        val behaviour = cardBox()
        behaviour.addView(switchRow("Hide in landscape", prefs.hideInLandscape) { prefs.hideInLandscape = it; applyLive() })
        behaviour.addView(switchRow("Hide in fullscreen apps (experimental)", prefs.hideInFullscreen) { prefs.hideInFullscreen = it; applyLive() })
        behaviour.addView(switchRow("Always show the pill", prefs.alwaysShowPill) { prefs.alwaysShowPill = it; applyLive() })
        behaviour.addView(switchRow("Activity dot on the right of the camera", prefs.dotRight) { prefs.dotRight = it; applyLive() })
        behaviour.addView(seekRow("Auto-dismiss", 2, 15, prefs.autoDismissSec, " s") { prefs.autoDismissSec = it })
        col.addView(behaviour)

        // calibration
        col.addView(section("Position and size"))
        val calib = cardBox()
        notchInfo = TextView(this).apply {
            setTextColor(gray)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(6))
        }
        calib.addView(notchInfo)
        calib.addView(seekRow("Horizontal offset", -80, 80, prefs.offsetX, " dp") { prefs.offsetX = it; applyLive() })
        calib.addView(seekRow("Vertical offset (0 = merged with the notch)", 0, 40, prefs.offsetY, " dp") { prefs.offsetY = it; applyLive() })
        calib.addView(seekRow("Idle pill width (0 = auto)", 0, 200, prefs.idleWidth, " dp") { prefs.idleWidth = it; applyLive() })
        calib.addView(seekRow("Idle pill height (0 = auto)", 0, 60, prefs.idleHeight, " dp") { prefs.idleHeight = it; applyLive() })
        col.addView(calib)
        refreshNotchInfo()

        // previews
        col.addView(section("Try it"))
        val tests = cardBox()
        tests.addView(button("Preview notification") { preview("notif") })
        tests.addView(button("Preview incoming call (try Answer / Decline)") { preview("call") })
        tests.addView(button("Preview charging") { preview("charge") })
        tests.addView(button("Preview download progress") { preview("progress") })
        col.addView(tests)

        col.addView(label(
            "Gestures: tap to expand, tap the dot to reopen a running activity, long press to open the app, swipe up to dismiss, swipe sideways to switch between media and notifications.",
            12f, gray
        ).apply { setPadding(dp(4), dp(16), dp(4), 0) })

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun preview(kind: String) {
        val c = IslandBus.controller
        if (c == null) toast("Turn on 'Enable Dynamic Island' first") else c.demo(kind)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setSilently(s: Switch, on: Boolean, l: CompoundButton.OnCheckedChangeListener? = null) {
        s.setOnCheckedChangeListener(null)
        s.isChecked = on
        if (l != null) s.setOnCheckedChangeListener(l)
    }

    // ------------------------------------------------------------------ widgets

    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(s: String) = label(s.uppercase(), 12f, gray, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(22), 0, dp(8))
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = roundBg(card, dp(18).toFloat())
    }

    private fun switchRow(title: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = title
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = initial
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun seekRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val head = label("$title: $initial$unit", 14f, Color.WHITE)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    head.text = "$title: ${p + min}$unit"
                    if (fromUser) onChange(p + min)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(head)
        box.addView(sb, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun button(title: String, onClick: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = roundBg(Color.parseColor("#2C2C2E"), dp(14).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }
    }

    private fun statusRow(title: String, desc: String, granted: () -> Boolean, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, Color.WHITE, true))
        val status = label(desc, 12f, gray)
        texts.addView(status)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val btn = button("Grant", action).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(40))
        }
        row.addView(btn)
        refreshers.add {
            val ok = granted()
            status.text = if (ok) "Granted" else desc
            status.setTextColor(if (ok) green else gray)
            btn.visibility = if (ok) View.GONE else View.VISIBLE
        }
        return row
    }
}
