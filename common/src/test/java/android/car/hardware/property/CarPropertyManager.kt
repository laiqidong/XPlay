package android.car.hardware.property

class CarPropertyManager {
    interface CarPropertyEventCallback {
        fun onChangeEvent(value: TestProperty)
        fun onErrorEvent(id: Int, area: Int)
    }
    val subscriptions = mutableSetOf<Int>()
    private var callback: CarPropertyEventCallback? = null
    fun registerCallback(listener: CarPropertyEventCallback, id: Int, rate: Float): Boolean {
        if (android.car.Car.deny) throw SecurityException("denied")
        callback = listener
        subscriptions.add(id)
        return true
    }
    fun unregisterCallback(listener: CarPropertyEventCallback) {
        callback = null
        subscriptions.clear()
    }
    fun emit(id: Int, value: Number) { callback?.onChangeEvent(TestProperty(id, value)) }
}
class TestProperty(private val id: Int, private val value: Number) {
    fun getPropertyId() = id
    fun getStatus() = 0
    fun getValue() = value
}
