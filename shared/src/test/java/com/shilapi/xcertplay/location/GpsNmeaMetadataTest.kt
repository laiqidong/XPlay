package com.shilapi.xcertplay.location

import org.junit.Assert.*
import org.junit.Test

class GpsNmeaMetadataTest {
    private fun sentence(body: String): String {
        val checksum = body.fold(0) { value, char -> value xor char.code }
        return "$"+body+"*%02X".format(checksum)+"\r\n"
    }
    private val body = "GNGGA,123519.00,4807.0380,N,01131.0000,E,1,12,0.7,545.4,M,46.9,M,,"

    @Test fun preservesRealReceiverMeasurementsAndExpiresThem() {
        val sample = GpsNmeaMetadata.parse(sentence(body), 10_000)!!
        assertTrue(sample.valid)
        assertEquals(12, sample.satellitesUsed)
        assertEquals(0.7, sample.hdop!!, 0.0)
        assertEquals(545.4, sample.altitudeMslMeters!!, 0.0)
        assertEquals(46.9, sample.geoidSeparationMeters!!, 0.0)
        assertTrue(sample.matches(45_319_000, 11_000))
        assertFalse(sample.matches(45_319_000, 13_001))
        assertFalse(sample.matches(45_321_000, 11_000))
        assertFalse(sample.matches(45_319_000, 9_000))
    }
    @Test fun invalidChecksumMalformedFieldsAndNonGgaAreIgnored() {
        assertNull(GpsNmeaMetadata.parse(sentence(body).replace("*", "*FF"), 1))
        assertNull(GpsNmeaMetadata.parse(sentence(body.replace("123519", "996199")), 1))
        assertNull(GpsNmeaMetadata.parse(sentence("GPGGA,1,2"), 1))
        assertNull(GpsNmeaMetadata.parse(sentence(body.replace("GNGGA", "GNRMC")), 1))
    }
    @Test fun noFixWithoutUtcAndEstimatedFixAreNeverSatelliteMeasurements() {
        assertFalse(GpsNmeaMetadata.parse(sentence("GPGGA,,,,,,0,00,,,,,,,"), 1)!!.valid)
        val estimated = GpsNmeaMetadata.parse(sentence(body.replace(",1,12,", ",6,12,")), 1)!!
        assertFalse(estimated.matches(45_319_000, 2))
    }
    @Test fun absentFieldsAndWrongAltitudeUnitsRemainUnknown() {
        val sample = GpsNmeaMetadata.parse(sentence(body.replace(",12,0.7,545.4,M,46.9,M,", ",,,,F,,F,")), 1)!!
        assertNull(sample.satellitesUsed)
        assertNull(sample.hdop)
        assertNull(sample.altitudeMslMeters)
        assertNull(sample.geoidSeparationMeters)
    }
    @Test fun utcMatchingWorksAcrossMidnight() {
        val sample = GpsNmeaMetadata.parse(sentence(body.replace("123519.00", "235959.50")), 1)!!
        assertTrue(sample.matches(86_400_200, 2))
    }
}
