package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R

internal object VehicleInformationProbe {
    internal val fields = listOf(
        "VCU_CURRENT_GEARLEV" to R.string.settings_probe_gear,
        "VCU_RAW_CAR_SPEED" to R.string.settings_probe_speed,
        "VCU_ACCPEDAL_SIG" to R.string.settings_probe_accelerator,
        "VCU_BRKPEDAL_ST" to R.string.settings_probe_brake,
        "VCU_BMS_SOCDISP" to R.string.settings_probe_battery,
        "VCU_DSTBAT_DISP_CLTC_FLOAT" to R.string.settings_probe_extra_5,
        "VCU_DSTBAT_DISP_WLTP_FLOAT" to R.string.settings_probe_extra_6,
        "VCU_CHARGE_GUN_STATUS" to R.string.settings_probe_extra_7,
        "BCM_DOOR" to R.string.settings_probe_extra_8,
        "BCM_DRIVERSEAT_OCCUPIED" to R.string.settings_probe_extra_9,
        "BCM_DRVSEAT_BELTSBR_WARNING" to R.string.settings_probe_extra_10,
        "BCM_LTURNLAMP_ST" to R.string.settings_probe_extra_11,
        "BCM_RTURNLAMP_ST" to R.string.settings_probe_extra_12,
        "HIGH_BEAM_LIGHTS_STATE" to R.string.settings_probe_extra_13,
        "BCM_PARKING_LAMP" to R.string.settings_probe_extra_14,
        "EPS_STEERING_ANGLE" to R.string.settings_probe_extra_15,
        "ENV_OUTSIDE_TEMPERATURE" to R.string.settings_probe_extra_16,
        "HVAC_INNER_TEMPERATURE" to R.string.settings_probe_extra_17,
        "TPMS_PRFL" to R.string.settings_probe_extra_18,
        "TPMS_PRFR" to R.string.settings_probe_extra_19,
        "TPMS_PRRL" to R.string.settings_probe_extra_20,
        "TPMS_PRRR" to R.string.settings_probe_extra_21,
        "TPMS_TEMP_ALL" to R.string.settings_probe_extra_22,
        "ESC_AVH" to R.string.settings_probe_extra_23,
        "VCU_DRIMODE_FB" to R.string.settings_probe_extra_24
    )
    internal val ids = listOf(557847045,559944229,557847064,557847063,557847042,559944314,559944315,557847080,557915161,557849607,557849612,557849623,557849624,289410561,557849626,559948806,291505923,559946285,559947266,559947267,559947268,559947269,557915663,557851651,557847079)
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
        "${context.getString(title)}: ${if (index == 0) gear(raw) else raw ?: "—"}"
    }.joinToString("\n")
    fun empty(context: Context) = format(context, emptyList())
}

/** A real event subscription through the user's authorized local ADB shell, with an initial snapshot. */
internal class VehicleInformationMonitor(context: Context, onValues: (List<String?>) -> Unit, onClosed: () -> Unit) {
    private val session = VehicleShellSession(context, true, onValues, onClosed)
    fun start() = session.start()
    fun stop() = session.stop()
}

internal class VehicleInformationReader(context: Context, onResult: (List<String?>) -> Unit) {
    private val result = MutableList<String?>(VehicleInformationProbe.ids.size) { null }
    private val session = VehicleShellSession(context, false, { values ->
        values.forEachIndexed { i, value -> result[i] = value }
    }, { onResult(result.toList()) })
    fun start() = session.start()
    fun cancel() = session.stop()
}

