package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.LocationFixSource
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import com.shilapi.xcertplay.location.LocationSourceDiagnostics
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in to original-navigation matched position and vehicle heading, even without GPS. */
internal class P7DirectionLocationProvider(
    context: Context,
    private val delegate: Iap2LocationProvider,
    private val gpsPreferred: Boolean = false,
    private val drOnly: Boolean = false,
) : Iap2LocationProvider by delegate {
    private val app = context.applicationContext
    private class Subscription(val thread: HandlerThread) {
        val handler = Handler(thread.looper)
        var monitor: P7DirectionMonitor? = null
    }
    private val active = AtomicReference<Subscription?>()
    private data class DrReading(val heading: P7DirectionMonitor.Sample?, val position: P7DirectionMonitor.Position?)
    @Volatile private var reading: DrReading? = null
    @Volatile private var onChanged: (() -> Unit)? = null
    @Volatile private var requested: Set<Int>? = null
    private var lastDrSent = Long.MIN_VALUE
    private var sendingDr = false
    private var gpsSelected = true
    private var gpsRecoveryCount = 0
    private var lastRecoveryGpsAt: Long? = null

    override fun setOnLocationChanged(listener: (() -> Unit)?) {
        onChanged = listener
        delegate.setOnLocationChanged(listener)
    }
    override fun onRequested(components: Set<Int>) {
        requested = components.toSet()
        delegate.onRequested(components)
    }
    override fun onNmeaSent() {
        if (!sendingDr) delegate.onNmeaSent()
    }

    override fun start(): Boolean {
        // Never hold a source lock across platform/provider IPC or block the UI on vendor Dex loading.
        if (active.get() == null) {
            val thread = HandlerThread("XPlayDR").apply { start() }
            val next = Subscription(thread)
            if (active.compareAndSet(null, next)) {
                next.handler.post {
                    if (active.get() !== next) return@post
                    val monitor = P7DirectionMonitor(app, {
                        if (active.get() === next) {
                            reading = next.monitor?.let { DrReading(it.dr, it.drPosition) }
                            if (lowLatencyEnabled) onChanged?.invoke()
                        }
                    }, thread.looper)
                    next.monitor = monitor
                    monitor.start()
                }
            } else thread.quitSafely()
        }
        // DR is an independent asynchronous source: Android GPS need not have started successfully.
        try { delegate.start() } catch (error: Exception) {
            android.util.Log.w("P7DrLocation", "Android fallback source unavailable", error)
        }
        return true
    }

    override fun stop() {
        val previous = active.getAndSet(null)
        reading = null
        lastDrSent = Long.MIN_VALUE
        gpsSelected = true
        gpsRecoveryCount = 0
        lastRecoveryGpsAt = null
        onChanged = null
        previous?.handler?.post {
            previous.monitor?.stop()
            previous.monitor = null
            previous.thread.quitSafely()
        }
        delegate.stop()
    }

    override fun latestNmea(): String? {
        sendingDr = false
        val fallback = delegate.latestNmea()
        // The delegate evaluates quality/freshness even when a GPS sample was already sent.
        // Do not alternate back to DR between callbacks just because no new GPS sentence exists.
        val now = SystemClock.elapsedRealtime()
        val snapshot = reading
        val position = snapshot?.position?.takeIf { now - it.receivedAt in 0..DR_FRESHNESS_MS }
        if (gpsPreferred) {
            val gpsAt = delegate.reliableGpsSampleTimeMillis
            if (!delegate.hasReliableGps || gpsAt == null) {
                gpsSelected = false
                gpsRecoveryCount = 0
                lastRecoveryGpsAt = null
            } else {
                if (!gpsSelected && gpsAt != lastRecoveryGpsAt) {
                    // Only distinct, consecutive measurements count, never repeated send polls.
                    gpsRecoveryCount = if (lastRecoveryGpsAt?.let { gpsAt - it in 1..2000L } == true)
                        gpsRecoveryCount + 1 else 1
                    lastRecoveryGpsAt = gpsAt
                }
                if (position == null || gpsRecoveryCount >= GPS_RECOVERY_SAMPLES) gpsSelected = true
                if (gpsSelected) return fallback
            }
        }
        // Preserve independently requested wheel-speed sentences when replacing only GGA/RMC.
        val auxiliary = fallback?.split("\r\n")?.filter { it.isNotEmpty() &&
            !it.startsWith("$" + "GPGGA,") && !it.startsWith("$" + "GNGGA,") &&
            !it.startsWith("$" + "GPRMC,") && !it.startsWith("$" + "GNRMC,") }
            ?.joinToString("") { "$it\r\n" }.orEmpty()
        val components = requested
        val wantsPosition = components == null || components.any { it == 1 || it == 2 }
        if (!wantsPosition) return auxiliary.takeIf { it.isNotEmpty() }
        if (position == null) {
            if (!drOnly) return fallback
            sendingDr = true
            val unavailable = NmeaLocationEncoder.encodeUnavailable().split("\r\n").filter {
                it.isNotEmpty() && (components == null ||
                    (it.startsWith("$" + "GPGGA,") && 1 in components) ||
                    (it.startsWith("$" + "GPRMC,") && 2 in components))
            }.joinToString("") { "$it\r\n" }
            return (unavailable + auxiliary).takeIf { it.isNotEmpty() }
        }
        LocationSourceDiagnostics.reportDrReception(position.receivedAt)
        sendingDr = true
        if (position.receivedAt <= lastDrSent) return auxiliary.takeIf { it.isNotEmpty() }
        val (latitude, longitude) = AmapDrCoordinates.toWgs84(position.latitude, position.longitude)
        val heading = snapshot?.heading?.takeIf { now - it.receivedAt in 0..DR_FRESHNESS_MS }?.degrees?.toDouble()
        val encoded = NmeaLocationEncoder.encode(CarPlayLocationFix(latitude, longitude,
            bearingDegrees = heading, speedMetersPerSecond = position.speedMetersPerSecond,
            source = LocationFixSource.DR))
        val sentences = encoded.split("\r\n").filter { it.isNotEmpty() &&
            (components == null || (it.startsWith("$" + "GPGGA,") && 1 in components) ||
                (it.startsWith("$" + "GPRMC,") && 2 in components)) }.joinToString("") { "$it\r\n" }
        lastDrSent = position.receivedAt
        sendingDr = true
        return (sentences + auxiliary).takeIf { it.isNotEmpty() }
    }

    override fun close() = stop()

    private companion object {
        const val DR_FRESHNESS_MS = 2000L
        const val GPS_RECOVERY_SAMPLES = 5
    }
}
