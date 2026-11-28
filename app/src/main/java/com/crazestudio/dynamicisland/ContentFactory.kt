package com.crazestudio.dynamicisland

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Builds the layouts of the island: compact, peek and expanded. */
class ContentFactory(private val ctx: Context, notch: Notch.Info, private val cb: Callbacks) {

    interface Callbacks {
        fun runAct(a: Act)
        fun mediaCmd(cmd: Int) // 0 prev, 1 play/pause, 2 next
    }

    class MediaUi(val info: MediaInfo, val progress: PillBar, val elapsed: TextView?)

    class Built(val view: View, val w: Int, val h: Int, val radius: Float, val ui: MediaUi? = null)

    private val white = Color.parseColor("#F5F5F7")
    private val gray = Color.parseColor("#A1A1A6")
    private val green = Color.parseColor("#30D158")
    private val red = Color.parseColor("#FF453A")
    private val chip = Color.parseColor("#2C2C2E")

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private val notchW = max(notch.w, ctx.dp(28))
    private val notchH = max(notch.h, ctx.dp(24))
    private val screenW = ctx.resources.displayMetrics.widthPixels
    private val compactH = max(notchH + ctx.dp(8), ctx.dp(36))
    private val expandedW = min(screenW - ctx.dp(16), ctx.dp(372))
    private val expandedR = ctx.dpf(36f)

    private fun dp(v: Int) = ctx.dp(v)

    // ---------------------------------------------------------------- compact / peek

