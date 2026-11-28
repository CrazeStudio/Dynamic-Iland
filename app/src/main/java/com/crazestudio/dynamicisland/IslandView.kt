package com.crazestudio.dynamicisland

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/**
 * The black pill. It owns its own animated size, so the overlay window
 * (WRAP_CONTENT) grows and shrinks with it. Content is clipped to the
 * rounded outline while the pill morphs between sizes.
 *
 * [flatTop] = square top corners, so the island merges into the top edge / notch.
 */
class IslandView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onSwipeUp()
        fun onSwipe(dir: Int)
    }

    var listener: Listener? = null

    var flatTop: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                applyDims()
            }
        }

    private val bg = GradientDrawable().apply { setColor(Color.BLACK) }
    private val radii = FloatArray(8)
    private val path = Path()
    private val rectF = RectF()
    private var curW = 0f
    private var curH = 0f
    private var curR = 0f
    private var content: View? = null
    private var anim: ValueAnimator? = null

    /** Damped spring: overshoots slightly, then settles. */
    private val spring = TimeInterpolator { t ->
        1f - exp(-6.5f * t) * cos(2f * Math.PI.toFloat() * 1.15f * t)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            listener?.onTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            listener?.onLongPress()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val dx = e2.x - (e1?.x ?: e2.x)
            val dy = e2.y - (e1?.y ?: e2.y)
            return if (abs(dy) > abs(dx) && dy < -context.dp(20)) {
                listener?.onSwipeUp()
                true
            } else if (abs(dx) > abs(dy) && abs(dx) > context.dp(40)) {
                listener?.onSwipe(if (dx > 0) 1 else -1)
                true
            } else false
        }
    })

    init {
        background = bg
        isClickable = true
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) {
                    outline.setEmpty()
                    return
                }
                rectF.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                path.reset()
                path.addRoundRect(rectF, radii, Path.Direction.CW)
                outline.setConvexPath(path)
            }
        }
        clipToOutline = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams
            c.measure(
                MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(curW.toInt().coerceAtLeast(1), curH.toInt().coerceAtLeast(1))
    }

    private fun applyDims() {
        val b = min(curR, min(curW, curH) / 2f)
        val t = if (flatTop) 0f else b
        radii[0] = t; radii[1] = t; radii[2] = t; radii[3] = t
        radii[4] = b; radii[5] = b; radii[6] = b; radii[7] = b
        bg.cornerRadii = radii.copyOf()
        invalidateOutline()
        requestLayout()
    }

    /** Jump to a size without animating (used before the first show). */
    fun snapTo(w: Int, h: Int, r: Float) {
        anim?.cancel()
        curW = w.toFloat()
        curH = h.toFloat()
        curR = r
        applyDims()
    }

    fun clear() {
        anim?.cancel()
        removeAllViews()
        content = null
    }

    /** Morph to [w] x [h] and cross-fade to [c] (null = empty pill). */
    fun show(c: View?, w: Int, h: Int, r: Float, animate: Boolean) {
        val old = content
        content = c
        if (c != null) {
            c.alpha = if (animate) 0f else 1f
            addView(c, LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        val fw = curW
        val fh = curH
        val fr = curR
        anim?.cancel()

        if (!animate) {
            curW = w.toFloat()
            curH = h.toFloat()
            curR = r
            old?.let { removeView(it) }
            applyDims()
            return
        }

        old?.let { o ->
            o.animate().alpha(0f).setDuration(130).withEndAction { removeView(o) }.start()
        }
        c?.animate()?.alpha(1f)?.setStartDelay(110)?.setDuration(200)?.start()

        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = spring
            addUpdateListener {
                val p = if (it.animatedFraction >= 1f) 1f else it.animatedValue as Float
                curW = fw + (w - fw) * p
                curH = fh + (h - fh) * p
                curR = fr + (r - fr) * p
                applyDims()
            }
            start()
        }
    }
}
