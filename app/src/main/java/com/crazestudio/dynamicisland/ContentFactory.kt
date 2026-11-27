package com.crazestudio.dynamicisland

import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Builds compact, peek and expanded layouts sized to the current phone display. */
class ContentFactory(private val ctx: Context, notch: Notch.Info, private val cb: Callbacks) {

    interface Callbacks {
        fun runAction(pi: PendingIntent?, finish: Boolean)
        fun mediaCmd(cmd: Int) // 0 prev, 1 play/pause, 2 next
    }

    class MediaUi(val info: MediaInfo, val progress: ProgressBar)
    class Built(val view: View, val w: Int, val h: Int, val radius: Float, val ui: MediaUi? = null)

    private val white = Color.WHITE
    private val gray = Color.parseColor("#9CA3AF")
    private val green = Color.parseColor("#34D399")
    private val red = Color.parseColor("#F87171")
    private val chip = Color.parseColor("#2C2C2E")

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val screenW = max(ctx.dp(1), ctx.resources.displayMetrics.widthPixels)
    private val screenH = max(ctx.dp(1), ctx.resources.displayMetrics.heightPixels)
    private val expandedW = min(max(ctx.dp(1), screenW - ctx.dp(24)), ctx.dp(380))
    // Leave room for the expanded card's header and prevent oversized/manual cutout values
    // from producing negative ear widths on small displays.
    private val notchW = min(max(notch.w, ctx.dp(28)), max(ctx.dp(1), expandedW - ctx.dp(32)))
    private val notchH = min(max(notch.h, ctx.dp(24)), max(ctx.dp(24), screenH / 5))
    private val compactH = max(notchH + ctx.dp(6), ctx.dp(38))
    private val expandedR = ctx.dpf(34f)

    private fun dp(v: Int) = ctx.dp(v)

    // ---------------------------------------------------------------- compact / peek

