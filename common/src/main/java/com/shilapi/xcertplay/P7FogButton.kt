package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.adb.LocalAdbDevicePolicy
import com.shilapi.xcertplay.host.R
import java.util.concurrent.Executors
import kotlin.math.abs

/** Activity-owned control: never draws over another app or resumes a previous flashing request. */
internal class P7FogButton(private val parent: FrameLayout,
    private val night: () -> Boolean = { parent.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES },
    private val source: () -> View? = { null },
    private val eligible: () -> Boolean) {
    companion object {
        private const val PREFS = "p7_fog_overlay"
        private const val PULSE_MILLIS = 250L
        private const val REFRESH_MILLIS = 200L
        private const val MIN_TARGET_DP = 48
        private const val MAX_TARGET_DP = 56
        private const val TARGET_HEIGHT_FRACTION = 0.09f
        private const val BAR_WIDTH_FRACTION = 0.13f
        private const val HOME_GAP_FRACTION = 0.115f
        fun supported() = Build.VERSION.SDK_INT == 28 && LocalAdbDevicePolicy.isXpengP7()
        fun enabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
        fun enable(c: Context, value: Boolean) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        }
    }

    private val context = parent.context
    private val main = Handler(Looper.getMainLooper())
    private val commands = Executors.newSingleThreadExecutor()
    @Volatile private var requested = false
    private var busy = false
    private var destroyed = false
    private var resumed = false
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val button = ImageButton(context).apply {
        setImageResource(R.drawable.ic_p7_fog)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = null
        elevation = 0f
        visibility = View.GONE
        setOnClickListener {
            if (busy) { requested = false; paint() }
            else if (resumed && eligible() && enabled(context)) startFlashing()
        }
    }
    private val contrast = CarPlayControlContrast(button, source, night) { paint() }
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            val show = supported() && enabled(context) && eligible()
            button.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) { requested = false; contrast.stop() } else contrast.refresh()
            paint()
            main.postDelayed(this, REFRESH_MILLIS)
        }
    }

    init {
        parent.addView(button, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layout() }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        button.setOnTouchListener { _, event ->
            val params = button.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    originX = params.leftMargin; originY = params.topMargin; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) dragging = true
                    if (dragging) {
                        params.leftMargin = (originX + event.rawX - downX).toInt().coerceIn(0, (parent.width - button.width).coerceAtLeast(0))
                        params.topMargin = (originY + event.rawY - downY).toInt().coerceIn(0, (parent.height - button.height).coerceAtLeast(0))
                        button.layoutParams = params
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs.edit()
                        .putFloat("carplay_x", params.leftMargin.toFloat() / (parent.width - button.width).coerceAtLeast(1))
                        .putFloat("carplay_y", params.topMargin.toFloat() / (parent.height - button.height).coerceAtLeast(1)).apply()
                    else button.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
    }

    private fun layout() {
        if (parent.width <= 0 || parent.height <= 0) return
        val density = context.resources.displayMetrics.density
        val size = (parent.height * TARGET_HEIGHT_FRACTION).toInt()
            .coerceIn((MIN_TARGET_DP * density).toInt(), (MAX_TARGET_DP * density).toInt())
            .coerceAtMost(minOf(parent.width, parent.height))
        val maxX = (parent.width - size).coerceAtLeast(0)
        val maxY = (parent.height - size).coerceAtLeast(0)
        val defaultX = ((parent.height * BAR_WIDTH_FRACTION - size) / 2).toInt()
        val defaultY = (parent.height * (1f - HOME_GAP_FRACTION) - size).toInt()
        button.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = if (prefs.contains("carplay_x")) (prefs.getFloat("carplay_x", 0f) * maxX).toInt().coerceIn(0, maxX)
                else defaultX.coerceIn(0, maxX)
            topMargin = if (prefs.contains("carplay_y")) (prefs.getFloat("carplay_y", 0f) * maxY).toInt().coerceIn(0, maxY)
                else defaultY.coerceIn(0, maxY)
        }
        val padding = size / 8
        button.setPadding(padding, padding, padding, padding)
    }

    fun resume() {
        if (destroyed) return
        resumed = true; layout(); main.removeCallbacks(refresh); refresh.run()
    }

    fun pause() {
        resumed = false; requested = false
        main.removeCallbacks(refresh); button.visibility = View.GONE; contrast.stop()
    }

    fun close() {
        pause(); destroyed = true; commands.shutdown()
    }

    private fun paint() {
        val dark = contrast.darkBackground
        button.setColorFilter(if (requested) {
            if (dark) Color.rgb(255, 174, 55) else Color.rgb(166, 88, 0)
        } else if (dark) Color.WHITE else Color.rgb(23, 29, 39))
        button.alpha = if (busy && !requested) 0.5f else 1f
        button.contentDescription = context.getString(if (requested) R.string.fog_stop else R.string.fog_start)
    }

    private fun startFlashing() {
        requested = true; busy = true; paint()
        commands.execute {
            var restored = false
            try {
                LocalAdb(AdbKeys.load(context)).use { adb ->
                    check(adb.connect(true) == LocalAdb.Access.READY)
                    if (requested) {
                        val path = context.applicationInfo.sourceDir + ":/system/framework/android.car.jar"
                        val quoted = "'" + path.replace("'", "'\\''") + "'"
                        val shell = adb.openShell("trap '' HUP; CLASSPATH=$quoted app_process /system/bin com.shilapi.xcertplay.P7FogWorker")
                            ?: error("worker unavailable")
                        shell.use {
                            while (requested) {
                                val start = SystemClock.elapsedRealtime()
                                check(it.exchangeBounded("PULSE", 3000) == "PULSE")
                                val delay = PULSE_MILLIS - (SystemClock.elapsedRealtime() - start)
                                if (delay > 0) Thread.sleep(delay)
                            }
                            restored = it.exchangeBounded("STOP", 3000) == "STOPPED"
                        }
                    } else restored = true
                }
            } catch (_: Exception) { /* The worker's lease expires and restores independently. */ }
            main.post {
                busy = false; requested = false
                if (!destroyed) {
                    paint()
                    if (!restored) Toast.makeText(context, R.string.fog_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}
