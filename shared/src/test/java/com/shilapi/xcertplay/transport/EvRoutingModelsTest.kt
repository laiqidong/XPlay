package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EvRoutingModelsTest {
    private val reading = VehicleStatusSnapshot(150, false, 50.0, 40_000, 80_000, null, true)

    @Test fun suppliedManufacturerModelsAreEncodedWithoutCreatingDefaultModels() {
        val charging = "{\"model_id\":\"test-charging-model\"}"
        val consumption = "{\"model_id\":123}"
        val fields = Iap2ParameterList.parse(Iap2VehicleStatus.update(reading.copy(
            chargingParametersJson = charging, consumptionParametersJson = consumption)).payload)
        assertEquals(charging + "\u0000", fields.first(26)!!.payload.decodeToString())
        assertEquals(consumption + "\u0000", fields.first(27)!!.payload.decodeToString())
        val unspecified = Iap2ParameterList.parse(Iap2VehicleStatus.update(reading).payload)
        assertNull(unspecified.first(26))
        assertNull(unspecified.first(27))
    }

    @Test fun missingModelIdsInvalidJsonAndOversizedModelsAreOmitted() {
        for (invalid in listOf("{}", "[]", "not-json", "{\"model_id\":\"\"}",
            "{\"model_id\":null}", "{\"model_id\":true}",
            "{\"model_id\":\"real-id\",\"data\":\"${"中".repeat(6_000)}\"}")) {
            val fields = Iap2ParameterList.parse(Iap2VehicleStatus.update(reading.copy(
                chargingParametersJson = invalid, consumptionParametersJson = invalid)).payload)
            assertNull(fields.first(26))
            assertNull(fields.first(27))
        }
    }
}
