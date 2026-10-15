package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleInformationProbeTest {
    @Test fun mapsP7Gears() {
        assertEquals("P (4)", VehicleInformationProbe.gear("4"))
        assertEquals("D (1)", VehicleInformationProbe.gear("1"))
        assertEquals("N (2)", VehicleInformationProbe.gear("2"))
        assertEquals("R (3)", VehicleInformationProbe.gear("3"))
        assertEquals("? (9)", VehicleInformationProbe.gear("9"))
        assertEquals("—", VehicleInformationProbe.gear(null))
    }
}
