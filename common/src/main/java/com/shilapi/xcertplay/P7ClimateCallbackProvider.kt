package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Receive only the stock HVAC's single-mode query result, never vehicle commands. */
class P7ClimateCallbackProvider : ContentProvider() {
    private fun checkCaller() {
        val ctx = checkNotNull(context)
        check(Binder.getCallingUid() == ctx.packageManager.getApplicationInfo(OEM_PACKAGE, 0).uid) {
            P7ClimateDiagnostics.note(ctx, "callback", "caller-rejected")
            "Only the stock climate app may deliver climate state"
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        if (method != SERVICE) return null
        P7ClimateDiagnostics.note(checkNotNull(context), "callback_provider", "requested")
        val descriptor = "${checkNotNull(context).packageName}.$SERVICE"
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                checkCaller()
                if (code == IBinder.INTERFACE_TRANSACTION) {
                    reply?.writeString(descriptor)
                    return true
                }
                if (code != 0) return super.onTransact(code, data, reply, flags)
                P7ClimateDiagnostics.note(checkNotNull(context), "callback_binder", "received")
                data.enforceInterface(descriptor)
                val uri = Uri.CREATOR.createFromParcel(data)
                val token = uri.getQueryParameter("token")
                val requestedAt = uri.getQueryParameter("requestedAt")?.toLongOrNull() ?: return false
                val result = uri.getQueryParameter("result")?.takeIf { it.length <= 1024 }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
                if (result.optString("event") != EVENT) return false
                // OEM getIntelligentPassengerStatus returns the single-mode boolean as int.
                val value = result.opt("result")
                val enabled = when (value) {
                    is Int -> value.takeIf { it == 0 || it == 1 }
                    else -> null
                } ?: return false
                synchronized(lock) {
                    val age = SystemClock.elapsedRealtime() - requestedAt
                    if (token != null && token == session && age in 0..MAX_AGE_MS && requestedAt > observedAt) {
                        observedAt = requestedAt
                        state = enabled
                        P7ClimateDiagnostics.note(checkNotNull(context), "callback", "state-accepted")
                    }
                }
                reply?.writeNoException()
                reply?.writeValue(null)
                return true
            }
        }
        val parameters = JSONArray().apply {
            listOf("result", "token", "requestedAt").forEach {
                put(JSONObject().put("alias", it).put("name", it))
            }
        }
        val manifest = JSONObject().put("authority", descriptor).put("DESCRIPTOR", descriptor)
            .put("TRANSACTION", JSONArray().put(JSONObject().put("path", "onResult")
                .put("METHOD", "onResult").put("ID", 0).put("parameter", parameters)))
        return Bundle().apply { putBinder("binder", callback); putString("manifest", manifest.toString()) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    internal companion object {
        const val OEM_PACKAGE = "com.xiaopeng.carhvac"
        const val SERVICE = "ClimateCallback"
        const val EVENT = "ac.intelligent.passenger.status"
        private const val MAX_AGE_MS = 2500L
        private val lock = Any()
        private var session: String? = null
        private var state: Int? = null
        private var observedAt = 0L

        fun open(): String = synchronized(lock) {
            UUID.randomUUID().toString().also { session = it; state = null; observedAt = 0 }
        }
        fun close(token: String?) = synchronized(lock) {
            if (session == token) { session = null; state = null; observedAt = 0 }
        }
        fun invalidate(token: String?) = synchronized(lock) {
            if (session == token) { state = null; observedAt = SystemClock.elapsedRealtime() }
        }
        fun read(token: String?): Int? = synchronized(lock) {
            state.takeIf { token != null && token == session && SystemClock.elapsedRealtime() - observedAt in 0..MAX_AGE_MS }
        }
    }
}
