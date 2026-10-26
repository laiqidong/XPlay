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
    private val videoBounds: () -> android.graphics.RectF = { android.graphics.RectF(0f, 0f, parent.width.toFloat(), parent.height.toFloat()) },
    private val cancelTouch: () -> Unit = {},
    private val eligible: () -> Boolean) {
    companion object {
        private const val PREFS = "p7_fog_overlay"
        private const val PULSE_MILLIS = 250L
        private const val REFRESH_MILLIS = 200L
        private const val BAR_WIDTH_FRACTION = 0.13f
        private const val HOME_GAP_FRACTION = .115f
        private const val ENTRY_ICON_FRACTION = .62f
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
    private var actionBusy = false
    private var pendingCommand: String? = null
    private var fogTarget: Int? = null
    private val fogConfirmationTimeout = Runnable {
        if (fogTarget != null) {
            fogTarget = null
            if (!destroyed && resumed) {
                sheetRefresh()
                Toast.makeText(context, R.string.vehicle_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    private var positioning = false
    private var vehicleState: org.json.JSONObject? = null
    private var vehicleMonitoring = false
    private val vehicleController = P7ClimateController(context, { state ->
        vehicleState = state; actionBusy = false; pendingCommand = null
        if (fogTarget != null && (state == null || !state.optBoolean("ok", false) || state.optInt("fog", -1) == fogTarget)) {
            fogTarget = null; main.removeCallbacks(fogConfirmationTimeout)
        }
        if (!destroyed) sheetRefresh()
    }, { Toast.makeText(context, R.string.vehicle_failed, Toast.LENGTH_SHORT).show() },
        workerClass = "com.shilapi.xcertplay.P7VehicleWorker")
    private var destroyed = false
    @Volatile private var resumed = false
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val button = ImageButton(context).apply {
        setImageDrawable(VehicleControlIcons.load(context, "vehicle_entry"))
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = null
        elevation = 0f
        visibility = View.GONE
        setOnClickListener {
            if (resumed && eligible() && enabled(context)) {
                if (!sheet.dismiss()) sheet.open()
            }
        }
    }
    private val sheet = P7ClimateOverlay(parent, eligible, source, videoBounds, cancelTouch,
        night = night, iconLoader = { c, key -> VehicleControlIcons.load(c, key) }, vehicleActions = { listOf(
            P7VehicleAction(context.getString(R.string.vehicle_fog), "vehicle_fog", selected = !busy && (fogTarget ?: vehicleState?.optInt("fog", -1)) == 1, enabled = !busy && validState("fog", 0..1)) { vehicleCommand("FOG") },
            P7VehicleAction(context.getString(if (requested) R.string.vehicle_flash_stop else R.string.vehicle_flash), "vehicle_flash", requested) {
                if (actionBusy) return@P7VehicleAction
                if (busy) { requested = false; paint() } else startFlashing()
                sheetRefresh()
            },
            P7VehicleAction(context.getString(if (trunkOpen()) R.string.vehicle_trunk_close else R.string.vehicle_trunk), if (trunkOpen()) "vehicle_trunk_close" else "vehicle_trunk", selected = trunkOpen(), enabled = pendingCommand != "TRUNK" && trunkReady()) { vehicleCommand("TRUNK") },
            P7VehicleAction(context.getString(R.string.vehicle_charge_slow), "vehicle_charge_slow", selected = vehicleState?.optInt("slow", -1) == 0, enabled = pendingCommand != "CHARGE_SLOW" && portReady("slow")) { vehicleCommand("CHARGE_SLOW") },
            P7VehicleAction(context.getString(R.string.vehicle_charge_fast), "vehicle_charge_fast", selected = vehicleState?.optInt("fast", -1) == 0, enabled = pendingCommand != "CHARGE_FAST" && portReady("fast")) { vehicleCommand("CHARGE_FAST") }
        ) })
    private fun validState(key: String, range: IntRange) = vehicleState?.optInt(key, -1) in range
    private fun trunkOpen() = vehicleState?.optInt("trunk", -1) in 2..9
    private fun trunkReady() = validState("trunk", 0..10)
    private fun portReady(key: String) = vehicleState?.optInt(key, -1).let { it == 0 || it == 2 }
    private fun sheetRefresh() { sheet.refreshVehicleActions() }
    fun dismiss() = sheet.dismiss()
    private val contrast = CarPlayControlContrast(button, source, night) { paint() }
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            layout()
            val show = supported() && enabled(context) && eligible()
            button.visibility = if (show) View.VISIBLE else View.GONE
            if (show != vehicleMonitoring) {
                vehicleMonitoring = show
                if (show) vehicleController.start() else { vehicleController.stop(); vehicleState = null; actionBusy = false; pendingCommand = null
        fogTarget = null; main.removeCallbacks(fogConfirmationTimeout) }
            }
            if (!show) { sheet.dismiss(); requested = false; contrast.stop() } else contrast.refresh()
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
                    positioning = true
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
                    positioning = false
                    if (dragging) prefs.edit()
                        .putFloat("carplay_x", params.leftMargin.toFloat() / (parent.width - button.width).coerceAtLeast(1))
                        .putFloat("carplay_y", params.topMargin.toFloat() / (parent.height - button.height).coerceAtLeast(1)).apply()
                    else button.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { positioning = false; dragging = false; true }
                else -> false
            }
        }
    }

    private fun layout() {
        if (positioning || parent.width <= 0 || parent.height <= 0) return
        val size = P7VehicleControlStyle.buttonSize(context, videoBounds().height())
            .coerceAtMost(minOf(parent.width, parent.height))
        val maxX = (parent.width - size).coerceAtLeast(0)
        val maxY = (parent.height - size).coerceAtLeast(0)
        val bounds = videoBounds()
        val defaultX = (bounds.left + (bounds.height() * BAR_WIDTH_FRACTION - size) / 2).toInt()
        val defaultY = (bounds.top + bounds.height() * (1f - HOME_GAP_FRACTION) - size).toInt()
        val next = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = if (prefs.contains("carplay_x")) (prefs.getFloat("carplay_x", 0f) * maxX).toInt().coerceIn(0, maxX)
                else defaultX.coerceIn(0, maxX)
            topMargin = if (prefs.contains("carplay_y")) (prefs.getFloat("carplay_y", 0f) * maxY).toInt().coerceIn(0, maxY)
                else defaultY.coerceIn(0, maxY)
        }
        val current = button.layoutParams as? FrameLayout.LayoutParams
        if (current == null || current.width != next.width || current.height != next.height ||
            current.leftMargin != next.leftMargin || current.topMargin != next.topMargin) button.layoutParams = next
        val padding = (size * (1f - ENTRY_ICON_FRACTION) / 2f).toInt()
        button.setPadding(padding, padding, padding, padding)
    }

    fun resume() {
        if (destroyed) return
        resumed = true; sheet.resume(); layout(); main.removeCallbacks(refresh); refresh.run()
    }

    fun pause() {
        resumed = false; requested = false; sheet.pause()
        vehicleMonitoring = false; vehicleController.stop(); vehicleState = null; actionBusy = false; pendingCommand = null
        fogTarget = null; main.removeCallbacks(fogConfirmationTimeout)
        main.removeCallbacks(refresh); button.visibility = View.GONE; contrast.stop()
    }

    fun close() {
        pause(); destroyed = true; sheet.close(); commands.shutdown(); vehicleController.close()
    }

    private fun paint() {
        val dark = contrast.darkBackground
        button.setColorFilter(if (requested) {
            if (dark) Color.rgb(255, 174, 55) else Color.rgb(166, 88, 0)
        } else if (dark) Color.WHITE else Color.rgb(23, 29, 39))
        button.alpha = if (busy && !requested) 0.5f else 1f
        button.contentDescription = context.getString(R.string.settings_vehicle_controls_title)
    }

    private fun vehicleCommand(command: String) {
        if (actionBusy || !resumed || !eligible() || !enabled(context)) return
        if (command == "FOG" && fogTarget != null) return
        val nextFog = if (command == "FOG") vehicleState?.optInt("fog", -1)?.takeIf { it in 0..1 }?.let { 1 - it } else null
        if (command == "FOG" && nextFog == null) return
        if (vehicleController.request(command)) {
            actionBusy = true; pendingCommand = command
            if (nextFog != null) {
                fogTarget = nextFog
                main.removeCallbacks(fogConfirmationTimeout)
                main.postDelayed(fogConfirmationTimeout, 4000)
            }
            sheetRefresh()
        }
    }
    private fun startFlashing() {
        if (!resumed || !eligible() || !enabled(context) || busy) return
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
                sheetRefresh()
                if (!destroyed) {
                    paint()
                    if (!restored) Toast.makeText(context, R.string.fog_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}
