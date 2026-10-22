// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** The three user-facing P7 transports. Saving only prepares the next connection. */
internal enum class P7ConnectionMethod {
    HOTSPOT, WIFI, USB;

    val wireless: Boolean get() = this != USB

    fun save(context: Context) {
        if (wireless) AirPlayPersistence.saveWirelessHotspotMode(context,
            if (this == WIFI) WirelessHotspotMode.EXISTING_WIFI else WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveWirelessEnabled(context, wireless)
        DiPlayPreferences.saveDefaultConnectionMode(context,
            if (wireless) DefaultConnectionMode.WIRELESS else DefaultConnectionMode.USB)
    }

    companion object {
        fun current(context: Context): P7ConnectionMethod = when {
            !DiPlayPreferences.autoConnectWireless(context) -> USB
            AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.EXISTING_WIFI -> WIFI
            else -> HOTSPOT
        }
    }
}
