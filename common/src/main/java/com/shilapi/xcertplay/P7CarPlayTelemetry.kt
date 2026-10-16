package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.*
import kotlin.math.roundToLong

/** Typed, session-owned readings. Units/charge enums come from the E28 Car SDK. */
internal class P7CarPlayTelemetry(private val elapsedMillis: () -> Long) {
    private data class Value(val number: Double, val receivedAt: Long)
    private val values = mutableMapOf<Int, Value>()
    private var gear: VehicleGear? = null
    private var gearAt = 0L
    private val samples = ArrayList<VehicleSpeedSample>()

    fun accept(id: Int, text: String?) {
        if (id !in PROPERTY_IDS) return
        val number = text?.toDoubleOrNull()?.takeIf { it.isFinite() }
        if (number == null) values.remove(id) else values[id] = Value(number, elapsedMillis())
        when (id) {
            GEAR -> {
                val next = when (number) {
                    1.0 -> VehicleGear.DRIVE
                    2.0 -> VehicleGear.NEUTRAL
                    3.0 -> VehicleGear.REVERSE
                    4.0 -> VehicleGear.PARK
                    else -> null
                }
                if (next != gear) samples.clear()
                gear = next
                gearAt = elapsedMillis()
            }
            SPEED -> {
                if (number == null || number !in 0.0..300.0) samples.clear() else {
                    samples.add(VehicleSpeedSample(elapsedMillis(), number / 3.6))
                    while (samples.size > 40) samples.removeAt(0)
                }
            }
        }
    }

    fun snapshot(): VehicleStatusSnapshot? {
        val percent = fresh(SOC)?.takeIf { it in 0.0..100.0 } ?: return null
        val range = fresh(WLTP_RANGE)?.takeIf { it in 0.0..2000.0 } ?: return null
        // Standard Android Car properties use Wh; the vendor's *_BATTCAP counters do not.
        val capacity = values[CAPACITY_WH]?.number?.takeIf { it in 1_000.0..500_000.0 }?.roundToLong()
        val energy = fresh(ENERGY_WH)?.takeIf { it in 0.0..500_000.0 &&
            (capacity == null || it <= capacity) }?.roundToLong()
        val chargeState = fresh(CHARGE_STATUS)
        // CarVcuManager.CHARGE_STATUS_CHARGING == 2; gun connection alone says nothing about charging.
        val charging = when (chargeState) {
            2.0 -> true
            0.0, 1.0, 3.0, 4.0, 5.0, 6.0, 7.0, 16.0, 17.0, 18.0, 19.0, 20.0 -> false
            else -> null
        }
        val connector = if (charging == true) when (fresh(CHARGE_GUN)) {
            1.0 -> EvChargingConnector.GB_T_AC
            2.0 -> EvChargingConnector.GB_T_DC
            else -> null
        } else null
        return VehicleStatusSnapshot(range.toInt(), percent <= 20, percent,
            currentChargeWh = energy, maxChargeWh = capacity, maxRangeKm = null,
            charging = charging, minChargeWh = null, activeConnector = connector)
    }

    fun drainSpeed(): VehicleSpeedReading? {
        val now = elapsedMillis()
        val currentGear = gear.takeIf { now - gearAt in 0..3_000L }
        val fresh = samples.filter { now - it.elapsedMillis in 0..3_000L }
        samples.clear()
        return if (currentGear == null || fresh.isEmpty()) null else VehicleSpeedReading(currentGear, fresh)
    }

    fun clearMotion() { samples.clear(); gear = null; gearAt = 0 }
    fun clear() { values.clear(); clearMotion() }
    private fun fresh(id: Int) = values[id]?.takeIf { elapsedMillis() - it.receivedAt in 0..90_000L }?.number

    companion object {
        const val GEAR = 557847045
        const val SPEED = 559944229
        const val SOC = 557847042
        const val WLTP_RANGE = 559944315
        const val CHARGE_STATUS = 557847081
        const val CHARGE_GUN = 557847080
        const val CAPACITY_WH = 291504390 // INFO_EV_BATTERY_CAPACITY
        const val ENERGY_WH = 291504905 // EV_BATTERY_LEVEL
        val PROPERTY_IDS = setOf(GEAR, SPEED, SOC, WLTP_RANGE, CHARGE_STATUS, CHARGE_GUN, CAPACITY_WH, ENERGY_WH)
    }
}
