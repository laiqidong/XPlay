package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.setup.SetupGuide

/** Launcher routing uses saved configuration, never transient network availability. */
internal object ConnectionEntryPolicy {
    const val EXTRA_LAUNCHER = "connection_launcher"
    const val EXTRA_START = "connection_start"
    private const val SUCCESS_KEY = "connection_succeeded"

    fun markConnected(context: Context) {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().putBoolean(SUCCESS_KEY, true).apply()
    }

    fun configured(context: Context): Boolean {
        val phone = !DiPlayPreferences.phoneAddress(context).isNullOrBlank()
        val established = SetupGuide.seen(context) || phone ||
            context.getSharedPreferences("diplay", Context.MODE_PRIVATE).getBoolean(SUCCESS_KEY, false)
        if (!established) return false
        if (!DiPlayPreferences.autoConnectWireless(context)) return true
        if (!phone) return false
        return when (AirPlayPersistence.loadWirelessHotspotMode(context)) {
            WirelessHotspotMode.MANUAL -> ManualHotspotValidation.error(
                AirPlayPersistence.loadManualHotspotSsid(context), AirPlayPersistence.loadManualHotspotPassphrase(context)) == null
            WirelessHotspotMode.EXISTING_WIFI -> ManualHotspotValidation.error(
                AirPlayPersistence.loadExistingWifiSsid(context), AirPlayPersistence.loadExistingWifiPassphrase(context)) == null
            else -> true
        }
    }
}
