package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos

/** Quiet, indeterminate connection activity. Hidden screens never keep an animator running. */
internal class ConnectionLoadingIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var phase = 0f
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
            phase = 0f
            invalidate()
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val step = 12f * density
        paint.color = color
        for (index in 0..2) {
            val pulse = ((cos((phase - index * 0.16f) * 2 * PI) + 1) / 2).toFloat()
            paint.alpha = (80 + 175 * pulse).toInt()
            canvas.drawCircle(width / 2f + (index - 1) * step, height / 2f,
                (2.5f + 0.8f * pulse) * density, paint)
        }
    }
}
