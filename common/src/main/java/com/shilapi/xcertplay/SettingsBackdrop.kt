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
        private const val BACKGROUND_BLUR_RADIUS = 4
        private val imageCache = mutableMapOf<Boolean, Pair<Bitmap, Bitmap>>()
        @Synchronized private fun cachedImages(context: Context, night: Boolean): Pair<Bitmap, Bitmap> =
            imageCache.getOrPut(night) {
                val image = BitmapFactory.decodeResource(context.resources,
                    if (night) R.drawable.settings_golden_gate_dark else R.drawable.settings_golden_gate_light)
                val small = Bitmap.createScaledBitmap(image, BLUR_WIDTH,
                    (image.height.toFloat() * BLUR_WIDTH / image.width).toInt().coerceAtLeast(1), true)
                val background = requireNotNull(small.copy(Bitmap.Config.ARGB_8888, true))
                blur(background, BACKGROUND_BLUR_RADIUS) to blur(small)
            }

        fun surface(view: View, night: Boolean, controlCorners: Boolean = false, tint: Int? = null): Drawable =
            SettingsBackdrop(view.context, night).frost(view, controlCorners, tint)

        fun blurDialogSnapshot(bitmap: Bitmap) { blur(bitmap) }
        fun blurButtonSnapshot(bitmap: Bitmap) { blur(bitmap, 2) }

        fun trackPosition(view: View) {
            if (view.getTag(R.id.glass_position_tracker) == true) return
            view.setTag(R.id.glass_position_tracker, true)
            val previous = IntArray(4) { Int.MIN_VALUE }
            val position = IntArray(2)
            // Scrolling changes the sampled wallpaper region even if the card's own layout is unchanged.
            val observer = ViewTreeObserver.OnPreDrawListener {
                view.getLocationInWindow(position)
                if (position[0] != previous[0] || position[1] != previous[1] ||
                    view.rootView.width != previous[2] || view.rootView.height != previous[3]) {
                    previous[0] = position[0]; previous[1] = position[1]
                    previous[2] = view.rootView.width; previous[3] = view.rootView.height
                    view.background?.invalidateSelf()
                }
                true
            }
            if (view.isAttachedToWindow) view.viewTreeObserver.addOnPreDrawListener(observer)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnPreDrawListener(observer) }
                override fun onViewDetachedFromWindow(v: View) {
                    if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(observer)
                }
            })
        }

        // Two separable box passes, shared once per appearance on older P7 Android versions.
        private fun blur(bitmap: Bitmap, radius: Int = BLUR_RADIUS): Bitmap {
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
                        for (p in -radius..radius) {
                            val c = input[index(p)]; r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                        }
                        val count = radius * 2 + 1
                        for (p in 0 until length) {
                            output[index(p)] = Color.rgb(r / count, g / count, b / count)
                            val remove = input[index(p - radius)]
                            val add = input[index(p + radius + 1)]
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

    private val density = context.resources.displayMetrics.density
    private val images = cachedImages(context, night)
    private val wallpaper = images.first
    private val blurred = images.second
    private var root: View? = null

    fun install(view: View, fullBlur: Boolean = true) {
        root = view
        view.background = Layer(view, false, fullBlur)
    }

    fun frost(view: View, controlCorners: Boolean = false, tint: Int? = null): Drawable {
        val material = Layer(view, true, controlCorners = controlCorners, tint = tint)
        if (!controlCorners) {
            view.elevation = 0f
            view.translationZ = 0f
        }
        trackPosition(view)
        return material
    }

    private inner class Layer(private val owner: View, private val frost: Boolean, private val fullBlur: Boolean = false, private val controlCorners: Boolean = false, private val tint: Int? = null) : Drawable() {
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = Path()
        private val location = IntArray(2)
        private val origin = IntArray(2)
        override fun draw(canvas: Canvas) {
            val host = root ?: owner.rootView
            if (host.width == 0 || host.height == 0) return
            owner.getLocationInWindow(location)
            host.getLocationInWindow(origin)
            val snapshot = DialogBackdrop.frame(owner)
            val bitmap = if (snapshot != null) snapshot.bitmap else if (frost) blurred else wallpaper
            val destination = if (snapshot != null) {
                owner.getLocationOnScreen(location)
                val dx = (snapshot.left - location[0]).toFloat()
                val dy = (snapshot.top - location[1]).toFloat()
                RectF(dx, dy, dx + snapshot.width, dy + snapshot.height)
            } else {
                val image = requireNotNull(bitmap)
                val scale = max(host.width.toFloat() / image.width, host.height.toFloat() / image.height)
                val dx = (host.width - image.width * scale) / 2f - (location[0] - origin[0])
                val dy = (host.height - image.height * scale) / 2f - (location[1] - origin[1])
                RectF(dx, dy, dx + image.width * scale, dy + image.height * scale)
            }
            val rect = RectF(bounds)
            val corner = if (controlCorners) owner.height * LiquidGlassStyle.CONTROL_RADIUS_RATIO else CARD_RADIUS_DP * density
            val save = canvas.save()
            if (frost) {
                path.reset()
                path.addRoundRect(rect, corner, corner, Path.Direction.CW)
                canvas.clipPath(path)
            }
            if (bitmap != null) canvas.drawBitmap(bitmap, null, destination, paint)
            if (frost || fullBlur) canvas.drawColor(tint ?: when {
                snapshot != null && !controlCorners -> if (night) 0x66292D39 else 0x55FFFFFF
                frost && !controlCorners -> if (night) 0xD0424855.toInt() else 0xE6FFFFFF.toInt()
                else -> if (night) 0xA6292D39.toInt() else 0xB3FFFFFF.toInt()
            })
            canvas.restoreToCount(save)
            if (frost) {
                val radius = minOf(corner, rect.width() / 2f, rect.height() / 2f)
                GlassRim.drawSettings(canvas, rect, radius, density, edgePaint, light = !night)
            }
        }
        override fun getOutline(outline: Outline) {
            val radius = if (controlCorners) bounds.height() * LiquidGlassStyle.CONTROL_RADIUS_RATIO else CARD_RADIUS_DP * density
            outline.setRoundRect(bounds, radius)
            outline.alpha = .55f
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

}
