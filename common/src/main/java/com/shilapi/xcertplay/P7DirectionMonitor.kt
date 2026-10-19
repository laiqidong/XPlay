package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import dalvik.system.PathClassLoader
import java.lang.reflect.Proxy

/** Read-only original-navigation subscription; reflection and callbacks run on the supplied looper. */
internal class P7DirectionMonitor(context: Context, private val changed: () -> Unit, looper: Looper = Looper.getMainLooper()) {
    private val context = context.applicationContext
    private val main = Handler(looper)
    private var loader: ClassLoader? = null
    private var remote: Any? = null
    private var callback: Any? = null
    private var bound = false
    private var stopped = false
    var connected = false
        private set
    var failed = false
        private set
    data class Sample(val degrees: Float, val receivedAt: Long)
    var gps: Sample? = null
        private set
    var dr: Sample? = null
        private set

    private val receiver = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(PREFIX + "ILocationServiceCallback")
                return true
            }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            return try {
                data.enforceInterface(PREFIX + "ILocationServiceCallback")
                if (data.readInt() != 0) {
                    val type = requireNotNull(loader).loadClass(PREFIX + "LocationImpl")
                    val creator = type.getField("CREATOR").get(null) as Parcelable.Creator<*>
                    val location = requireNotNull(creator.createFromParcel(data))
                    val category = requireNotNull(type.getMethod("category").invoke(location)).toString()
                    val angle = type.getMethod("angle").invoke(location) as Float
                    val received = SystemClock.elapsedRealtime()
                    main.post {
                        if (!stopped && angle.isFinite() && angle >= 0f && angle < 360f) {
                            val sample = Sample(angle, received)
                            when (category) {
                                "GPS_LOCATION" -> gps = sample
                                "DR_LOCATION" -> dr = sample
                            }
                            changed()
                        }
                    }
                }
                reply?.writeNoException()
                true
            } catch (_: Exception) {
                main.post { if (!stopped) fail() }
                false
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            main.post {
                if (stopped) return@post
                try {
                    val cl = requireNotNull(loader)
                    val api = cl.loadClass(PREFIX + "ILocationServiceInterface")
                    val cb = cl.loadClass(PREFIX + "ILocationServiceCallback")
                    remote = cl.loadClass(PREFIX + "ILocationServiceInterface\$Stub")
                        .getMethod("asInterface", IBinder::class.java).invoke(null, service)
                    callback = Proxy.newProxyInstance(cl, arrayOf(cb)) { proxy, method, args ->
                        when (method.name) {
                            "asBinder" -> receiver
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.get(0)
                            "toString" -> "XPlayDirectionMonitor"
                            else -> null
                        }
                    }
                    val categories = cl.loadClass("com.xiaopeng.lib.framework.moduleinterface.locationmodule.ILocation\$Category")
                        .getMethod("values").invoke(null) as Array<*>
                    categories.forEachIndexed { index, category ->
                        if (category.toString() in setOf("GPS_LOCATION", "DR_LOCATION"))
                            api.getMethod("subscribe", cb, Int::class.javaPrimitiveType).invoke(remote, callback, index)
                    }
                    connected = true
                    changed()
                } catch (_: Throwable) { fail() }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { main.post { if (!stopped) fail() } }
        override fun onBindingDied(name: ComponentName) { main.post { if (!stopped) fail() } }
        override fun onNullBinding(name: ComponentName) { main.post { if (!stopped) fail() } }
    }
    private val timeout = Runnable { if (!connected && !stopped) fail() }
    fun start() {
        try {
            val apk = context.packageManager.getApplicationInfo(PACKAGE, 0).sourceDir
            loader = PathClassLoader(apk, ClassLoader.getSystemClassLoader())
            bound = context.bindService(Intent().setComponent(ComponentName(PACKAGE,
                PREFIX + "provider.LocationService")), connection, Context.BIND_AUTO_CREATE)
            if (!bound) fail() else main.postDelayed(timeout, 8000)
        } catch (_: Throwable) { fail() }
    }
    private fun fail() {
        connected = false
        failed = true
        gps = null
        dr = null
        stop()
        changed()
    }
    fun stop() {
        stopped = true
        connected = false
        main.removeCallbacksAndMessages(null)
        runCatching {
            val cl = requireNotNull(loader)
            cl.loadClass(PREFIX + "ILocationServiceInterface").getMethod("unsubscribe",
                cl.loadClass(PREFIX + "ILocationServiceCallback")).invoke(remote, callback)
        }
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }
    companion object {
        private const val PACKAGE = "com.xiaopeng.montecarlo"
        private const val PREFIX = "com.xiaopeng.lib.framework.locationmodule."
    }
}
