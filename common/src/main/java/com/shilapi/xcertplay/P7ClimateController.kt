package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import org.json.JSONObject
import java.util.concurrent.Executors

internal interface P7ClimateSession {
    fun start()
    fun stop()
    fun close()
    fun request(command: String): Boolean
}

/** One bounded request in flight. Only reads reconnect; writes are never retried or queued. */
internal class P7ClimateController(private val context: Context, private val changed: (JSONObject?) -> Unit,
    private val failed: () -> Unit) : P7ClimateSession {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var active = false
    private var busy = false
    @Volatile private var generation = 0
    private var disposed = false
    private var adb: LocalAdb? = null // worker thread only
    private var shell: LocalAdb.InteractiveShell? = null
    private val poll = Runnable { request("READ") }

    override fun start() {
        if (disposed || active) return
        active = true; generation++; changed(null); request("READ")
    }
    override fun stop() {
        active = false; generation++; main.removeCallbacks(poll)
        worker.execute { disconnect() }
    }
    override fun close() { if (!disposed) { stop(); disposed = true; worker.shutdown() } }
    override fun request(command: String): Boolean {
        if (!active || disposed || busy) return false
        busy = true
        main.removeCallbacks(poll)
        val ticket = generation
        worker.execute {
            var state: JSONObject? = null
            var error = false
            try {
                if (shell == null) {
                    // Never reconnect in order to send a write from an old UI state.
                    check(command == "READ")
                    val connection = LocalAdb(AdbKeys.load(context))
                    adb = connection
                    check(connection.connect(true) == LocalAdb.Access.READY)
                    val path = context.applicationInfo.sourceDir + ":/system/framework/android.car.jar"
                    val quoted = "'" + path.replace("'", "'\\''") + "'"
                    shell = connection.openShell("CLASSPATH=$quoted app_process /system/bin com.shilapi.xcertplay.P7ClimateWorker")
                    check(shell != null)
                }
                if (ticket == generation && active) {
                    val reply = shell!!.exchangeBounded(command, 3000) ?: error("climate stream ended")
                    state = JSONObject(reply.trim())
                    error = !state.optBoolean("ok", false)
                }
            } catch (_: Exception) { disconnect(); error = true }
            val delivered = state
            val hasError = error
            main.post {
                busy = false
                if (active && ticket == generation && !disposed) {
                    changed(delivered)
                    if (hasError && command != "READ") failed()
                    main.postDelayed(poll, if (delivered == null) 2000 else 700)
                } else if (active && !disposed) request("READ")
            }
        }
        return true
    }
    private fun disconnect() {
        runCatching { shell?.close() }; shell = null
        runCatching { adb?.close() }; adb = null
    }
}
