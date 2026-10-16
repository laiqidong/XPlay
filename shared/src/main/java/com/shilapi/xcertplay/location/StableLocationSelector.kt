package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import kotlin.math.*

/** Session-local GPS priority and continuity filter. All ages use time since boot. */
class StableLocationSelector(private val startedAtMillis: Long) {
    enum class Source { GPS, NETWORK }
    data class Sample(val source: Source, val elapsedMillis: Long, val fix: CarPlayLocationFix)
    private data class Track(var latest: Sample? = null, var count: Int = 0,
                             var suspect: Sample? = null, var suspectCount: Int = 0,
                             var lastSeen: Long = Long.MIN_VALUE)
    private val tracks = Source.entries.associateWith { Track() }
    private var active: Source? = null
    private var lastGoodGpsAt = startedAtMillis
    private var lastOutput: Sample? = null
    private var stationaryAnchor: CarPlayLocationFix? = null
    private var lastSentAt = Long.MIN_VALUE

    fun offer(sample: Sample, nowMillis: Long) {
        val track = tracks.getValue(sample.source)
        // Polling the same fix must never count as several recovery samples.
        if (sample.elapsedMillis <= track.lastSeen) return
        track.lastSeen = sample.elapsedMillis
        val maxAge = if (sample.source == Source.GPS) GPS_AGE else NETWORK_AGE
        val accuracy = sample.fix.accuracyMeters
        if (nowMillis - sample.elapsedMillis !in 0..maxAge || accuracy == null ||
            !accuracy.isFinite() || accuracy <= 0 ||
            accuracy > if (sample.source == Source.GPS) 30.0 else 50.0) {
            track.count = 0
            track.suspect = null
            track.suspectCount = 0
            return
        }
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
        if (sample.source == Source.GPS) lastGoodGpsAt = sample.elapsedMillis
    }

    /** Null means withhold position; never refresh the timestamp of an old coordinate. */
    fun latest(nowMillis: Long, trustedSpeedMetersPerSecond: Double? = null): CarPlayLocationFix? {
        val gpsTrack = tracks.getValue(Source.GPS)
        val networkTrack = tracks.getValue(Source.NETWORK)
        val gps = gpsTrack.latest?.takeIf { nowMillis - it.elapsedMillis in 0..GPS_AGE }
        val network = networkTrack.latest?.takeIf { nowMillis - it.elapsedMillis in 0..NETWORK_AGE }
        val selected = when {
            gps != null && (active != Source.NETWORK || gpsTrack.count >= 3) -> gps
            active == Source.NETWORK && network != null && networkTrack.count >= 3 -> network
            nowMillis - lastGoodGpsAt >= GPS_FALLBACK_DELAY && network != null && networkTrack.count >= 3 -> network
            else -> return null
        }
        // A fallback must also agree with the last output, or have three coherent new points.
        val previous = lastOutput
        val count = tracks.getValue(selected.source).count
        if (previous != null && previous.source != selected.source &&
            !continuous(previous, selected) && count < 3) return null
        active = selected.source
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
        lastOutput = selected
        lastSentAt = selected.elapsedMillis
        return fix
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
        const val NETWORK_AGE = 5_000L
        const val GPS_FALLBACK_DELAY = 5_000L
    }
}