    fun mediaCompact(m: MediaInfo): Built {
        val w = min(max(notchW, screenW - dp(16)), notchW + 2 * dp(46))
        val h = compactH
        val left = ear(art(m, dp(24), dp(7)), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL), dp(11), 0)
        val eq = EqualizerView(ctx).apply { active = m.playing }
        val right = ear(eq, FrameLayout.LayoutParams(dp(18), dp(14), Gravity.END or Gravity.CENTER_VERTICAL), 0, dp(12))
        return Built(row(w, h, left, right), w, h, h / 2f)
    }

    fun livePeek(t: Live): Built {
        val earW = max(0, min(dp(118), (screenW - dp(24) - notchW) / 2))
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
        val left = ear(leftView, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL), dp(11), 0)
        val tv = tv(label, 13f, color, t is Live.Charging)
        val right = ear(tv, FrameLayout.LayoutParams(match, wrap, Gravity.START or Gravity.CENTER_VERTICAL), dp(8), dp(12))
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
        return expandedBuilt(root, w)
    }

    private fun buildNotif(root: LinearLayout, t: Live.Notif, w: Int) {
        root.addView(header(w, t.icon, t.appName, "now"))
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(16))
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pic = t.avatar ?: t.icon
        if (pic != null) top.addView(drawableView(pic, dp(12)), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (t.title.isNotBlank()) col.addView(tv(t.title, 15f, white, true))
        if (t.text.isNotBlank()) col.addView(tv(t.text, 13f, gray, false, 3))
        top.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        body.addView(top)

        if (t.hasProgress) {
            val value = if (t.progressMax > 0) t.progress * 1000 / t.progressMax else 0
            body.addView(bar(1000, value, white, t.indeterminate), LinearLayout.LayoutParams(match, dp(4)).apply { topMargin = dp(12) })
        }
        if (t.actions.isNotEmpty()) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            t.actions.take(3).forEach { a ->
                r.addView(pill(a.title, chip, white) { cb.runAction(a.intent, true) }, LinearLayout.LayoutParams(wrap, dp(34)).apply { marginEnd = dp(8) })
            }
            body.addView(r, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        }
        root.addView(body)
    }

    private fun buildCall(root: LinearLayout, t: Live.Notif, w: Int) {
        root.addView(header(w, t.icon, t.appName, "call"))
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(16))
        }
        body.addView(tv(t.title.ifBlank { "Unknown" }, 20f, white, true))
        body.addView(tv(t.text.ifBlank { "Incoming call" }, 13f, gray).apply { setPadding(0, dp(2), 0, 0) })

        val acts = t.actions
        val decline = acts.firstOrNull { has(it.title, "decline", "reject", "hang", "end", "dismiss", "cancel") } ?: acts.firstOrNull()
        val accept = acts.firstOrNull { has(it.title, "answer", "accept", "pick") } ?: acts.firstOrNull { it !== decline }
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        if (decline != null) {
            r.addView(pill(decline.title, Color.parseColor("#3A1F1F"), red) { cb.runAction(decline.intent, true) },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = if (accept != null && accept !== decline) dp(10) else 0 })
        }
        if (accept != null && accept !== decline) {
            r.addView(pill(accept.title, Color.parseColor("#16A34A"), white) { cb.runAction(accept.intent, true) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        if (r.childCount > 0) body.addView(r, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) })
        root.addView(body)
    }

    private fun buildCharging(root: LinearLayout, t: Live.Charging, w: Int) {
        val color = if (t.low) red else green
        root.addView(header(w, null, "Battery", if (t.low) "low" else "charging"))
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(18))
        }
        body.addView(tv(if (t.low) "Battery low" else "Charging", 13f, gray))
        body.addView(tv("${t.percent}%", 32f, color, true).apply { setPadding(0, dp(2), 0, dp(10)) })
        body.addView(bar(100, t.percent, color, false), LinearLayout.LayoutParams(match, dp(6)))
        root.addView(body)
    }

    fun mediaExpanded(m: MediaInfo): Built {
        val w = expandedW
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val eq = EqualizerView(ctx).apply { active = m.playing }
        val right = ear(eq, FrameLayout.LayoutParams(dp(18), dp(14), Gravity.END or Gravity.CENTER_VERTICAL), 0, dp(20))
        root.addView(row(w, notchH + dp(2), null, right))

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(16))
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(art(m, dp(56), dp(12)), LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(m.title, 16f, white, true))
        if (m.artist.isNotBlank()) col.addView(tv(m.artist, 13f, gray))
        top.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        body.addView(top)

        val value = if (m.duration > 0) (m.position() * 1000 / m.duration).toInt() else 0
        val progress = bar(1000, value, white, false)
        body.addView(progress, LinearLayout.LayoutParams(match, dp(4)).apply { topMargin = dp(14) })

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(ctl(R.drawable.ic_prev, dp(10), white, null) { cb.mediaCmd(0) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        controls.addView(ctl(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play, dp(14), Color.BLACK, white) { cb.mediaCmd(1) },
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(22); marginEnd = dp(22) })
        controls.addView(ctl(R.drawable.ic_next, dp(10), white, null) { cb.mediaCmd(2) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        body.addView(controls, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
        root.addView(body)
        return expandedBuilt(root, w, MediaUi(m, progress))
    }

    /** Expanded content scrolls on short displays rather than being clipped off the bottom. */
    private fun expandedBuilt(content: View, w: Int, ui: MediaUi? = null): Built {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = content.measuredHeight.coerceAtLeast(dp(1))
        val maxHeight = (screenH - dp(80)).coerceAtLeast(dp(1))
        val viewportHeight = min(contentHeight, maxHeight)
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content, ScrollView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return Built(scroll, w, viewportHeight, expandedR, ui)
    }

    // ---------------------------------------------------------------- building blocks

    /** Top strip next to the camera: app icon + name on the left, a label on the right. */
    private fun header(w: Int, appIcon: Drawable?, name: String, rightLabel: String): View {
        val leftBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(6), 0)
            if (appIcon != null) addView(drawableView(appIcon, dp(4)), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
            addView(tv(name, 11f, gray), LinearLayout.LayoutParams(0, wrap, 1f))
        }
        val rightBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(20), 0)
            addView(tv(rightLabel, 11f, gray))
        }
        return row(w, notchH + dp(2), leftBox, rightBox)
    }

    /** Lays two ears on either side of the camera cutout. */
    private fun row(totalW: Int, h: Int, l: View?, r: View?): FrameLayout {
        val earW = max(0, (totalW - notchW) / 2)
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

    private fun tv(s: String, sp: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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

    private fun art(m: MediaInfo, @Suppress("UNUSED_PARAMETER") size: Int, radius: Int) = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        if (m.art != null) setImageBitmap(m.art) else setBackgroundColor(chip)
        round(this, radius)
    }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(14), 0, dp(14), 0)
        background = roundBg(bg, ctx.dpf(22f))
        setOnClickListener { onClick() }
    }

    private fun ctl(res: Int, pad: Int, tint: Int, bg: Int?, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(tint)
        setPadding(pad, pad, pad, pad)
        if (bg != null) background = roundBg(bg, ctx.dpf(40f))
        setOnClickListener { onClick() }
    }

    private fun bar(max: Int, value: Int, fg: Int, indeterminate: Boolean) =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = max
            progress = value
            isIndeterminate = indeterminate
            progressTintList = ColorStateList.valueOf(fg)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A3A3C"))
            if (indeterminate) indeterminateTintList = ColorStateList.valueOf(fg)
        }

    private fun has(title: String, vararg words: String): Boolean {
        val s = title.lowercase()
        return words.any { s.contains(it) }
    }
}
