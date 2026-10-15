package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

/** Reads only the five known P7 fields from the vehicle service's last reported events. */
internal object VehicleInformationProbe {
    internal val fields = listOf(
        "VCU_CURRENT_GEARLEV" to R.string.settings_probe_gear,
        "VCU_RAW_CAR_SPEED" to R.string.settings_probe_speed,
        "VCU_ACCPEDAL_SIG" to R.string.settings_probe_accelerator,
        "VCU_BRKPEDAL_ST" to R.string.settings_probe_brake,
        "VCU_BMS_SOCDISP" to R.string.settings_probe_battery,
    )

    internal fun gear(raw: String?): String = when (raw) {
        "4" -> "P (4)"
        "1" -> "D (1)"
        "2" -> "N (2)"
        "3" -> "R (3)"
        null -> "—"
        else -> "? ($raw)"
    }

    fun format(context: Context, values: List<String?>): String = fields.mapIndexed { index, (_, title) ->
        val raw = values.getOrNull(index)
        val value = if (index == 0) gear(raw) else raw ?: "—"
        "${context.getString(title)}: $value"
    }.joinToString("\n")

    fun empty(context: Context) = format(context, emptyList())
}

/** Native CarPropertyManager subscription. No polling and no ADB shell helper. */
internal class VehicleInformationMonitor(
    private val context: Context,
    private val onValues: (List<String?>) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val ids = listOf(557847045, 559944229, 557847064, 557847063, 557847042)
    private val values = MutableList<String?>(5) { null }
    private var stopped = false
    private var car: Any? = null
    private var manager: Any? = null
    private var listener: Any? = null

    fun start() {
        try {
            val carClass = Class.forName("android.car.Car")
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                    if (stopped) return
                    try {
                        val properties = carClass.getMethod("getCarManager", String::class.java).invoke(car, "property")
                            ?: error("Vehicle properties unavailable")
                        manager = properties
                        val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
                        val callback = java.lang.reflect.Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
                            when (method.name) {
                                "onChangeEvent" -> args?.firstOrNull()?.let { property ->
                                    val type = property.javaClass
                                    val id = type.getMethod("getPropertyId").invoke(property) as Int
                                    val index = ids.indexOf(id)
                                    if (index >= 0 && !stopped) {
                                        val available = type.getMethod("getStatus").invoke(property) == 0
                                        val value = if (available) type.getMethod("getValue").invoke(property) as? Number else null
                                        update(index, value?.toString())
                                    }
                                }
                                "onErrorEvent" -> (args?.firstOrNull() as? Int)?.let { id ->
                                    val index = ids.indexOf(id)
                                    if (index >= 0 && !stopped) update(index, null)
                                }
                                "hashCode" -> System.identityHashCode(proxy)
                                "equals" -> proxy === args?.firstOrNull()
                                "toString" -> "XPlayVehicleEvents"
                                else -> null
                            }
                        }
                        listener = callback
                        val register = properties.javaClass.getMethod("registerCallback", callbackClass,
                            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                        var registered = 0
                        ids.forEach { id ->
                            runCatching { if (register.invoke(properties, callback, id, 0f) == true) registered++ }
                        }
                        if (registered == 0) fail()
                        // The service sends the initial value on registration; later updates are events.
                    } catch (_: Exception) { fail() }
                }
                override fun onServiceDisconnected(name: android.content.ComponentName) { fail() }
            }
            car = carClass.getMethod("createCar", Context::class.java, android.content.ServiceConnection::class.java,
                android.os.Handler::class.java).invoke(null, context, connection, android.os.Handler(android.os.Looper.getMainLooper()))
                ?: error("Vehicle service unavailable")
            carClass.getMethod("connect").invoke(car)
        } catch (_: Exception) { fail() }
    }

    private fun update(index: Int, value: String?) {
        if (values[index] == value) return
        values[index] = value
        onValues(values.toList())
    }

    private fun fail() {
        if (stopped) return
        stop()
        onValues(emptyList())
        onClosed()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        val properties = manager
        val callback = listener
        if (properties != null && callback != null) runCatching {
            properties.javaClass.methods.first { it.name == "unregisterCallback" && it.parameterTypes.size == 1 }
                .invoke(properties, callback)
        }
        car?.let { runCatching { it.javaClass.getMethod("disconnect").invoke(it) } }
        car = null
        manager = null
        listener = null
    }
}
