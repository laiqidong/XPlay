package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import kotlin.math.*

/** Session-local GPS validity and continuity filter. All ages use time since boot. */
class StableLocationSelector(@Suppress("UNUSED_PARAMETER") startedAtMillis: Long) {
    enum class Source { GPS, NETWORK }
    data class Sample(val source: Source, val elapsedMillis: Long, val fix: CarPlayLocationFix)
    private data class Track(var latest: Sample? = null, var count: Int = 0,
                             var suspect: Sample? = null, var suspectCount: Int = 0,
                             var lastSeen: Long = Long.MIN_VALUE)
    private val tracks = Source.entries.associateWith { Track() }
    private var recovering = false
    private var stationaryAnchor: CarPlayLocationFix? = null
    private var lastSentAt = Long.MIN_VALUE

    fun offer(sample: Sample, nowMillis: Long) {
        if (sample.source != Source.GPS) return
        val track = tracks.getValue(sample.source)
        // Polling the same fix must never count as several recovery samples.
        if (sample.elapsedMillis <= track.lastSeen) return
        // Reject impossible ages before advancing the duplicate watermark.
        if (nowMillis - sample.elapsedMillis !in 0..GPS_AGE) {
            invalidateGps()
            return
        }
        track.lastSeen = sample.elapsedMillis
        val maxAge = GPS_AGE
        val accuracy = sample.fix.accuracyMeters
        if (accuracy == null ||
            !accuracy.isFinite() || accuracy <= 0 ||
            accuracy > 30.0) {
            invalidateGps()
            return
        }
        if (track.latest?.let { sample.elapsedMillis - it.elapsedMillis > GPS_AGE } == true) invalidateGps()
        val previous = track.latest
        if (previous != null && !continuous(previous, sample)) {
            val suspect = track.suspect
            track.suspectCount = if (suspect != null &&
                sample.elapsedMillis - suspect.elapsedMillis <= maxAge && continuous(suspect, sample)) {
                track.suspectCount + 1
            } else 1
            track.suspect = sample
            track.count = 0
            // Three coherent new fixes can establish a new position after a bad old fix.
            if (track.suspectCount < 3) return
            track.count = 3
        } else {
            track.count = if (previous != null && sample.elapsedMillis - previous.elapsedMillis <= maxAge) {
                track.count + 1
            } else 1
        }
        track.suspect = null
        track.suspectCount = 0
        track.latest = sample
        if (track.count >= 3) recovering = false
    }

    /** Null means withhold position; never refresh the timestamp of an old coordinate. */
    fun latest(nowMillis: Long, trustedSpeedMetersPerSecond: Double? = null): CarPlayLocationFix? {
        if (!hasValidGps(nowMillis)) return null
        val selected = requireNotNull(tracks.getValue(Source.GPS).latest)
        if (selected.elapsedMillis <= lastSentAt) return null
        val speed = trustedSpeedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 }
        val parked = speed != null && speed <= 0.3
        val anchor = stationaryAnchor
        val pinToAnchor = parked && anchor != null &&
            distance(anchor, selected.fix) <= min(20.0, max(anchor.accuracyMeters ?: 0.0, selected.fix.accuracyMeters ?: 0.0))
        val fix = if (pinToAnchor) {
            requireNotNull(anchor)
            selected.fix.copy(latitudeDegrees = anchor.latitudeDegrees, longitudeDegrees = anchor.longitudeDegrees,
                speedMetersPerSecond = 0.0, bearingDegrees = null)
        } else selected.fix.copy(
            bearingDegrees = selected.fix.bearingDegrees.takeUnless {
                (speed ?: selected.fix.speedMetersPerSecond)?.let { it < 1.0 } == true
            },
        )
        stationaryAnchor = if (parked) (if (pinToAnchor) anchor else fix) else null
        lastSentAt = selected.elapsedMillis
        return fix
    }

    /** Distinguishes a duplicate poll from a lost/invalid fix. */
    fun hasValidGps(nowMillis: Long): Boolean {
        val sample = tracks.getValue(Source.GPS).latest ?: run {
            recovering = true // Once no-fix is reported, even cold startup requires confirmation.
            return false
        }
        if (nowMillis - sample.elapsedMillis !in 0..GPS_AGE) {
            invalidateGps()
            return false
        }
        return !recovering
    }

    fun invalidateGps() {
        tracks.getValue(Source.GPS).apply {
            latest = null
            count = 0
            suspect = null
            suspectCount = 0
        }
        recovering = true
        stationaryAnchor = null
    }

    private fun continuous(a: Sample, b: Sample): Boolean {
        val seconds = (b.elapsedMillis - a.elapsedMillis) / 1000.0
        if (seconds <= 0) return false
        val speed = a.fix.speedMetersPerSecond?.takeIf { it.isFinite() && it in 0.0..85.0 } ?: 0.0
        val allowance = max(55.0, speed + 15.0) * seconds +
            (a.fix.accuracyMeters ?: 0.0) + (b.fix.accuracyMeters ?: 0.0)
        return distance(a.fix, b.fix) <= allowance
    }

    private fun distance(a: CarPlayLocationFix, b: CarPlayLocationFix): Double {
        val lat1 = Math.toRadians(a.latitudeDegrees)
        val lat2 = Math.toRadians(b.latitudeDegrees)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitudeDegrees - a.longitudeDegrees)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val GPS_AGE = 3_000L
    }
}
