package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Opt-in experiment: RMC course carries vehicle heading, including while reversing. */
internal class P7DirectionLocationProvider(
    context: Context,
    private val delegate: Iap2LocationProvider,
) : Iap2LocationProvider by delegate {
    private val app = context.applicationContext
    private class Subscription(val thread: HandlerThread) {
        val handler = Handler(thread.looper)
        var monitor: P7DirectionMonitor? = null
    }
    private val active = AtomicReference<Subscription?>()
    @Volatile private var sample: P7DirectionMonitor.Sample? = null

    override fun start(): Boolean {
        // Never hold a source lock across platform/provider IPC or block the UI on vendor Dex loading.
        if (active.get() == null) {
            val thread = HandlerThread("XPlayDR").apply { start() }
            val next = Subscription(thread)
            if (active.compareAndSet(null, next)) {
                next.handler.post {
                    if (active.get() !== next) return@post
                    val monitor = P7DirectionMonitor(app, {
                        if (active.get() === next) sample = next.monitor?.dr
                    }, thread.looper)
                    next.monitor = monitor
                    monitor.start()
                }
            } else thread.quitSafely()
        }
        return try {
            delegate.start().also { if (!it) stop() }
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    override fun stop() {
        val previous = active.getAndSet(null)
        sample = null
        previous?.handler?.post {
            previous.monitor?.stop()
            previous.monitor = null
            previous.thread.quitSafely()
        }
        delegate.stop()
    }

    override fun latestNmea(): String? {
        val nmea = delegate.latestNmea() ?: return null
        val value = sample?.takeIf { SystemClock.elapsedRealtime() - it.receivedAt in 0..DR_FRESHNESS_MS }
        return nmea.split("\r\n").joinToString("\r\n") { sentence ->
            val body = sentence.removePrefix("$").substringBefore('*')
            if (!body.startsWith("GPRMC,") && !body.startsWith("GNRMC,")) return@joinToString sentence
            val fields = body.split(',').toMutableList()
            if (fields.size < 9 || fields[2] != "A") return@joinToString sentence
            fields[8] = value?.let { String.format(Locale.US, "%.2f", it.degrees) } ?: ""
            val updated = fields.joinToString(",")
            val checksum = updated.fold(0) { sum, char -> sum xor char.code }
            "$" + updated + "*" + String.format(Locale.US, "%02X", checksum)
        }
    }

    override fun close() = stop()

    private companion object { const val DR_FRESHNESS_MS = 1500L }
}
