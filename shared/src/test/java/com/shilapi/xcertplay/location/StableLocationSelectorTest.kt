package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.location.StableLocationSelector.Source.*
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import org.junit.Assert.*
import org.junit.Test

class StableLocationSelectorTest {
    private val selector = StableLocationSelector(0)
    private fun sample(time: Long, source: StableLocationSelector.Source = GPS,
                       latitude: Double = 30.0, accuracy: Double? = 10.0,
                       timestamp: Long = time) = StableLocationSelector.Sample(source, time,
        CarPlayLocationFix(latitude, 120.0, bearingDegrees = 90.0,
            accuracyMeters = accuracy, timestampMillis = timestamp))
    private fun offer(time: Long, source: StableLocationSelector.Source = GPS,
                      latitude: Double = 30.0, accuracy: Double? = 10.0) {
        selector.offer(sample(time, source, latitude, accuracy), time)
    }

    @Test fun futureTimestampCannotBlockLaterRealSamples() {
        selector.offer(sample(60_000), 1_000)
        for (time in listOf(2_000L, 3_000L)) {
            offer(time)
            assertNull(selector.latest(time))
        }
        offer(4_000)
        assertNotNull(selector.latest(4_000))
    }

    @Test fun noFixAtStartupRequiresThreeDistinctGpsSamples() {
        assertFalse(selector.hasValidGps(10_000))
        for (time in listOf(11_000L, 12_000L)) {
            offer(time)
            assertNull(selector.latest(time))
        }
        offer(13_000)
        assertNotNull(selector.latest(13_000))
    }

    @Test fun newerNetworkNeverDisplacesHealthyGps() {
        offer(1_000)
        for (t in listOf(1_100L, 2_100L, 3_100L)) offer(t, NETWORK, 30.001)
        assertEquals(30.0, selector.latest(3_100)!!.latitudeDegrees, 0.0)
    }

    @Test fun garageLossNeverFallsBackToNetworkAndGpsRecoveryNeedsThreeFixes() {
        offer(1_000)
        assertNotNull(selector.latest(1_000))
        for (t in listOf(5_000L, 6_000L, 7_000L)) offer(t, NETWORK)
        assertNull(selector.latest(7_000))
        assertFalse(selector.hasValidGps(7_000))
        for (t in listOf(8_000L, 9_000L)) {
            offer(t)
            assertNull(selector.latest(t))
        }
        offer(10_000)
        assertNotNull(selector.latest(10_000))
    }

    @Test fun duplicateCallbacksCannotSatisfyRecoveryConfirmation() {
        selector.invalidateGps()
        offer(6_000)
        repeat(5) {
            offer(6_000)
            assertNull(selector.latest(6_000))
        }
        offer(7_000)
        assertNull(selector.latest(7_000))
        offer(8_000)
        assertNotNull(selector.latest(8_000))
        assertNull(selector.latest(8_001))
        assertTrue(selector.hasValidGps(8_001))
    }

    @Test fun rejectsStaleFutureUnknownAndPoorAccuracyFixes() {
        for ((time, accuracy) in listOf(1_000L to 10.0, 10_001L to 10.0,
            10_000L to null, 10_000L to 31.0, 10_000L to Double.NaN)) {
            val filter = StableLocationSelector(0)
            filter.offer(sample(time, accuracy = accuracy), 10_000)
            assertNull(filter.latest(10_000))
        }
        offer(6_000, NETWORK, accuracy = 51.0)
        assertNull(selector.latest(6_000))
    }

    @Test fun expiredGpsIsWithheldBeforeNetworkFallback() {
        offer(1_000)
        assertNull(selector.latest(4_001))
        assertNull(selector.latest(40_000))
    }

    @Test fun singleThreeHundredMeterJumpIsRejectedAndNormalTrackResumes() {
        offer(1_000)
        assertNotNull(selector.latest(1_000))
        offer(2_000, latitude = 30.003)
        assertNull(selector.latest(2_000))
        offer(3_000, latitude = 30.0001)
        assertEquals(30.0001, selector.latest(3_000)!!.latitudeDegrees, 0.0)
    }

    @Test fun threeCoherentJumpedPointsCanReestablishPosition() {
        offer(1_000)
        selector.latest(1_000)
        for (t in listOf(2_000L, 3_000L)) {
            offer(t, latitude = 31.0)
            assertNull(selector.latest(t))
        }
        offer(4_000, latitude = 31.0)
        assertEquals(31.0, selector.latest(4_000)!!.latitudeDegrees, 0.0)
    }

    @Test fun confirmationResetsAfterBadFixOrLongGap() {
        selector.invalidateGps()
        offer(6_000)
        offer(7_000, accuracy = 200.0)
        offer(8_000)
        assertNull(selector.latest(8_000))
        offer(9_000)
        assertNull(selector.latest(9_000))
        offer(20_000)
        assertNull(selector.latest(20_000))
        offer(21_000)
        assertNull(selector.latest(21_000))
        offer(22_000)
        assertNotNull(selector.latest(22_000))
    }

    @Test fun monotonicAgeSurvivesWallClockChanges() {
        selector.offer(sample(1_000, timestamp = 9_999_999), 1_000)
        assertNotNull(selector.latest(1_000))
        selector.offer(sample(2_000, timestamp = 100), 2_000)
        assertEquals(100L, selector.latest(2_000)!!.timestampMillis)
    }

    @Test fun trustedParkingSpeedPinsSmallJitterAndMovementReleasesImmediately() {
        offer(1_000)
        selector.latest(1_000, 0.0)
        offer(2_000, latitude = 30.00005)
        val parked = selector.latest(2_000, 0.0)!!
        assertEquals(30.0, parked.latitudeDegrees, 0.0)
        assertEquals(2_000L, parked.timestampMillis)
        assertNull(parked.bearingDegrees)
        offer(3_000, latitude = 30.0001)
        assertEquals(30.0001, selector.latest(3_000, 5.0)!!.latitudeDegrees, 0.0)
    }

    @Test fun lowSpeedClearsCourseAndDoesNotInventMissingCourse() {
        val movingSlowly = sample(1_000).copy(fix = sample(1_000).fix.copy(speedMetersPerSecond = 0.2))
        selector.offer(movingSlowly, 1_000)
        assertNull(selector.latest(1_000, 0.2)!!.bearingDegrees)
        val next = sample(2_000).copy(fix = sample(2_000).fix.copy(bearingDegrees = null))
        selector.offer(next, 2_000)
        assertNull(selector.latest(2_000, 0.0)!!.bearingDegrees)
    }

    @Test fun noTrustedSpeedDoesNotFreezePosition() {
        offer(1_000)
        selector.latest(1_000)
        offer(2_000, latitude = 30.00005)
        assertEquals(30.00005, selector.latest(2_000)!!.latitudeDegrees, 0.0)
    }
}
