package com.shilapi.xcertplay

import android.content.Context
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Samples only the underlying video, never the lyrics or a whole-window screenshot.
 * Downsampled frames stay in memory, with at most one capture/blur in flight per owner.
 */
@Suppress("DEPRECATION")
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
    private var refreshMillis = FAST_REFRESH_MILLIS
    private var activeAt = 0L
    private var deliveredFrames = 0L
    private var reportState = ""
    private var reportedAt = 0L
    private fun report(state: String, detail: String = "") {
        val now = SystemClock.elapsedRealtime()
        if (state != reportState || now - reportedAt >= 10000) {
            reportState = state; reportedAt = now
            Log.i("XPlayLyricsBlur", "$state $detail".trim())
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val started = SystemClock.elapsedRealtime()
            if (!busy && owner.isShown && owner.windowVisibility == View.VISIBLE) capture()
            main.postDelayed(this, (refreshMillis - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1))
        }
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; generation++
        if (value) { activeAt = SystemClock.elapsedRealtime(); deliveredFrames = 0; refreshMillis = FAST_REFRESH_MILLIS }
        main.removeCallbacks(tick)
        if (value) main.post(tick)
        else { frame = null; report("inactive") }
    }
    private fun capture() {
        val video = source?.invoke() ?: run { report("source_missing"); return }
        if (!video.isAttachedToWindow || video.width <= 0 || video.height <= 0) { report("source_not_ready"); return }
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
        val captureStarted = SystemClock.elapsedRealtime()
        val profile = captureStarted - reportedAt >= 10000 || reportState.isEmpty()
        busy = true
        fun complete(bitmap: Bitmap?) {
            if (bitmap == null || ticket != generation || !active) {
                bitmap?.recycle(); busy = false; return
            }
            val captureMillis = SystemClock.elapsedRealtime() - captureStarted
            worker.execute {
                val started = SystemClock.elapsedRealtime()
                var detailBefore = 0; var detailAfter = 0
                val result = runCatching {
                    if (profile) detailBefore = bitmapDetail(bitmap)
                    blurFast(bitmap, owner.context.applicationContext)
                    if (profile) detailAfter = bitmapDetail(bitmap)
                    bitmap
                }.getOrNull()
                val backend = if (nativeUnavailable) "kotlin" else "native"
                val elapsed = SystemClock.elapsedRealtime() - started
                main.post {
                    busy = false
                    if (result != null && active && ticket == generation && owner.isAttachedToWindow) {
                        // Published bitmaps may still be referenced by hardware rendering; let GC retire them.
                        frame = Frame(result, bounds)
                        deliveredFrames++
                        refreshMillis = if (backend == "kotlin") FALLBACK_REFRESH_MILLIS
                            else if (captureMillis > 24 || elapsed > 24) SLOW_REFRESH_MILLIS else FAST_REFRESH_MILLIS
                        val fps = deliveredFrames * 1000f / (SystemClock.elapsedRealtime() - activeAt).coerceAtLeast(1)
                        report("ready source=${video.javaClass.simpleName} sample=${result.width}x${result.height} radius=$RADIUS backend=$backend",
                            "capture_ms=$captureMillis blur_ms=$elapsed fps=${String.format(java.util.Locale.ROOT, "%.1f", fps)} detail_before=$detailBefore detail_after=$detailAfter")
                        if (elapsed > 100) Log.w("XPlayLyricsBlur", "slow_blur_ms=$elapsed")
                        changed()
                    } else { bitmap.recycle(); if (result == null) report("blur_failed") }
                }
            }
        }
        when (video) {
            is TextureView -> {
                val bitmap = runCatching { video.getBitmap(width, height) }.getOrNull()
                if (bitmap == null) report("texture_copy_failed")
                complete(bitmap)
            }
            is SurfaceView -> {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(video, crop, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) complete(bitmap)
                        else { report("pixel_copy_failed code=$result"); bitmap.recycle(); complete(null) }
                    }, main)
                } catch (error: Exception) { report("pixel_copy_exception type=${error.javaClass.simpleName}"); bitmap.recycle(); complete(null) }
            }
            is android.widget.ImageView -> {
                // A static image can supply the same material in debug previews without a video session.
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.scale(width.toFloat() / video.width, height.toFloat() / video.height)
                video.draw(canvas)
                complete(bitmap)
            }
            else -> { report("unsupported_source type=${video.javaClass.simpleName}"); complete(null) }
        }
    }

    companion object {
        private const val SAMPLE_WIDTH = 320
        private const val RADIUS = 8
        private const val KERNEL_SIZE = RADIUS * 2 + 1
        private const val FAST_REFRESH_MILLIS = 50L
        private const val SLOW_REFRESH_MILLIS = 83L
        private const val FALLBACK_REFRESH_MILLIS = 120L
        // Confined to the single worker; reuse its context, script and bounded allocations.
        private var nativeBlur: NativeBlur? = null
        private var nativeUnavailable = false
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "xplay-lyric-backdrop").apply { isDaemon = true }
        }
        private fun bitmapDetail(bitmap: Bitmap): Int {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return detail(pixels, bitmap.width)
        }
        private fun blurFast(bitmap: Bitmap, context: Context) {
            if (!nativeUnavailable) {
                try {
                    val engine = nativeBlur ?: NativeBlur(context).also { nativeBlur = it }
                    engine.apply(bitmap)
                    return
                } catch (error: Exception) {
                    Log.w("XPlayLyricsBlur", "native_unavailable type=${error.javaClass.simpleName}")
                    runCatching { nativeBlur?.close() }; nativeBlur = null; nativeUnavailable = true
                }
            }
            blur(bitmap) { _, _ -> }
        }
        private class NativeBlur(context: Context) : AutoCloseable {
            private val rs = RenderScript.create(context)
            private val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)).apply {
                // Intrinsic Gaussian radius differs from two box passes; approximate the existing softness.
                setRadius((RADIUS * 2f).coerceAtMost(25f))
            }
            private var input: Allocation? = null
            private var output: Allocation? = null
            private var width = 0
            private var height = 0
            fun apply(bitmap: Bitmap) {
                if (input == null || width != bitmap.width || height != bitmap.height) {
                    input?.destroy(); output?.destroy(); input = null; output = null
                    input = Allocation.createFromBitmap(rs, bitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT)
                    output = Allocation.createTyped(rs, input!!.type)
                    width = bitmap.width; height = bitmap.height
                } else input!!.copyFrom(bitmap)
                script.setInput(input)
                script.forEach(output)
                output!!.copyTo(bitmap)
            }
            override fun close() { input?.destroy(); output?.destroy(); script.destroy(); rs.destroy() }
        }
        private fun detail(pixels: IntArray, width: Int): Int {
            if (width < 2) return 0
            var sum = 0L; var count = 0
            val stride = (pixels.size / 512).coerceAtLeast(1)
            for (index in 0 until pixels.size - 1 step stride) {
                if (index % width == width - 1) continue
                val a = pixels[index]; val b = pixels[index + 1]
                sum += kotlin.math.abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) +
                    kotlin.math.abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) +
                    kotlin.math.abs((a and 255) - (b and 255))
                count++
            }
            return if (count > 0) (sum / (count * 3)).toInt() else 0
        }
        private fun blur(bitmap: Bitmap, diagnostics: (Int, Int) -> Unit): Bitmap {
            val width = bitmap.width; val height = bitmap.height
            val input = IntArray(width * height); val temp = IntArray(input.size)
            bitmap.getPixels(input, 0, width, 0, 0, width, height)
            val detailBefore = detail(input, width)
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
            diagnostics(detailBefore, detail(input, width))
            bitmap.setPixels(input, 0, width, 0, 0, width, height)
            return bitmap
        }
    }
}
