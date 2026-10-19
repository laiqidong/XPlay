package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.LocationFixSource
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Android location source for iAP2 LocationInformation.
 *
 * Automatic mode quality-gates GPS/fused/network independently. GPS-only is a separate opt-in.
 * Non-satellite fixes carry estimated provenance; protection adds continuity and parking filters.
 */
class AndroidCarPlayLocationProvider(
    context: Context,
    private val preferredLocationAgeMillis: Long = DEFAULT_PREFERRED_LOCATION_AGE_MILLIS,
    private val driftProtectionEnabled: Boolean = false,
    private val gpsOnlyEnabled: Boolean = false,
) : Iap2LocationProvider {
    private val locationManager =
        context.applicationContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()
    private val preferredProviders = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (!gpsOnlyEnabled) add("fused")
        if (!gpsOnlyEnabled) add(LocationManager.NETWORK_PROVIDER)
    }.filter { it in locationManager.allProviders }
    private val latestFixes = ConcurrentHashMap<String, Location>()
    private var stableSelector = StableLocationSelector(SystemClock.elapsedRealtime())
    private var autoSelector = AutoLocationSelector(driftProtectionEnabled)
    private var gpsAvailable: Boolean? = null
    private var nmeaRegistered = false
    private var gpsMetadata: GpsNmeaMetadata? = null
    private val nmeaListener = OnNmeaMessageListener { sentence, _ ->
        val metadata = GpsNmeaMetadata.parse(sentence, SystemClock.elapsedRealtime())
        if (metadata != null) synchronized(stateLock) {
            gpsMetadata = metadata
            if (!metadata.valid) {
                if (driftProtectionEnabled && gpsOnlyEnabled) stableSelector.invalidateGps()
                autoSelector.invalidate(AutoLocationSelector.Source.GPS)
                if (gpsOnlyEnabled) latestFixes.remove(LocationManager.GPS_PROVIDER)
            }
        }
    }
    private var vehicleSpeed: Pair<Long, Double>? = null
    @Volatile private var requestedComponents: Set<Int>? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: return
            if (provider in preferredProviders) {
                latestFixes[provider] = location
                synchronized(stateLock) { if (gpsOnlyEnabled) { if (driftProtectionEnabled) offerStable(location) } else offerAuto(location) }
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            latestFixes.remove(provider)
            synchronized(stateLock) { autoSource(provider)?.let(autoSelector::invalidate) }
            if (driftProtectionEnabled && provider == LocationManager.GPS_PROVIDER) {
                synchronized(stateLock) { stableSelector.invalidateGps() }
            }
        }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {
            if (status != android.location.LocationProvider.AVAILABLE) onProviderDisabled(provider)
        }
    }

    private var started = false

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (started) {
            true
        } else {
            gpsMetadata = null
            autoSelector = AutoLocationSelector(driftProtectionEnabled)
            latestFixes.clear()
            if (gpsOnlyEnabled) latestFixes.clear()
            if (driftProtectionEnabled) {
                stableSelector = StableLocationSelector(SystemClock.elapsedRealtime())
                gpsAvailable = null
                vehicleSpeed = null
                latestFixes.clear()
            }
            nmeaRegistered = try {
                locationManager.addNmeaListener(nmeaListener, android.os.Handler(Looper.getMainLooper()))
            } catch (error: Exception) {
                Log.w(TAG, "Receiver NMEA unavailable; optional GPS measurements will be omitted", error)
                false
            }
            seedLastKnownLocations()
            var subscribedProviders = 0
            for (provider in preferredProviders) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper(),
                    )
                    subscribedProviders++
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to Android location provider $provider", error)
                }
            }
            started = subscribedProviders > 0
            if (!started) {
                Log.w(TAG, "No Android location provider could be started")
            }
            started
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocations() {
        for (provider in preferredProviders) {
            val location = try {
                locationManager.getLastKnownLocation(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read last known location from $provider", error)
                null
            } ?: continue
            latestFixes[provider] = location
            if (gpsOnlyEnabled) { if (driftProtectionEnabled) offerStable(location) } else offerAuto(location)
            Log.i(
                TAG,
                "Seeded $provider location ageMs=${(System.currentTimeMillis() - location.time).coerceAtLeast(0)}",
            )
        }
    }

    override fun stop() {
        val shouldUnregister = synchronized(stateLock) {
            val wasStarted = started
            started = false
            wasStarted
        }
        if (!shouldUnregister && !nmeaRegistered) return
        runCatching { locationManager.removeUpdates(listener) }
        if (nmeaRegistered) runCatching { locationManager.removeNmeaListener(nmeaListener) }
        nmeaRegistered = false
        synchronized(stateLock) { gpsMetadata = null; latestFixes.clear(); vehicleSpeed = null
            autoSelector = AutoLocationSelector(driftProtectionEnabled)
            LocationSourceDiagnostics.snapshot = LocationSourceDiagnostics.Snapshot(null, null, null, "stopped") }
    }

    override fun latestNmea(): String? {
        val requested = requestedComponents
        if (requested != null && requested.none { it == 1 || it == 2 }) return null
        return try {
            val encoded = if (!gpsOnlyEnabled) synchronized(stateLock) {
                val now = SystemClock.elapsedRealtime()
                val fix = autoSelector.latest(now, vehicleSpeed?.takeIf { now - it.first in 0..1500L }?.second)
                val selected = autoSelector.selectedSample()
                LocationSourceDiagnostics.snapshot = LocationSourceDiagnostics.Snapshot(selected?.source?.name,
                    selected?.elapsedMillis, selected?.fix?.accuracyMeters, autoSelector.reason, autoSelector.rejection)
                when {
                    fix != null -> NmeaLocationEncoder.encode(fix)
                    !autoSelector.available() -> NmeaLocationEncoder.encodeUnavailable()
                    else -> null
                }
            } else if (driftProtectionEnabled) synchronized(stateLock) {
                val now = SystemClock.elapsedRealtime()
                val fix = stableSelector.latest(now, vehicleSpeed?.takeIf { now - it.first in 0..1_500L }?.second)
                val available = stableSelector.hasValidGps(now)
                val gps = latestFixes[LocationManager.GPS_PROVIDER]?.takeIf { available }
                LocationSourceDiagnostics.snapshot = LocationSourceDiagnostics.Snapshot(if (available) "GPS" else null,
                    gps?.elapsedRealtimeNanos?.div(1_000_000L), gps?.accuracy?.toDouble(),
                    if (available) "selected" else "no_reliable_fix")
                if (available != gpsAvailable) {
                    gpsAvailable = available
                    Log.i(TAG, if (available) "GPS reliable; reporting valid position" else
                        "GPS unavailable or recovering; reporting no fix; iOS controls location fusion")
                }
                when {
                    fix != null -> NmeaLocationEncoder.encode(fix)
                    !available -> NmeaLocationEncoder.encodeUnavailable()
                    else -> null // The last fix is still valid, but has already been sent.
                }
            } else if (gpsOnlyEnabled) {
                val now = SystemClock.elapsedRealtime()
                val gps = latestFixes[LocationManager.GPS_PROVIDER]?.takeIf {
                    now - it.elapsedRealtimeNanos / 1_000_000L in 0..3_000L &&
                        gpsMetadata?.let { metadata -> !metadata.valid && now - metadata.receivedAtMillis in 0..3000L } != true
                }
                LocationSourceDiagnostics.snapshot = LocationSourceDiagnostics.Snapshot(if (gps != null) "GPS" else null,
                    gps?.elapsedRealtimeNanos?.div(1_000_000L), gps?.accuracy?.toDouble(),
                    if (gps != null) "selected" else "no_reliable_fix")
                gps?.let(::toCarPlayFix)?.let(NmeaLocationEncoder::encode) ?: NmeaLocationEncoder.encodeUnavailable()
            } else null
            encoded?.let { encoded ->
                if (requested == null) encoded else encoded.split("\r\n").filter {
                    (it.startsWith("\$GPGGA,") && 1 in requested) ||
                        (it.startsWith("\$GPRMC,") && 2 in requested)
                }.joinToString("", transform = { "$it\r\n" }).takeIf { it.isNotEmpty() }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not encode Android location as NMEA", error)
            null
        }
    }

    override fun onRequested(components: Set<Int>) { requestedComponents = components.toSet() }

    override fun onVehicleSpeed(reading: VehicleSpeedReading) = synchronized(stateLock) {
        val sample = reading.samples.lastOrNull()
        vehicleSpeed = sample?.let { it.elapsedMillis to it.metersPerSecond }
    }

    private fun autoSource(provider: String?) = when (provider) {
        LocationManager.GPS_PROVIDER -> AutoLocationSelector.Source.GPS
        "fused" -> AutoLocationSelector.Source.FUSED
        LocationManager.NETWORK_PROVIDER -> AutoLocationSelector.Source.NETWORK
        else -> null
    }

    private fun offerAuto(location: Location) {
        val source = autoSource(location.provider) ?: return
        val now = SystemClock.elapsedRealtime()
        if (source == AutoLocationSelector.Source.GPS && gpsMetadata?.let {
                !it.valid && now - it.receivedAtMillis in 0..3000L
            } == true) {
            autoSelector.invalidate(source)
            return
        }
        runCatching {
            autoSelector.offer(AutoLocationSelector.Sample(source,
                location.elapsedRealtimeNanos / 1_000_000L, toCarPlayFix(location)), SystemClock.elapsedRealtime())
            val prior = LocationSourceDiagnostics.snapshot
            LocationSourceDiagnostics.snapshot = prior.copy(reason = autoSelector.reason, rejection = autoSelector.rejection)
        }.onFailure { Log.w(TAG, "Rejected invalid $source location", it) }
    }

    private fun offerStable(location: Location) {
        if (gpsMetadata?.let { !it.valid && SystemClock.elapsedRealtime() - it.receivedAtMillis in 0..3000L } == true) {
            stableSelector.invalidateGps()
            return
        }
        // GPS-only protection never promotes another source into a satellite fix.
        val source = when (location.provider) {
            LocationManager.GPS_PROVIDER -> StableLocationSelector.Source.GPS
            LocationManager.NETWORK_PROVIDER -> StableLocationSelector.Source.NETWORK
            else -> return
        }
        runCatching {
            stableSelector.offer(StableLocationSelector.Sample(source,
                location.elapsedRealtimeNanos / 1_000_000L, toCarPlayFix(location)), SystemClock.elapsedRealtime())
        }.onFailure { Log.w(TAG, "Rejected invalid Android location", it) }
    }

    private fun toCarPlayFix(fix: Location): CarPlayLocationFix {
        val metadata = synchronized(stateLock) {
            gpsMetadata?.takeIf { fix.provider == LocationManager.GPS_PROVIDER &&
                it.matches(fix.time, SystemClock.elapsedRealtime()) }
        }
        val msl = if (Build.VERSION.SDK_INT >= 34 && fix.hasMslAltitude()) fix.mslAltitudeMeters else null
        return CarPlayLocationFix(
            latitudeDegrees = fix.latitude,
            longitudeDegrees = fix.longitude,
            altitudeMeters = metadata?.altitudeMslMeters ?: msl,
            bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
            speedMetersPerSecond = fix.speed.takeIf { fix.hasSpeed() }?.toDouble(),
            accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
            timestampMillis = fix.time,
            satellitesUsed = metadata?.satellitesUsed,
            horizontalDilution = metadata?.hdop,
            geoidSeparationMeters = metadata?.geoidSeparationMeters,
            source = when (fix.provider) {
                LocationManager.GPS_PROVIDER -> LocationFixSource.GPS
                LocationManager.NETWORK_PROVIDER -> LocationFixSource.NETWORK
                else -> LocationFixSource.FUSED
            },
        )
    }

    companion object {
        private const val TAG = "CarPlayLocation"
        private const val UPDATE_INTERVAL_MILLIS = 1_000L
        private const val MIN_DISTANCE_METERS = 0f
        private const val DEFAULT_PREFERRED_LOCATION_AGE_MILLIS = 30_000L
    }
}
