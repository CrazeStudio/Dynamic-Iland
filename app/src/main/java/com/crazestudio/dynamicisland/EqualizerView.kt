package com.crazestudio.dynamicisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** Four animated bars shown while media is playing. */
class EqualizerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34D399") }
    private val phases = floatArrayOf(0f, 1.3f, 2.4f, 0.7f)
    private val rect = RectF()
    private var t = 0f

    var active: Boolean = true
        set(value) {
            field = value
            updateAnimator()
            invalidate()
        }

    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    private fun updateAnimator() {
        if (active && isAttachedToWindow) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val n = phases.size
        val barW = width / (n * 2f - 1f)
        for (i in 0 until n) {
            val f = if (active) 0.25f + 0.75f * abs(sin(t + phases[i])) else 0.2f
            val h = height * f
            val left = i * barW * 2f
            rect.set(left, height - h, left + barW, height.toFloat())
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }
}
