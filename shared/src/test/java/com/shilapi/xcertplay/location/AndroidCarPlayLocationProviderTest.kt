package com.shilapi.xcertplay.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidCarPlayLocationProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Before fun setup() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        shadowOf(context).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    private fun fix(provider: String, latitude: Double, age: Long = 0, accuracyMeters: Float = 10f) = Location(provider).apply {
        this.latitude = latitude
        longitude = 120.0
        accuracy = accuracyMeters
        time = System.currentTimeMillis() - age
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - age * 1_000_000
    }
    private fun encoded(location: Location) = NmeaLocationEncoder.encode(CarPlayLocationFix(
        location.latitude, location.longitude, accuracyMeters = location.accuracy.toDouble(), timestampMillis = location.time))

    @Test fun defaultRetainsNewestFixEvenWhenItIsCoarseNetworkLocation() {
        val gps = fix(LocationManager.GPS_PROVIDER, 30.0, age = 1_000)
        val network = fix(LocationManager.NETWORK_PROVIDER, 31.0, accuracyMeters = 500f)
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, network)
        val provider = AndroidCarPlayLocationProvider(context)
        try {
            assertTrue(provider.start())
            assertEquals(encoded(network), provider.latestNmea())
            assertEquals(encoded(network), provider.latestNmea())
        } finally { provider.stop() }
    }

    @Test fun protectionUsesGoodGpsAndDoesNotReplayItOrSendStalePoints() {
        val gps = fix(LocationManager.GPS_PROVIDER, 30.0, age = 1_000)
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 31.0, accuracyMeters = 500f))
        val provider = AndroidCarPlayLocationProvider(context, driftProtectionEnabled = true)
        try {
            provider.start()
            assertEquals(encoded(gps), provider.latestNmea())
            assertNull(provider.latestNmea())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            assertNull(provider.latestNmea())
        } finally { provider.stop() }
    }

    @Test fun staleWheelSpeedCannotFreezeNewGpsCoordinates() {
        val gps = fix(LocationManager.GPS_PROVIDER, 30.0)
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        val provider = AndroidCarPlayLocationProvider(context, driftProtectionEnabled = true)
        try {
            provider.start()
            provider.onVehicleSpeed(VehicleSpeedReading(VehicleGear.PARK,
                listOf(VehicleSpeedSample(SystemClock.elapsedRealtime(), 0.0))))
            assertNotNull(provider.latestNmea())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            val next = fix(LocationManager.GPS_PROVIDER, 30.00005)
            shadowOf(manager).simulateLocation(next)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(encoded(next), provider.latestNmea())
        } finally { provider.stop() }
    }

    @Test fun onlyTheRequestedPositionSentenceIsReturned() {
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER,
            fix(LocationManager.GPS_PROVIDER, 30.0))
        val provider = AndroidCarPlayLocationProvider(context)
        try {
            provider.start()
            provider.onRequested(setOf(2))
            val rmc = provider.latestNmea()!!
            assertTrue(rmc.startsWith("\$GPRMC,"))
            assertFalse(rmc.contains("\$GPGGA,"))
            provider.onRequested(setOf(4))
            assertNull(provider.latestNmea())
        } finally { provider.stop() }
    }
}
