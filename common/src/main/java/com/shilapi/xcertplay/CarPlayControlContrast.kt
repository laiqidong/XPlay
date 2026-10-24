package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View

/** Samples only video pixels behind a shortcut, excluding our own foreground artwork. */
internal class CarPlayControlContrast(
    private val owner: View,
    private val source: () -> View?,
    private val fallbackNight: () -> Boolean,
    private val changed: () -> Unit
) {
    private var sampledDark: Boolean? = null
    val darkBackground: Boolean get() = sampledDark ?: fallbackNight()
    private var lastSample = 0L
    private var busy = false
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())

    fun stop() { generation++; sampledDark = null; lastSample = 0L }

    fun refresh() {
        if (!owner.isShown || busy || SystemClock.elapsedRealtime() - lastSample < SAMPLE_INTERVAL_MS) return
        val video = source() ?: return
        if (!video.isAttachedToWindow || video.width <= 0 || video.height <= 0) return
        val ownerPosition = IntArray(2); val videoPosition = IntArray(2)
        owner.getLocationInWindow(ownerPosition); video.getLocationInWindow(videoPosition)
        val area = Rect(ownerPosition[0] - videoPosition[0], ownerPosition[1] - videoPosition[1],
            ownerPosition[0] - videoPosition[0] + owner.width, ownerPosition[1] - videoPosition[1] + owner.height)
        area.inset(area.width() / 4, area.height() / 4)
        if (!area.intersect(0, 0, video.width, video.height)) return
        lastSample = SystemClock.elapsedRealtime()
        val ticket = generation
        fun finish(bitmap: Bitmap?, crop: Rect? = null) {
            busy = false
            if (bitmap == null) return
            try {
                if (ticket != generation || !owner.isShown) return
                val box = crop ?: Rect(0, 0, bitmap.width, bitmap.height)
                var brightness = 0f
                for (y in 0 until SAMPLE_GRID) for (x in 0 until SAMPLE_GRID) {
                    val pixel = bitmap.getPixel((box.left + box.width() * (x + .5f) / SAMPLE_GRID).toInt().coerceIn(0, bitmap.width - 1),
                        (box.top + box.height() * (y + .5f) / SAMPLE_GRID).toInt().coerceIn(0, bitmap.height - 1))
                    brightness += .2126f * ((pixel ushr 16) and 255) + .7152f * ((pixel ushr 8) and 255) + .0722f * (pixel and 255)
                }
                brightness /= SAMPLE_GRID * SAMPLE_GRID
                // Hysteresis prevents color flicker near a midtone background.
                val dark = when {
                    brightness < DARK_THRESHOLD -> true
                    brightness > LIGHT_THRESHOLD -> false
                    else -> darkBackground
                }
                val before = darkBackground
                sampledDark = dark
                if (before != dark) changed()
            } finally { bitmap.recycle() }
        }
        when (video) {
            is SurfaceView -> {
                busy = true
                val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(video, area, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) finish(bitmap) else { bitmap.recycle(); busy = false }
                    }, main)
                } catch (_: Exception) { bitmap.recycle(); busy = false }
            }
            is TextureView -> {
                val bitmap = runCatching { video.getBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT) }.getOrNull() ?: return
                val scaled = Rect(area.left * bitmap.width / video.width, area.top * bitmap.height / video.height,
                    area.right * bitmap.width / video.width, area.bottom * bitmap.height / video.height)
                finish(bitmap, scaled)
            }
        }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val SAMPLE_SIZE = 12
        private const val SAMPLE_GRID = 4
        private const val TEXTURE_WIDTH = 128
        private const val TEXTURE_HEIGHT = 64
        private const val DARK_THRESHOLD = 116f
        private const val LIGHT_THRESHOLD = 144f
    }
}
