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

    @Test fun gpsOnlyExcludesNetworkWithoutFilteringGpsAndExpiresAfterThreeSeconds() {
        val gps = fix(LocationManager.GPS_PROVIDER, 30.0, age = 1_000, accuracyMeters = 50f)
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 31.0))
        val provider = AndroidCarPlayLocationProvider(context, gpsOnlyEnabled = true)
        try {
            assertTrue(provider.start())
            assertEquals(encoded(gps), provider.latestNmea())
            val moved = fix(LocationManager.GPS_PROVIDER, 30.001, accuracyMeters = 50f)
            shadowOf(manager).simulateLocation(moved)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(encoded(moved), provider.latestNmea())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            assertEquals(NmeaLocationEncoder.encodeUnavailable(), provider.latestNmea())
        } finally { provider.stop() }
    }

    @Test fun gpsOnlyWithoutGpsDoesNotUseNetwork() {
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 31.0))
        val provider = AndroidCarPlayLocationProvider(context, gpsOnlyEnabled = true)
        try {
            provider.start()
            assertEquals(NmeaLocationEncoder.encodeUnavailable(), provider.latestNmea())
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
            assertEquals(NmeaLocationEncoder.encodeUnavailable(), provider.latestNmea())
        } finally { provider.stop() }
    }

    @Test fun garageLossReportsVoidRequestedSentenceAndRecoveryNeedsThreeGpsFixes() {
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 30.0))
        val provider = AndroidCarPlayLocationProvider(context, driftProtectionEnabled = true)
        try {
            assertTrue(provider.start())
            assertNotNull(provider.latestNmea())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            provider.onRequested(setOf(2))
            val unavailable = provider.latestNmea()!!
            assertTrue(unavailable.startsWith("$"+"GPRMC,,V,"))
            assertFalse(unavailable.contains("GPGGA"))
            shadowOf(manager).simulateLocation(fix(LocationManager.NETWORK_PROVIDER, 31.0))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(unavailable, provider.latestNmea())
            repeat(3) { index ->
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                shadowOf(manager).simulateLocation(fix(LocationManager.GPS_PROVIDER, 30.0 + 0.00001 * index))
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val nmea = provider.latestNmea()!!
                assertEquals(index < 2, nmea.startsWith("$"+"GPRMC,,V,"))
            }
        } finally { provider.stop() }
    }

    @Test fun disabledGpsImmediatelyReportsNoFixAndHonorsRequestedComponents() {
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 30.0))
        val provider = AndroidCarPlayLocationProvider(context, driftProtectionEnabled = true)
        try {
            assertTrue(provider.start())
            assertNotNull(provider.latestNmea())
            shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(NmeaLocationEncoder.encodeUnavailable(), provider.latestNmea())
            provider.onRequested(setOf(1))
            val gga = provider.latestNmea()!!
            assertTrue(gga.startsWith("$"+"GPGGA,"))
            assertFalse(gga.contains("GPRMC"))
            provider.onRequested(setOf(4))
            assertNull(provider.latestNmea())
        } finally { provider.stop() }
    }

    @Test fun ellipsoidAltitudeIsNotSentAsSeaLevelAndMeasuredNmeaFieldsAreUsed() {
        val gps = fix(LocationManager.GPS_PROVIDER, 30.0).apply { altitude = 900.0 }
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        val provider = AndroidCarPlayLocationProvider(context)
        try {
            assertTrue(provider.start())
            fun gga() = provider.latestNmea()!!.lineSequence().first().substringBefore('*').split(',')
            val unknown = gga()
            assertEquals("", unknown[7])
            assertEquals("", unknown[8])
            assertEquals("", unknown[9])
            val utc = java.text.SimpleDateFormat("HHmmss.SS", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(gps.time))
            val body = "GPGGA,$utc,3000.0000,N,12000.0000,E,1,12,0.8,875.0,M,25.0,M,,"
            val checksum = body.fold(0) { value, char -> value xor char.code }
            val sentence = "$"+body+"*%02X".format(checksum)
            org.robolectric.util.ReflectionHelpers.getField<android.location.OnNmeaMessageListener>(
                provider, "nmeaListener").onNmeaMessage(sentence, gps.time)
            assertEquals(listOf("12", "0.8", "875.0", "M", "25.0", "M"), gga().subList(7, 13))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            assertEquals("", gga()[9]) // Retained Android fix must not inherit expired metadata.
        } finally { provider.stop() }
    }

    @Test fun receiverNoFixImmediatelyInvalidatesThePreviouslyValidGps() {
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 30.0))
        val provider = AndroidCarPlayLocationProvider(context, driftProtectionEnabled = true)
        try {
            assertTrue(provider.start())
            assertNotNull(provider.latestNmea())
            val sentence = NmeaLocationEncoder.encodeUnavailable().lineSequence().first()
            org.robolectric.util.ReflectionHelpers.getField<android.location.OnNmeaMessageListener>(
                provider, "nmeaListener").onNmeaMessage(sentence, System.currentTimeMillis())
            assertEquals(NmeaLocationEncoder.encodeUnavailable(), provider.latestNmea())
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
