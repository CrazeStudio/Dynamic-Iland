package com.crazestudio.dynamicisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Slim rounded progress bar (determinate or indeterminate). */
class PillBar(context: Context) : View(context) {
    var maxValue = 1000
    var progress = 0
        set(value) {
            field = value
            invalidate()
        }
    var fillColor = Color.WHITE
    var trackColor = Color.parseColor("#3A3A3C")
    var indeterminate = false
        set(value) {
            field = value
            updateAnim()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private fun updateAnim() {
        if (indeterminate && isAttachedToWindow) {
            if (!anim.isStarted) anim.start()
        } else anim.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnim()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        paint.color = trackColor
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.color = fillColor
        if (indeterminate) {
            val seg = w * 0.35f
            val x = -seg + (w + seg) * phase
            rect.set(max(0f, x), 0f, min(w, x + seg), h)
        } else {
            val fw = w * progress.coerceIn(0, maxValue) / maxValue.toFloat()
            if (fw <= 0f) return
            rect.set(0f, 0f, max(fw, h), h)
        }
        if (rect.width() > 0f) canvas.drawRoundRect(rect, r, r, paint)
    }
}
