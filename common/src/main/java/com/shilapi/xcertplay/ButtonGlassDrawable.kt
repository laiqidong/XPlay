package com.shilapi.xcertplay

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils

/** Capsule material sampled from the actual ancestor surfaces, excluding the button itself. */
internal class ButtonGlassDrawable(
    private val owner: View,
    private val night: Boolean,
    private val tint: Int?,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val edge = RectF()
    private val position = IntArray(2)
    private val ancestorPosition = IntArray(2)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var snapshot: Bitmap? = null
    private var lastDialogFrame: Bitmap? = null
    private var shader: BitmapShader? = null
    private var dirty = true
    private var opacity = 255

    init { SettingsBackdrop.trackPosition(owner) }

    override fun onBoundsChange(bounds: Rect) { dirty = true }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        owner.getLocationInWindow(position)
        val dialogFrame = DialogBackdrop.frame(owner)?.bitmap
        if (dirty || lastX != position[0] || lastY != position[1] || lastDialogFrame !== dialogFrame) {
            sampleAncestors()
            lastX = position[0]; lastY = position[1]; lastDialogFrame = dialogFrame
            dirty = false
        }
        rect.set(bounds)
        val radius = rect.height() * LiquidGlassStyle.CONTROL_RADIUS_RATIO
        paint.shader = shader
        paint.alpha = opacity
        if (shader != null) canvas.drawRoundRect(rect, radius, radius, paint)
        fill.color = tint?.let { ColorUtils.setAlphaComponent(it, 220) }
            ?: if (night) 0x407B8798 else 0x55FFFFFF
        fill.alpha = Color.alpha(fill.color) * opacity / 255
        canvas.drawRoundRect(rect, radius, radius, fill)
        val stroke = owner.resources.displayMetrics.density * BUTTON_RIM_DP
        edge.set(rect); edge.inset(stroke / 2, stroke / 2)
        rim.strokeWidth = stroke
        rim.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(Color.argb(if (night) 100 else 155, 255, 255, 255),
                Color.argb(if (night) 28 else 42, 0, 0, 0), Color.argb(45, 255, 255, 255)),
            floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        rim.alpha = opacity
        canvas.drawRoundRect(edge, (radius - stroke / 2).coerceAtLeast(0f),
            (radius - stroke / 2).coerceAtLeast(0f), rim)
    }

    private fun sampleAncestors() {
        val width = minOf(SAMPLE_WIDTH, bounds.width()).coerceAtLeast(1)
        val height = (bounds.height().toFloat() * width / bounds.width()).toInt().coerceAtLeast(1)
        var bitmap = snapshot
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            // Drop the old image without recycling a bitmap that a hardware display list may still use.
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            snapshot = bitmap
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        val buffer = Canvas(bitmap)
        buffer.scale(width.toFloat() / bounds.width(), height.toFloat() / bounds.height())
        val ancestors = mutableListOf<View>()
        var parent = owner.parent
        while (parent is View) { ancestors.add(parent); parent = parent.parent }
        for (view in ancestors.asReversed()) {
            val background = view.background ?: continue
            view.getLocationInWindow(ancestorPosition)
            val saved = buffer.save()
            buffer.translate((ancestorPosition[0] - position[0]).toFloat(),
                (ancestorPosition[1] - position[1]).toFloat())
            background.draw(buffer)
            buffer.restoreToCount(saved)
        }
        SettingsBackdrop.blurButtonSnapshot(bitmap)
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(bounds.width().toFloat() / width, bounds.height().toFloat() / height)
                postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
            })
        }
    }

    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, bounds.height() * LiquidGlassStyle.CONTROL_RADIUS_RATIO) }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val SAMPLE_WIDTH = 256
        const val BUTTON_RIM_DP = .7f
    }
}
