package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import android.graphics.RectF
import android.view.animation.PathInterpolator
import kotlin.math.min

/** Native port of svg-spinners/ring-resize (MIT, Utkarsh Verma).
 * 2s linear rotation plus 1.5s ease-in-out dash animation; see third_party/svg-spinners.
 * Hidden screens never keep an animator running.
 */
internal class ConnectionLoadingIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var phase = 0f
    private val ease = PathInterpolator(0.42f, 0f, 0.58f, 1f)
    private val bounds = RectF()
    var color: Int = 0
        set(value) { field = value; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE ||
            (android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            animator?.cancel()
            animator = null
            phase = 0.1f
            invalidate()
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = min(width, height) / 24f
        val radius = 9.5f * unit
        bounds.set(width / 2f - radius, height / 2f - radius,
            width / 2f + radius, height / 2f + radius)
        val cycle = (phase * 4f) % 1f
        val length: Float
        val offset: Float
        when {
            cycle < 0.475f -> {
                val t = ease.getInterpolation(cycle / 0.475f)
                length = 42f * t
                offset = 16f * t
            }
            cycle < 0.95f -> {
                val t = ease.getInterpolation((cycle - 0.475f) / 0.475f)
                length = 42f
                offset = 16f + 43f * t
            }
            else -> { length = 42f; offset = 59f }
        }
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * unit
        paint.strokeCap = Paint.Cap.ROUND
        // The 150-unit gap exceeds the circumference: clip the dash at the path end.
        val circumference = (2 * Math.PI * 9.5).toFloat()
        val start = offset
        val end = min(offset + length, circumference)
        val rotation = phase * 1080f
        if (end > start) canvas.drawArc(bounds, rotation + start / circumference * 360f,
            (end - start) / circumference * 360f, false, paint)
    }
}
