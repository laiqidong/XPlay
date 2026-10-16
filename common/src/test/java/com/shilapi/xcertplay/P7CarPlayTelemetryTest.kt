package com.shilapi.xcertplay

import com.shilapi.xcertplay.P7CarPlayTelemetry.Companion.GEAR
import com.shilapi.xcertplay.P7CarPlayTelemetry.Companion.SPEED
import com.shilapi.xcertplay.P7CarPlayTelemetry.Companion.SOC
import com.shilapi.xcertplay.P7CarPlayTelemetry.Companion.WLTP_RANGE
import com.shilapi.xcertplay.P7CarPlayTelemetry.Companion.CHARGE_STATUS
import com.shilapi.xcertplay.P7CarPlayTelemetry.Companion.CHARGE_GUN
import com.shilapi.xcertplay.P7CarPlayTelemetry.Companion.CAPACITY_WH
import com.shilapi.xcertplay.P7CarPlayTelemetry.Companion.ENERGY_WH
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test

class P7CarPlayTelemetryTest {
    private var now = 1_000L
    private val data = P7CarPlayTelemetry { now }
    private fun base() {
        data.accept(SOC, "50")
        data.accept(WLTP_RANGE, "250")
    }

    @Test fun unknownFieldsAreOmittedRatherThanCalculatedFromSoc() {
        base()
        val state = data.snapshot()!!
        assertEquals(50.0, state.batteryPercent, 0.0)
        assertEquals(250, state.rangeKm)
        assertNull(state.currentChargeWh)
        assertNull(state.maxChargeWh)
        assertNull(state.maxRangeKm)
        assertNull(state.minChargeWh)
        assertNull(state.charging)
        assertNull(state.activeConnector)
    }

    @Test fun standardBatteryPropertiesRemainWhAndVendorCountersAreIgnored() {
        base()
        data.accept(CAPACITY_WH, "80000")
        data.accept(ENERGY_WH, "40000")
        data.accept(559944302, "999999") // Vendor cumulative discharge counter is not remaining energy.
        assertEquals(80_000L, data.snapshot()!!.maxChargeWh)
        assertEquals(40_000L, data.snapshot()!!.currentChargeWh)
    }

    @Test fun inconsistentOrInvalidEnergyIsNotSent() {
        base()
        data.accept(CAPACITY_WH, "80000")
        for (value in listOf("90000", "-1", "NaN", "Infinity", null)) {
            data.accept(ENERGY_WH, value)
            assertNull(data.snapshot()!!.currentChargeWh)
        }
    }

    @Test fun connectedGunIsNotTheSameAsChargingAndConnectorFollowsActualType() {
        base()
        data.accept(CHARGE_GUN, "1")
        assertNull(data.snapshot()!!.charging)
        data.accept(CHARGE_STATUS, "1") // Appointment / waiting.
        assertEquals(false, data.snapshot()!!.charging)
        assertNull(data.snapshot()!!.activeConnector)
        data.accept(CHARGE_STATUS, "2")
        assertEquals(true, data.snapshot()!!.charging)
        assertEquals(EvChargingConnector.GB_T_AC, data.snapshot()!!.activeConnector)
        data.accept(CHARGE_GUN, "2")
        assertEquals(EvChargingConnector.GB_T_DC, data.snapshot()!!.activeConnector)
        data.accept(CHARGE_STATUS, "4") // Done, still plugged in.
        assertEquals(false, data.snapshot()!!.charging)
        assertNull(data.snapshot()!!.activeConnector)
        data.accept(CHARGE_STATUS, "15") // Invalid is unknown, not false.
        assertNull(data.snapshot()!!.charging)
    }

    @Test fun staleValuesExpireAndSessionResetDropsStaticCapacityToo() {
        base()
        data.accept(CAPACITY_WH, "80000")
        data.accept(ENERGY_WH, "40000")
        data.accept(CHARGE_STATUS, "2")
        now += 90_001
        assertNull(data.snapshot())
        base()
        assertNull(data.snapshot()!!.currentChargeWh)
        assertNull(data.snapshot()!!.charging)
        assertEquals(80_000L, data.snapshot()!!.maxChargeWh)
        data.clear()
        base()
        assertNull(data.snapshot()!!.maxChargeWh)
    }

    @Test fun motionUsesActualGearAndConvertsKmhOnlyOnce() {
        data.accept(GEAR, "1")
        data.accept(SPEED, "36")
        data.accept(GEAR, "1") // The gear refresh must not erase buffered speed.
        val speed = data.drainSpeed()!!
        assertEquals(VehicleGear.DRIVE, speed.gear)
        assertEquals(10.0, speed.samples.single().metersPerSecond, 0.0)
        assertNull(data.drainSpeed())
        data.accept(SPEED, "36")
        data.accept(GEAR, "3")
        assertNull(data.drainSpeed()) // Never mix samples across D/R.
        data.accept(SPEED, "3.6")
        assertEquals(VehicleGear.REVERSE, data.drainSpeed()!!.gear)
    }

    @Test fun staleGearOrSpeedAndUnavailableEventsAreNotReplayed() {
        data.accept(GEAR, "4")
        data.accept(SPEED, "0")
        now += 3_001
        assertNull(data.drainSpeed())
        data.accept(GEAR, "1")
        data.accept(SPEED, "40")
        data.accept(SPEED, null)
        assertNull(data.drainSpeed())
        base()
        data.accept(SOC, null)
        assertNull(data.snapshot())
    }
}
