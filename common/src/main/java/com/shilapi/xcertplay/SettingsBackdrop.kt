package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.max

/** Static wallpaper and its shared, downsampled blur. Never samples the settings controls. */
internal class SettingsBackdrop(context: Context, private val night: Boolean) {
    companion object {
        const val CARD_INSET_DP = 24
        const val CARD_VERTICAL_INSET_DP = 24
        private const val EDGE_STROKE_DP = .8f
        const val CARD_RADIUS_DP = 28
        private const val BLUR_WIDTH = 320
        private const val BLUR_RADIUS = 12
    }

    private val density = context.resources.displayMetrics.density
    private val wallpaper = BitmapFactory.decodeResource(context.resources,
        if (night) R.drawable.settings_golden_gate_dark else R.drawable.settings_golden_gate_light)
    private val blurred = Bitmap.createScaledBitmap(wallpaper, BLUR_WIDTH,
        (wallpaper.height.toFloat() * BLUR_WIDTH / wallpaper.width).toInt().coerceAtLeast(1), true).let(::blur)
    private var root: View? = null

    fun install(view: View, fullBlur: Boolean = false) {
        root = view
        view.background = Layer(view, false, fullBlur)
    }

    fun frost(view: View): Drawable {
        // Scrolling changes the sampled wallpaper region even if the card's own layout is unchanged.
        val observer = ViewTreeObserver.OnPreDrawListener {
            val drawable = view.background as? Layer
            drawable?.updatePosition()
            true
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnPreDrawListener(observer) }
            override fun onViewDetachedFromWindow(v: View) {
                if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(observer)
            }
        })
        return layer(view, true)
    }

    private fun layer(owner: View, frost: Boolean) = Layer(owner, frost)

    private inner class Layer(private val owner: View, private val frost: Boolean, private val fullBlur: Boolean = false) : Drawable() {
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = Path()
        private val location = IntArray(2)
        private val origin = IntArray(2)
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
        fun updatePosition() {
            owner.getLocationInWindow(location)
            if (location[0] != lastX || location[1] != lastY) {
                lastX = location[0]; lastY = location[1]; invalidateSelf()
            }
        }
        override fun draw(canvas: Canvas) {
            val host = root ?: return
            if (host.width == 0 || host.height == 0) return
            owner.getLocationInWindow(location)
            host.getLocationInWindow(origin)
            val bitmap = if (frost || fullBlur) blurred else wallpaper
            val scale = max(host.width.toFloat() / bitmap.width, host.height.toFloat() / bitmap.height)
            val dx = (host.width - bitmap.width * scale) / 2f - (location[0] - origin[0])
            val dy = (host.height - bitmap.height * scale) / 2f - (location[1] - origin[1])
            val rect = RectF(bounds)
            val save = canvas.save()
            if (frost) {
                path.reset()
                path.addRoundRect(rect, CARD_RADIUS_DP * density, CARD_RADIUS_DP * density, Path.Direction.CW)
                canvas.clipPath(path)
            }
            canvas.drawBitmap(bitmap, null, RectF(dx, dy, dx + bitmap.width * scale, dy + bitmap.height * scale), paint)
            if (frost || fullBlur) canvas.drawColor(if (night) 0xA6292D39.toInt() else 0xB3FFFFFF.toInt())
            canvas.restoreToCount(save)
            if (frost) {
                // Glass reflects at the top and bottom; both side edges stay subtly shaded.
                val stroke = EDGE_STROKE_DP * density
                val radius = minOf(CARD_RADIUS_DP * density, rect.width() / 2f, rect.height() / 2f)
                val side = Color.argb(if (night) 14 else 20, 0, 0, 0)
                val highlight = Color.argb(if (night) 38 else 58, 255, 255, 255)
                edgePaint.shader = SweepGradient(rect.centerX(), rect.centerY(),
                    intArrayOf(side, highlight, side, highlight, side),
                    floatArrayOf(0f, .25f, .5f, .75f, 1f))
                val edge = RectF(rect).apply { inset(stroke / 2, stroke / 2) }
                edgePaint.strokeWidth = stroke
                edgePaint.alpha = 255
                canvas.drawRoundRect(edge, (radius - stroke / 2).coerceAtLeast(0f),
                    (radius - stroke / 2).coerceAtLeast(0f), edgePaint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Two separable box passes, cached once per appearance, including older P7 Android versions. */
    private fun blur(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        var input = IntArray(w * h)
        var output = IntArray(input.size)
        bitmap.getPixels(input, 0, w, 0, 0, w, h)
        repeat(2) {
            for (horizontal in listOf(true, false)) {
                val lines = if (horizontal) h else w
                val length = if (horizontal) w else h
                for (line in 0 until lines) {
                    fun index(p: Int) = if (horizontal) line * w + p.coerceIn(0, length - 1)
                        else p.coerceIn(0, length - 1) * w + line
                    var r = 0; var g = 0; var b = 0
                    for (p in -BLUR_RADIUS..BLUR_RADIUS) {
                        val c = input[index(p)]; r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                    }
                    val count = BLUR_RADIUS * 2 + 1
                    for (p in 0 until length) {
                        output[index(p)] = Color.rgb(r / count, g / count, b / count)
                        val remove = input[index(p - BLUR_RADIUS)]
                        val add = input[index(p + BLUR_RADIUS + 1)]
                        r += Color.red(add) - Color.red(remove)
                        g += Color.green(add) - Color.green(remove)
                        b += Color.blue(add) - Color.blue(remove)
                    }
                }
                val swap = input; input = output; output = swap
            }
        }
        bitmap.setPixels(input, 0, w, 0, 0, w, h)
        return bitmap
    }
}
