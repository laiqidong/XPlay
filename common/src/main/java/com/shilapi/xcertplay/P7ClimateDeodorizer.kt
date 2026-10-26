package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel

/** OEM app owns the deodorizing sequence and its timer; do not emulate it with fan writes. */
internal class P7ClimateDeodorizer(private val context: Context) {
    @Volatile private var service: IBinder? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = binder }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
        override fun onBindingDied(name: ComponentName) { stop() }
        override fun onNullBinding(name: ComponentName) { stop() }
    }
    fun start() {
        if (bound) return
        bound = runCatching {
            context.bindService(Intent().setComponent(ComponentName("com.xiaopeng.carhvac",
                "com.xiaopeng.smartcontrol.ipc.server.ipc.AidlService")), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }
    fun stop() {
        service = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
    }
    // Called only on the climate I/O thread. The OEM IPipeBus call has three input strings
    // (module, method, parameter) and one output string. No command retry or replay.
    private fun call(method: String, parameter: String): String? {
        val binder = checkNotNull(service) { "OEM climate service unavailable" }
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken("com.xiaopeng.ipc.aidl.IPipeBus")
            request.writeString("com.xiaopeng.smartcontrol.sdk.server.hvac.HvacManagerServer")
            request.writeString(method)
            request.writeString(parameter)
            request.writeInt(1)
            check(binder.transact(1, request, reply, 0))
            reply.readException()
            check(reply.readInt() == 0) { "OEM climate call failed" }
            val result = arrayOfNulls<String>(1)
            reply.readStringArray(result)
            return result[0]
        } finally { reply.recycle(); request.recycle() }
    }
    fun read(): Int? = runCatching {
        when (call("isHvacDeodorantEnable", "null")) { "true" -> 1; "false" -> 0; else -> null }
    }.getOrNull()
    fun set(enabled: Boolean) { call("setHvacDeodorantEnable", enabled.toString()) }
}
