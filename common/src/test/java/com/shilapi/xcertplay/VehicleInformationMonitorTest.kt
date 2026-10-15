package com.shilapi.xcertplay

import android.car.Car
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VehicleInformationMonitorTest {
    @Test fun changesAreDrivenByCallbacksAndStopUnregistersAllFields() {
        Car.deny = false
        val received = mutableListOf<List<String?>>()
        var closed = 0
        val monitor = VehicleInformationMonitor(RuntimeEnvironment.getApplication(), received::add, { closed++ })
        monitor.start()
        val car = Car.latest!!
        assertEquals(5, car.properties.subscriptions.size)
        assertTrue(received.isEmpty())
        car.properties.emit(557847045, 4)
        car.properties.emit(557847063, 1)
        assertEquals(listOf("4", null, null, "1", null), received.last())
        car.properties.emit(557847063, 1)
        assertEquals(2, received.size)
        monitor.stop()
        assertTrue(car.disconnected)
        assertTrue(car.properties.subscriptions.isEmpty())
        car.properties.emit(557847063, 0)
        assertEquals(2, received.size)
        assertEquals(0, closed)
    }

    @Test fun deniedSubscriptionsStopWithoutFallingBackToPolling() {
        Car.deny = true
        try {
            var closed = 0
            val monitor = VehicleInformationMonitor(RuntimeEnvironment.getApplication(), {}, { closed++ })
            monitor.start()
            assertEquals(1, closed)
            assertTrue(Car.latest!!.disconnected)
            assertTrue(Car.latest!!.properties.subscriptions.isEmpty())
        } finally { Car.deny = false }
    }
}