    fun mediaCompact(m: MediaInfo): Built {
        val w = notchW + 2 * dp(46)
        val h = compactH
        val left = ear(art(m, dp(7)), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL), dp(12), 0)
        val eq = EqualizerView(ctx).apply { active = m.playing }
        val right = ear(eq, FrameLayout.LayoutParams(dp(18), dp(14), Gravity.END or Gravity.CENTER_VERTICAL), 0, dp(14))
        return Built(row(w, h, left, right), w, h, h / 2f)
    }

    fun livePeek(t: Live): Built {
        val earW = min(dp(122), (screenW - dp(16) - notchW) / 2)
        val w = notchW + 2 * earW
        val h = compactH
        val leftView: View
        val label: String
        var color = white
        when (t) {
            is Live.Charging -> {
                color = if (t.low) red else green
                leftView = icon(R.drawable.ic_bolt, color)
                label = "${t.percent}%"
            }
            is Live.Notif -> {
                if (t.isCall) {
                    leftView = icon(R.drawable.ic_call, green)
                    color = green
                } else {
                    leftView = drawableView(t.avatar ?: t.icon, dp(7))
                }
                label = peekLabel(t)
            }
        }
        val left = ear(leftView, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL), dp(12), 0)
        val tv = tv(label, 13f, color, true)
        val right = ear(tv, FrameLayout.LayoutParams(match, wrap, Gravity.START or Gravity.CENTER_VERTICAL), dp(8), dp(14))
        return Built(row(w, h, left, right), w, h, h / 2f)
    }

    private fun peekLabel(t: Live.Notif): String {
        val base = t.title.ifBlank { t.text }
        if (t.progressMax > 0) return "$base  ${t.progress * 100 / t.progressMax}%"
        return base
    }

    // ---------------------------------------------------------------- expanded

    fun liveExpanded(t: Live): Built {
        val w = expandedW
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        when (t) {
            is Live.Notif -> if (t.isCall) buildCall(root, t, w) else buildNotif(root, t, w)
            is Live.Charging -> buildCharging(root, t, w)
        }
        return Built(root, w, measureH(root, w), expandedR)
    }

    private fun buildNotif(root: LinearLayout, t: Live.Notif, w: Int) {
        root.addView(header(w, t.icon, t.appName, "now"))
        val body = vbox(dp(20), dp(8), dp(20), dp(18))
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pic = t.avatar ?: t.icon
        if (pic != null) {
            top.addView(drawableView(pic, dp(14)), LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (t.title.isNotBlank()) col.addView(tv(t.title, 16f, white, true))
        if (t.text.isNotBlank()) col.addView(tv(t.text, 14f, gray, false, 3).apply { setPadding(0, dp(2), 0, 0) })
        top.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        body.addView(top)

        if (t.hasProgress) {
            val value = if (t.progressMax > 0) t.progress * 1000 / t.progressMax else 0
            body.addView(
                pillBar(1000, value, white, t.indeterminate),
                LinearLayout.LayoutParams(match, dp(5)).apply { topMargin = dp(14) }
            )
        }
        if (t.actions.isNotEmpty()) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            t.actions.take(3).forEachIndexed { i, a ->
                r.addView(
                    action(a.title, i == 0) { cb.runAct(a) },
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) marginStart = dp(8) }
                )
            }
            body.addView(r, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) })
        }
        root.addView(body)
    }

    private fun buildCall(root: LinearLayout, t: Live.Notif, w: Int) {
        root.addView(header(w, t.icon, t.appName, "call"))
        val body = vbox(dp(22), dp(8), dp(22), dp(20))
        body.addView(tv(t.title.ifBlank { "Unknown" }, 22f, white, true))
        body.addView(tv(t.text.ifBlank { "Incoming call" }, 14f, gray).apply { setPadding(0, dp(3), 0, 0) })

        val acts = t.actions
        val decline = acts.firstOrNull { has(it.title, "decline", "reject", "hang", "end", "dismiss", "cancel") } ?: acts.firstOrNull()
        val accept = acts.firstOrNull { has(it.title, "answer", "accept", "pick") } ?: acts.firstOrNull { it !== decline }

        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (decline != null) {
            r.addView(
                callButton(red, 135f, decline.title) { cb.runAct(decline) },
                LinearLayout.LayoutParams(0, wrap, 1f)
            )
        }
        if (accept != null && accept !== decline) {
            r.addView(
                callButton(green, 0f, accept.title) { cb.runAct(accept) },
                LinearLayout.LayoutParams(0, wrap, 1f)
            )
        }
        if (r.childCount > 0) body.addView(r, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(18) })
        root.addView(body)
    }

    private fun buildCharging(root: LinearLayout, t: Live.Charging, w: Int) {
        val color = if (t.low) red else green
        root.addView(header(w, null, "Battery", if (t.low) "low" else "charging"))
        val body = vbox(dp(22), dp(8), dp(22), dp(22))
        body.addView(tv(if (t.low) "Battery low" else "Charging", 14f, gray))
        body.addView(tv("${t.percent}%", 38f, color, true).apply { setPadding(0, dp(2), 0, dp(12)) })
        body.addView(pillBar(100, t.percent, color, false), LinearLayout.LayoutParams(match, dp(6)))
        root.addView(body)
    }

    fun mediaExpanded(m: MediaInfo): Built {
        val w = expandedW
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val eq = EqualizerView(ctx).apply { active = m.playing }
        val right = ear(eq, FrameLayout.LayoutParams(dp(18), dp(14), Gravity.END or Gravity.CENTER_VERTICAL), 0, dp(22))
        root.addView(row(w, notchH + dp(2), null, right))

        val body = vbox(dp(20), dp(8), dp(20), dp(20))
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(art(m, dp(16)), LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(14) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(m.title, 17f, white, true))
        if (m.artist.isNotBlank()) col.addView(tv(m.artist, 14f, gray).apply { setPadding(0, dp(2), 0, 0) })
        top.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        body.addView(top)

        val value = if (m.duration > 0) (m.position() * 1000 / m.duration).toInt() else 0
        val progress = pillBar(1000, value, white, false)
        body.addView(progress, LinearLayout.LayoutParams(match, dp(5)).apply { topMargin = dp(18) })

        var elapsed: TextView? = null
        if (m.duration > 0) {
            val times = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val e = tv(fmtTime(m.position()), 11f, gray)
            elapsed = e
            times.addView(e, LinearLayout.LayoutParams(0, wrap, 1f))
            times.addView(tv(fmtTime(m.duration), 11f, gray))
            body.addView(times, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        }

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(
            ctl(R.drawable.ic_prev, dp(13), white, null) { cb.mediaCmd(0) },
            LinearLayout.LayoutParams(dp(52), dp(52))
        )
        controls.addView(
            ctl(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, dp(16), Color.BLACK, white) { cb.mediaCmd(1) },
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(20); marginEnd = dp(20) }
        )
        controls.addView(
            ctl(R.drawable.ic_next, dp(13), white, null) { cb.mediaCmd(2) },
            LinearLayout.LayoutParams(dp(52), dp(52))
        )
        body.addView(controls, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        root.addView(body)
        return Built(root, w, measureH(root, w), expandedR, MediaUi(m, progress, elapsed))
    }

    // ---------------------------------------------------------------- building blocks

    /** Top strip next to the camera: app icon + name on the left, a label on the right. */
    private fun header(w: Int, appIcon: Drawable?, name: String, rightLabel: String): View {
        val leftBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(6), 0)
            if (appIcon != null) {
                addView(drawableView(appIcon, dp(4)), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) })
            }
            addView(tv(name, 12f, gray), LinearLayout.LayoutParams(0, wrap, 1f))
        }
        val rightBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(22), 0)
            addView(tv(rightLabel, 12f, gray))
        }
        return row(w, notchH + dp(2), leftBox, rightBox)
    }

    /** Lays two "ears" on either side of the camera cutout. */
    private fun row(totalW: Int, h: Int, l: View?, r: View?): FrameLayout {
        val earW = (totalW - notchW) / 2
        val root = FrameLayout(ctx)
        if (l != null) root.addView(l, FrameLayout.LayoutParams(earW, h, Gravity.START or Gravity.TOP))
        if (r != null) root.addView(r, FrameLayout.LayoutParams(earW, h, Gravity.END or Gravity.TOP))
        return root
    }

    private fun ear(child: View, lp: FrameLayout.LayoutParams, padStart: Int, padEnd: Int): FrameLayout =
        FrameLayout(ctx).apply {
            setPadding(padStart, 0, padEnd, 0)
            addView(child, lp)
        }

    private fun vbox(l: Int, t: Int, r: Int, b: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(l, t, r, b)
    }

    private fun tv(s: String, sp: Float, color: Int, strong: Boolean = false, lines: Int = 1) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (strong) medium else regular
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun icon(res: Int, tint: Int) = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(tint)
    }

    private fun round(v: View, radius: Int) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
            }
        }
        v.clipToOutline = true
    }

    private fun drawableView(d: Drawable?, radius: Int) = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        if (d != null) setImageDrawable(d) else setBackgroundColor(chip)
        round(this, radius)
    }

    private fun art(m: MediaInfo, radius: Int) = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        if (m.art != null) setImageBitmap(m.art) else setBackgroundColor(chip)
        round(this, radius)
    }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(if (primary) Color.BLACK else white)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = medium
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(10), 0, dp(10), 0)
        background = roundBg(if (primary) white else chip, ctx.dpf(24f))
        setOnClickListener { onClick() }
    }

    /** Round call button with a caption underneath. */
    private fun callButton(color: Int, rot: Float, label: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val b = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(Color.WHITE)
            rotation = rot
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundBg(color, ctx.dpf(40f))
        }
        addView(b, LinearLayout.LayoutParams(dp(58), dp(58)))
        addView(tv(label, 12f, gray).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        setOnClickListener { onClick() }
    }

    private fun ctl(res: Int, pad: Int, tint: Int, bg: Int?, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(tint)
        setPadding(pad, pad, pad, pad)
        if (bg != null) background = roundBg(bg, ctx.dpf(40f))
        setOnClickListener { onClick() }
    }

    private fun pillBar(max: Int, value: Int, fg: Int, indeterminate: Boolean) = PillBar(ctx).apply {
        maxValue = max
        progress = value
        fillColor = fg
        this.indeterminate = indeterminate
    }

    private fun has(title: String, vararg words: String): Boolean {
        val s = title.lowercase()
        return words.any { s.contains(it) }
    }

    private fun measureH(v: View, w: Int): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ctx.dp(460), View.MeasureSpec.AT_MOST)
        )
        return v.measuredHeight
    }
}
