package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class Iap2VehicleStatusReporterTest {
    @Test fun unavailableDataIsNotRetriedInABusyLoopAndStopEndsUpdates() {
        var now = 1_000_000_000L
        var reads = 0
        var reading: VehicleStatusSnapshot? = null
        val reporter = Iap2VehicleStatusReporter({ reads++; reading }, {}, { now })
        val sent = mutableListOf<Iap2Frame>()
        reporter.handle(Iap2Frame(Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES, byteArrayOf()), sent::add)
        repeat(10) { reporter.tick(sent::add) }
        assertEquals(1, reads)
        assertTrue(sent.isEmpty())
        reading = VehicleStatusSnapshot(150, false, 50.0, null, null, null, true)
        now += 30_000_000_000L
        reporter.tick(sent::add)
        assertEquals(1, sent.size)
        reporter.handle(Iap2Frame(Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES, byteArrayOf()), sent::add)
        now += 30_000_000_000L
        reporter.tick(sent::add)
        assertEquals(1, sent.size)
    }
}
