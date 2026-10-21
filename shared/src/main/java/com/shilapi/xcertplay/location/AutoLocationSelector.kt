package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import kotlin.math.*

/** Quality-gated fallback. Provider names never imply satellite provenance. Session-local. */
internal class AutoLocationSelector(private val protection: Boolean, private val gpsMaxAgeMillis: Long = 3000L) {
    enum class Source(val ageMillis: Long, val accuracyLimit: Double) {
        GPS(3000, 30.0), FUSED(5000, 50.0), NETWORK(5000, 100.0)
    }
    private fun maxAge(source: Source) = if (source == Source.GPS) gpsMaxAgeMillis else source.ageMillis
    data class Sample(val source: Source, val elapsedMillis: Long, val fix: CarPlayLocationFix)
    private data class Track(var value: Sample? = null, var count: Int = 0,
        var seen: Long = Long.MIN_VALUE, var suspect: Sample? = null, var suspectCount: Int = 0)
    private val tracks = Source.entries.associateWith { Track() }
    private var lastOutput: Sample? = null
    private var selected: Sample? = null
    private var anchor: CarPlayLocationFix? = null
    var rejection: String? = null
        private set
    var reason: String = "waiting"
        private set

    fun invalidate(source: Source) {
        tracks.getValue(source).apply { value = null; count = 0; suspect = null; suspectCount = 0 }
        reason = "provider_unavailable"
    }
    fun offer(sample: Sample, now: Long) {
        val track = tracks.getValue(sample.source)
        if (now - sample.elapsedMillis !in 0..maxAge(sample.source)) {
            reason = "stale_or_future"; rejection = reason; return
        }
        if (sample.elapsedMillis <= track.seen) return
        track.seen = sample.elapsedMillis
        val accuracy = sample.fix.accuracyMeters
        if (accuracy == null || !accuracy.isFinite() || accuracy <= 0 || accuracy > sample.source.accuracyLimit) {
            invalidate(sample.source); reason = "poor_accuracy"; rejection = reason; return
        }
        val previous = track.value
        if (protection && previous != null && !continuous(previous, sample)) {
            val suspect = track.suspect
            track.suspectCount = if (suspect != null && continuous(suspect, sample) &&
                sample.elapsedMillis - suspect.elapsedMillis <= maxAge(sample.source)) track.suspectCount + 1 else 1
            track.suspect = sample
            reason = "jump"; rejection = reason
            if (track.suspectCount < 3) return
            track.count = 3
        } else {
            track.count = if (previous != null && sample.elapsedMillis - previous.elapsedMillis <= maxAge(sample.source))
                track.count + 1 else 1
        }
        track.suspect = null; track.suspectCount = 0
        track.value = sample
    }
    fun latest(now: Long, speed: Double?): CarPlayLocationFix? {
        selected = Source.entries.asSequence().mapNotNull { source ->
            val track = tracks.getValue(source)
            track.value?.takeIf { now - it.elapsedMillis in 0..maxAge(source) &&
                track.suspect == null && (!protection || track.count >= 3) }
        }.firstOrNull { candidate ->
            val previous = lastOutput
            !protection || previous == null || candidate.source == previous.source ||
                (candidate.elapsedMillis >= previous.elapsedMillis && continuous(previous, candidate))
        }
        val value = selected ?: run { reason = "no_reliable_fix"; return null }
        reason = "selected"
        val previous = lastOutput
        if (previous != null && value.source == previous.source && value.elapsedMillis <= previous.elapsedMillis) return null
        // Never step backwards in time when switching to a slower fallback provider.
        if (previous != null && value.elapsedMillis < previous.elapsedMillis) return null
        val parked = protection && speed?.let { it.isFinite() && it in 0.0..0.3 } == true
        val oldAnchor = anchor
        val pinned = parked && oldAnchor != null && distance(oldAnchor, value.fix) <=
            min(20.0, max(oldAnchor.accuracyMeters ?: 0.0, value.fix.accuracyMeters ?: 0.0))
        val fix = if (pinned) value.fix.copy(latitudeDegrees = oldAnchor!!.latitudeDegrees,
            longitudeDegrees = oldAnchor.longitudeDegrees, speedMetersPerSecond = 0.0, bearingDegrees = null)
            else value.fix.copy(bearingDegrees = value.fix.bearingDegrees.takeUnless {
                protection && (speed ?: value.fix.speedMetersPerSecond)?.let { it < 1.0 } == true
            })
        anchor = if (parked) (if (pinned) oldAnchor else fix) else null
        lastOutput = value
        return fix
    }
    fun available() = selected != null
    fun selectedSample() = selected
    private fun continuous(a: Sample, b: Sample): Boolean {
        val seconds = (b.elapsedMillis - a.elapsedMillis) / 1000.0
        if (seconds < 0) return false
        val speed = a.fix.speedMetersPerSecond?.takeIf { it.isFinite() && it in 0.0..85.0 } ?: 0.0
        return distance(a.fix, b.fix) <= max(55.0, speed + 15.0) * seconds +
            (a.fix.accuracyMeters ?: 0.0) + (b.fix.accuracyMeters ?: 0.0)
    }
    private fun distance(a: CarPlayLocationFix, b: CarPlayLocationFix): Double {
        val x = Math.toRadians(b.latitudeDegrees - a.latitudeDegrees)
        val y = Math.toRadians(b.longitudeDegrees - a.longitudeDegrees)
        val h = sin(x / 2).pow(2) + cos(Math.toRadians(a.latitudeDegrees)) *
            cos(Math.toRadians(b.latitudeDegrees)) * sin(y / 2).pow(2)
        return 6371000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** No coordinates are retained in diagnostics. Times are monotonic reception/source ages. */
object LocationSourceDiagnostics {
    /** Receipt time only: original navigation omits measurement time and accuracy. */
    fun reportDrReception(receivedAt: Long) {
        snapshot = Snapshot("DR", receivedAt, null, "dr_reception_only")
    }
    data class Snapshot(val source: String?, val measuredAt: Long?, val accuracy: Double?, val reason: String, val rejection: String? = null)
    @Volatile var snapshot = Snapshot(null, null, null, "stopped")
        internal set
}


/** Timing only: no coordinates. “Sent” means queued to iAP2, not rendered by iPhone Maps. */
object LocationLatencyDiagnostics {
    data class Snapshot(val lowLatency: Boolean = false, val sourcePeriodMillis: Long? = null,
        val sourceToReceiveMillis: Long? = null, val receiveToSendMillis: Long? = null,
        val sourceToSendMillis: Long? = null)
    @Volatile var snapshot = Snapshot()
        internal set
}
