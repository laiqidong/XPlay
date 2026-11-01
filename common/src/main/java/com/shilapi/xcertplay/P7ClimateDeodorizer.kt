package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock

/** OEM app owns the deodorizing sequence and its timer; do not emulate it with fan writes. */
internal class P7ClimateDeodorizer(private val context: Context) {
    @Volatile private var service: IBinder? = null
    private var bound = false
    // Display-only estimate for an explicitly observed XPlay start. OEM owns all actuation.
    private var requestedStartAt: Long? = null
    private var estimatedStartAt: Long? = null
    private var lastReadAt: Long? = null
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
    fun read(): Int? {
        val now = SystemClock.elapsedRealtime()
        if (lastReadAt?.let { now - it > OBSERVATION_GAP_MS } == true) clearEstimate()
        lastReadAt = now
        val enabled = runCatching {
            when (call("isHvacDeodorantEnable", "null")) { "true" -> 1; "false" -> 0; else -> null }
        }.getOrNull()
        val requested = requestedStartAt
        when {
            enabled == null -> clearEstimate()
            requested != null && now - requested > START_CONFIRM_MS -> clearEstimate()
            enabled == 1 && requested != null -> { estimatedStartAt = requested; requestedStartAt = null }
            enabled == 0 && requested == null -> clearEstimate()
        }
        return enabled
    }

    fun set(enabled: Boolean) {
        val wasOff = if (enabled) read() == 0 else false
        clearEstimate()
        call("setHvacDeodorantEnable", enabled.toString())
        if (enabled && wasOff) requestedStartAt = SystemClock.elapsedRealtime()
    }

    fun remainingEstimateSeconds(): Int? {
        val started = estimatedStartAt ?: return null
        val remaining = (DEODOR_DURATION_MS - (SystemClock.elapsedRealtime() - started) + 999) / 1000
        // Never claim completion or send OFF based on our estimate; OEM remains authoritative.
        return remaining.toInt().takeIf { it in 1..180 }
    }

    private fun clearEstimate() { requestedStartAt = null; estimatedStartAt = null }

    private companion object {
        const val DEODOR_DURATION_MS = 180_000L
        const val START_CONFIRM_MS = 3000L
        const val OBSERVATION_GAP_MS = 3000L
    }
}
