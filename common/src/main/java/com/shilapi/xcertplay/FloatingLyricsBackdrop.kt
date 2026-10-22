package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Samples only the underlying video, never the lyrics or a whole-window screenshot.
 * Downsampled frames stay in memory, with at most one capture/blur in flight per owner.
 */
internal class FloatingLyricsBackdrop(private val owner: View, private val changed: () -> Unit) {
    data class Frame(val bitmap: Bitmap, val bounds: RectF) {
        // Sample the already blurred video under the card, not the theme or a fixed tint.
        fun brightness(card: RectF): Float? {
            if (bitmap.isRecycled || bounds.isEmpty || card.isEmpty) return null
            val area = RectF(card)
            if (!area.intersect(bounds)) return null
            var luminance = 0.0
            for (row in 0 until 4) for (column in 0 until 8) {
                val x = ((area.left + area.width() * (column + .5f) / 8 - bounds.left) / bounds.width() * bitmap.width)
                    .toInt().coerceIn(0, bitmap.width - 1)
                val y = ((area.top + area.height() * (row + .5f) / 4 - bounds.top) / bounds.height() * bitmap.height)
                    .toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(x, y)
                luminance += .2126 * ((color ushr 16) and 255) + .7152 * ((color ushr 8) and 255) + .0722 * (color and 255)
            }
            return (luminance / 32).toFloat()
        }
    }
    var region: RectF? = null
    var source: (() -> View?)? = null
    var frame: Frame? = null; private set
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var busy = false
    private var generation = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (!busy && owner.isShown && owner.windowVisibility == View.VISIBLE) capture()
            main.postDelayed(this, REFRESH_MILLIS)
        }
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; generation++
        main.removeCallbacks(tick)
        if (value) main.post(tick)
        else { frame?.bitmap?.recycle(); frame = null }
    }
    private fun capture() {
        val video = source?.invoke() ?: return
        if (!video.isAttachedToWindow || video.width <= 0 || video.height <= 0) return
        val location = IntArray(2); val origin = IntArray(2)
        video.getLocationOnScreen(location); owner.getLocationOnScreen(origin)
        val x = (location[0] - origin[0]).toFloat(); val y = (location[1] - origin[1]).toFloat()
        val bounds = RectF(x, y, x + video.width, y + video.height)
        var crop: Rect? = null
        if (video is SurfaceView) region?.let { card ->
            val area = RectF(card)
            // Overscan supplies neighbouring pixels to the blur instead of smearing the card edge.
            val overscan = video.width.toFloat() / SAMPLE_WIDTH * RADIUS * 3
            area.inset(-overscan, -overscan)
            if (area.intersect(bounds)) {
                crop = Rect(kotlin.math.floor(area.left - x).toInt(), kotlin.math.floor(area.top - y).toInt(),
                    kotlin.math.ceil(area.right - x).toInt(), kotlin.math.ceil(area.bottom - y).toInt())
                crop?.let { bounds.set(x + it.left, y + it.top, x + it.right, y + it.bottom) }
            }
        }
        val width = (SAMPLE_WIDTH * bounds.width() / video.width).roundToInt().coerceIn(1, SAMPLE_WIDTH)
        val height = (width * bounds.height() / bounds.width()).roundToInt().coerceIn(1, SAMPLE_WIDTH)
        val ticket = generation
        busy = true
        fun complete(bitmap: Bitmap?) {
            if (bitmap == null || ticket != generation || !active) {
                bitmap?.recycle(); busy = false; return
            }
            worker.execute {
                val result = runCatching { blur(bitmap) }.getOrNull()
                main.post {
                    busy = false
                    if (result != null && active && ticket == generation && owner.isAttachedToWindow) {
                        frame?.bitmap?.recycle(); frame = Frame(result, bounds); changed()
                    } else bitmap.recycle()
                }
            }
        }
        when (video) {
            is TextureView -> complete(runCatching { video.getBitmap(width, height) }.getOrNull())
            is SurfaceView -> {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(video, crop, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) complete(bitmap)
                        else { bitmap.recycle(); complete(null) }
                    }, main)
                } catch (_: Exception) { bitmap.recycle(); complete(null) }
            }
            else -> complete(null)
        }
    }

    companion object {
        private const val SAMPLE_WIDTH = 320
        private const val RADIUS = 8
        private const val KERNEL_SIZE = RADIUS * 2 + 1
        private const val REFRESH_MILLIS = 350L
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "xplay-lyric-backdrop").apply { isDaemon = true }
        }
        private fun blur(bitmap: Bitmap): Bitmap {
            val width = bitmap.width; val height = bitmap.height
            val input = IntArray(width * height); val temp = IntArray(input.size)
            bitmap.getPixels(input, 0, width, 0, 0, width, height)
            // Two separable box passes, bounded to the small sample; never touch full-size video.
            repeat(2) {
                for (y in 0 until height) {
                    var r = 0; var g = 0; var b = 0
                    fun add(x: Int, sign: Int) {
                        val color = input[y * width + x.coerceIn(0, width - 1)]
                        r += ((color ushr 16) and 255) * sign
                        g += ((color ushr 8) and 255) * sign
                        b += (color and 255) * sign
                    }
                    for (x in -RADIUS..RADIUS) add(x, 1)
                    for (x in 0 until width) {
                        temp[y * width + x] = -0x1000000 or ((r / KERNEL_SIZE) shl 16) or ((g / KERNEL_SIZE) shl 8) or (b / KERNEL_SIZE)
                        add(x - RADIUS, -1); add(x + RADIUS + 1, 1)
                    }
                }
                for (x in 0 until width) {
                    var r = 0; var g = 0; var b = 0
                    fun add(y: Int, sign: Int) {
                        val color = temp[y.coerceIn(0, height - 1) * width + x]
                        r += ((color ushr 16) and 255) * sign
                        g += ((color ushr 8) and 255) * sign
                        b += (color and 255) * sign
                    }
                    for (y in -RADIUS..RADIUS) add(y, 1)
                    for (y in 0 until height) {
                        input[y * width + x] = -0x1000000 or ((r / KERNEL_SIZE) shl 16) or ((g / KERNEL_SIZE) shl 8) or (b / KERNEL_SIZE)
                        add(y - RADIUS, -1); add(y + RADIUS + 1, 1)
                    }
                }
            }
            bitmap.setPixels(input, 0, width, 0, 0, width, height)
            return bitmap
        }
    }
}
