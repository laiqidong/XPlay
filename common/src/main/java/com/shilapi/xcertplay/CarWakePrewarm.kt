package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering

/**
 * Starts the slow, UI-independent parts of a wireless start as soon as the car wakes, while the host
 * activity is still being brought forward: the vehicle data shell and the car hotspot.
 * The controller later finds both ready; the hotspot start is serialized inside [CarHotspotTethering].
 */
internal object CarWakePrewarm {
    private const val TAG = "XPlay-CarWake"

    fun start(context: Context) {
        val app = context.applicationContext
        if (CarPlayBackgroundSession.hasSession() || !ConnectionEntryPolicy.configured(app) ||
            !DiPlayPreferences.autoConnect(app)) return
        P7CarPlayData.startSession(app)
        if (!DiPlayPreferences.autoConnectWireless(app) || !CarHotspotSettings.shouldEnable(
                app, true, AirPlayPersistence.loadWirelessHotspotMode(app))) return
        Thread(
            {
                val result = runCatching {
                    CarHotspotTethering.enable(app, isCancelled = { !CarHotspotSettings.enabled(app) },
                        log = { Log.i(TAG, "wake prewarm $it") })
                }
                Log.i(TAG, "wake prewarm car hotspot result=${result.getOrNull() ?: result.exceptionOrNull()?.javaClass?.simpleName}")
            },
            "xplay-wake-hotspot",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** The car is going to sleep without a session: the vehicle shell has nobody to report to. */
    fun stop() {
        if (!CarPlayBackgroundSession.hasSession()) P7CarPlayData.stopSession()
    }
}
