package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.adb.LocalAdbDevicePolicy
import com.shilapi.xcertplay.transport.*

internal object P7CarPlaySettings {
    private fun prefs(context: Context) = context.getSharedPreferences("xplay_p7_carplay", Context.MODE_PRIVATE)
    fun battery(context: Context) = LocalAdbDevicePolicy.isXpengP7() && prefs(context).getBoolean("battery", false)
    fun motion(context: Context) = LocalAdbDevicePolicy.isXpengP7() && prefs(context).getBoolean("motion", false)
    fun saveBattery(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("battery", enabled).apply() }
    fun saveMotion(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("motion", enabled).apply() }
}

/** One session-owned shell subscription supplies both iAP2 consumers, including background CarPlay. */
internal object P7CarPlayData : VehicleStatusProvider, VehicleSpeedSource {
    private val handler = Handler(Looper.getMainLooper())
    private var session: VehicleShellSession? = null
    private var active = false
    private var profile = "both"
    private var generation = 0
    private var connected = false
    private var app: Context? = null
    private val telemetry = P7CarPlayTelemetry(SystemClock::elapsedRealtime)
    private val retry = Runnable { synchronized(this) { if (active && session == null) launch() } }

    @Synchronized fun startSession(context: Context) {
        if (active) return
        if (!P7CarPlaySettings.battery(context) && !P7CarPlaySettings.motion(context)) return
        val battery = P7CarPlaySettings.battery(context)
        val motion = P7CarPlaySettings.motion(context) && AirPlayPersistence.loadLocationReportingEnabled(context)
        if (!battery && !motion) return
        profile = if (battery && motion) "both" else if (battery) "battery" else "motion"
        app = context.applicationContext
        active = true
        launch()
    }

    private fun launch() {
        val context = app ?: return
        val token = ++generation
        val next = VehicleShellSession(context, true, {}, {
            synchronized(this) {
                if (generation != token || !active) return@VehicleShellSession
                session = null
                connected = false
                telemetry.clear()
                handler.postDelayed(retry, 10_000)
            }
        }, carPlay = true, profile = profile, onField = { id, value ->
            synchronized(this) {
                if (generation == token && active) {
                    telemetry.accept(id, value)
                }
            }
        }, onReady = {
            synchronized(this) { if (generation == token && active) connected = true }
        })
        session = next
        next.start()
    }

    @Synchronized override fun snapshot(): VehicleStatusSnapshot? {
        if (!active || !connected) return null
        return telemetry.snapshot()
    }

    override fun start() { /* startSession owns the shared source; iPhone only starts consuming speed. */ }
    @Synchronized override fun stop() { telemetry.clearMotion() }
    @Synchronized override fun drain(): VehicleSpeedReading? {
        if (!active || !connected) { telemetry.clearMotion(); return null }
        return telemetry.drainSpeed()
    }

    @Synchronized fun stopSession() {
        active = false
        generation++
        handler.removeCallbacks(retry)
        session?.stop()
        session = null
        connected = false
        telemetry.clear()
    }
}
