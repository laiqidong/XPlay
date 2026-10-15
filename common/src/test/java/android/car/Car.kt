package android.car

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.car.hardware.property.CarPropertyManager

/** Test-only model of the device API, never shipped in the application. */
class Car(private val connection: ServiceConnection) {
    val properties = CarPropertyManager()
    var disconnected = false
    fun getCarManager(name: String): Any = properties
    fun connect() = connection.onServiceConnected(ComponentName("car", "service"), Binder())
    fun disconnect() { disconnected = true }

    companion object {
        var latest: Car? = null
        var deny = false
        @JvmStatic fun createCar(context: Context, connection: ServiceConnection, handler: Handler): Car =
            Car(connection).also { latest = it }
    }
}