internal class VehicleShellSession(
    private val context: Context,
    private val subscribe: Boolean,
    private val onValues: (List<String?>) -> Unit,
    private val onClosed: () -> Unit,
    private val carPlay: Boolean = false,
    private val profile: String = "both",
    private val onField: ((Int, String?) -> Unit)? = null,
    private val onReady: (() -> Unit)? = null,
) {
    @Volatile private var stopped = false
    @Volatile private var adb: LocalAdb? = null
    private val handler = Handler(Looper.getMainLooper())
    private val startTimeout = Runnable {
        Log.e("XPlay-VehicleRead", "ADB bridge startup timeout")
        adb?.cancelPendingOperations()
    }
    fun start() {
        handler.postDelayed(startTimeout, 20_000)
        Thread({
            val root = "/data/local/tmp/xplay-vehicle-${java.util.UUID.randomUUID()}"
            try {
                LocalAdb(AdbKeys.load(context)).use { client ->
                    adb = client
                    if (stopped) return@use
                    val access = client.connect(mayAsk = !carPlay)
                    Log.i("XPlay-VehicleRead", "ADB access=$access subscribe=$subscribe")
                    check(access == LocalAdb.Access.READY) { "ADB $access" }
                    val encoded = android.util.Base64.encodeToString(context.assets.open("p7-probe.jar").use { it.readBytes() }, android.util.Base64.NO_WRAP)
                    check(client.shell("umask 077; : > $root.b64; echo XPLAY_OK")?.contains("XPLAY_OK") == true)
                    for (chunk in encoded.chunked(2000)) {
                        if (stopped) return@use
                        check(client.shell("printf '%s' '$chunk' >> $root.b64; echo XPLAY_OK")?.contains("XPLAY_OK") == true)
                    }
                    check(client.shell("base64 -d $root.b64 > $root.jar && echo XPLAY_OK")?.contains("XPLAY_OK") == true)
                    val output = VehicleBridgeOutput(carPlay, { stopped }, onValues, onField, onReady) {
                        if (!carPlay || partsForLog(it)) Log.i("XPlay-VehicleRead", "bridge $it")
                        if (it.substringBefore(',') in listOf("READY", "DONE")) handler.removeCallbacks(startTimeout)
                    }
                    val mode = if (subscribe) "subscribe" else "read"
                    // Fixed-character paths and arguments only; no user-controlled shell text.
                    val command = "trap 'rm -f $root.jar $root.b64' EXIT; CLASSPATH=$root.jar app_process /system/bin com.laiqidong.xplay.tools.P7VehicleBridge $mode${if (carPlay) " carplay $profile" else ""}"
                    client.stream(command, output::feed)
                }
            } catch (error: Exception) {
                if (!stopped) Log.e("XPlay-VehicleRead", "ADB vehicle bridge failed", error)
            } finally {
                handler.removeCallbacks(startTimeout)
                adb = null
                if (!stopped) onClosed() // Invalidate published data before potentially slow cleanup.
                // Use a fresh connection for cleanup if the event socket was cancelled.
                runCatching { LocalAdb(AdbKeys.load(context)).use { cleanup ->
                    if (cleanup.connect(mayAsk = false) == LocalAdb.Access.READY) cleanup.shell("rm -f $root.jar $root.b64")
                } }
            }
        }, "XPlay-Vehicle-Shell").start()
    }
    private fun partsForLog(line: String) = line.startsWith("READY") || line.startsWith("ERROR") || line.startsWith("INFO,connection")

    fun stop() {
        stopped = true
        handler.removeCallbacks(startTimeout)
        adb?.cancelPendingOperations()
    }
}


/** Streaming bridge protocol: complete lines only, validated values and no updates after stop. */
internal class VehicleBridgeOutput(
    private val carPlay: Boolean,
    private val stopped: () -> Boolean,
    private val onValues: (List<String?>) -> Unit,
    private val onField: ((Int, String?) -> Unit)? = null,
    private val onReady: (() -> Unit)? = null,
    private val onLine: (String) -> Unit = {},
) {
    private val pending = StringBuilder()
    private val values = MutableList<String?>(VehicleInformationProbe.ids.size) { null }

    fun feed(chunk: String) {
        if (stopped()) return
        pending.append(chunk)
        check(pending.length <= 32768) { "Invalid bridge output" }
        while (pending.indexOf("\n") >= 0) {
            val end = pending.indexOf("\n")
            val line = pending.substring(0, end).trim()
            pending.delete(0, end + 1)
            if (stopped()) return
            onLine(line)
            val parts = line.split(',', limit = 3)
            val id = parts.getOrNull(1)?.toIntOrNull()
            val index = VehicleInformationProbe.ids.indexOf(id)
            if (parts[0] in listOf("VALUE", "UNAVAILABLE")) {
                if (carPlay && id in P7CarPlayTelemetry.PROPERTY_IDS) {
                    onField?.invoke(requireNotNull(id), if (parts[0] == "VALUE")
                        parts.getOrNull(2)?.takeIf { it.toDoubleOrNull()?.isFinite() == true } else null)
                }
                if (index >= 0) {
                    val value = if (parts[0] == "VALUE") parts.getOrNull(2)?.takeIf { text ->
                        text.split(" / ").all { it.toDoubleOrNull()?.isFinite() == true }
                    } else null
                    if (!carPlay) onField?.invoke(VehicleInformationProbe.ids[index], value)
                    if (values[index] != value) {
                        values[index] = value
                        onValues(values.toList())
                    }
                }
            }
            if (parts[0] == "READY") onReady?.invoke()
        }
    }
}
