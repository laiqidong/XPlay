package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class VehicleBridgeOutputTest {
    @Test fun fragmentedCallbacksProduceSnapshotsWithoutDuplicatesAndStopSuppressesUpdates() {
        val snapshots = mutableListOf<List<String?>>()
        var stopped = false
        var ready = 0
        val output = VehicleBridgeOutput(false, { stopped }, snapshots::add, onReady = { ready++ })
        output.feed("VALUE,557847045,")
        assertTrue(snapshots.isEmpty())
        output.feed("4\nVALUE,557847063,1\nREADY,25\n")
        assertEquals(1, ready)
        assertEquals("4", snapshots.last()[0])
        assertEquals("1", snapshots.last()[3])
        output.feed("VALUE,557847063,1\n")
        assertEquals(2, snapshots.size)
        stopped = true
        output.feed("UNAVAILABLE,557847063\nREADY,25\n")
        assertEquals(2, snapshots.size)
        assertEquals(1, ready)
    }

    @Test fun unavailableInvalidAndUnrecognizedValuesNeverBecomeVehicleMeasurements() {
        val readings = mutableListOf<Pair<Int, String?>>()
        val output = VehicleBridgeOutput(true, { false }, {}, { id, value -> readings += id to value })
        output.feed("VALUE,559944229,36\nUNAVAILABLE,559944229\n")
        output.feed("VALUE,559944229,NaN\nVALUE,559944229,Infinity\n")
        output.feed("INFO,559944229,99\nVALUE,123,100\n")
        assertEquals(listOf(559944229 to "36", 559944229 to null, 559944229 to null,
            559944229 to null), readings)
    }

    @Test fun repeatedIdenticalSpeedCallbacksRemainDistinctMotionSamples() {
        val telemetry = P7CarPlayTelemetry { now }
        val output = VehicleBridgeOutput(true, { false }, {}, telemetry::accept)
        output.feed("VALUE,557847045,1\n")
        output.feed("VALUE,559944229,36\n")
        now += 250
        output.feed("VALUE,559944229,36\n")
        val reading = telemetry.drainSpeed()!!
        assertEquals(2, reading.samples.size)
        assertEquals(250L, reading.samples[1].elapsedMillis - reading.samples[0].elapsedMillis)
    }

    @Test fun unboundedIncompleteOutputIsRejected() {
        val output = VehicleBridgeOutput(false, { false }, {})
        try {
            output.feed("x".repeat(32769))
            fail("Unbounded partial line must fail")
        } catch (_: IllegalStateException) {}
    }
    private var now = 10_000L
}
