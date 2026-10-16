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

    @Test fun newerNetworkNeverDisplacesHealthyGps() {
        offer(1_000)
        for (t in listOf(1_100L, 2_100L, 3_100L)) offer(t, NETWORK, 30.001)
        assertEquals(30.0, selector.latest(3_100)!!.latitudeDegrees, 0.0)
    }

    @Test fun fallbackWaitsFiveSecondsAndRecoveryNeedsThreeNewGpsFixes() {
        offer(1_000)
        assertNotNull(selector.latest(1_000))
        for (t in listOf(3_000L, 4_000L, 5_000L)) offer(t, NETWORK, 30.001)
        assertNull(selector.latest(5_000))
        offer(6_000, NETWORK, 30.001)
        assertEquals(30.001, selector.latest(6_000)!!.latitudeDegrees, 0.0)
        for (t in listOf(7_000L, 8_000L)) {
            offer(t)
            offer(t + 1, NETWORK, 30.001)
            assertEquals(30.001, selector.latest(t + 1)!!.latitudeDegrees, 0.0)
        }
        offer(9_000)
        assertEquals(30.0, selector.latest(9_000)!!.latitudeDegrees, 0.0)
    }

    @Test fun pollingOrDuplicateCallbacksCannotSatisfyNetworkConfirmation() {
        offer(6_000, NETWORK)
        repeat(5) {
            offer(6_000, NETWORK)
            assertNull(selector.latest(6_000))
        }
        offer(7_000, NETWORK)
        assertNull(selector.latest(7_000))
        offer(8_000, NETWORK)
        assertNotNull(selector.latest(8_000))
        assertNull(selector.latest(8_001)) // Never resend a cached point as a new fix.
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
        offer(6_000, NETWORK)
        offer(7_000, NETWORK, accuracy = 200.0)
        offer(8_000, NETWORK)
        assertNull(selector.latest(8_000))
        offer(9_000, NETWORK)
        assertNull(selector.latest(9_000))
        offer(20_000, NETWORK)
        assertNull(selector.latest(20_000))
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

    @Test fun noTrustedSpeedDoesNotFreezePosition() {
        offer(1_000)
        selector.latest(1_000)
        offer(2_000, latitude = 30.00005)
        assertEquals(30.00005, selector.latest(2_000)!!.latitudeDegrees, 0.0)
    }
}
