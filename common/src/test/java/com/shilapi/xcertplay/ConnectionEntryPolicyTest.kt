package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ConnectionEntryPolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun newInstallNeedsTheGuide() { assertFalse(ConnectionEntryPolicy.configured(context)) }
    @Test fun completedUsbSetupSkipsTheGuideWithoutAPhoneAddress() {
        SetupGuide.markSeen(context)
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.USB)
        assertTrue(ConnectionEntryPolicy.configured(context))
    }
    @Test fun oldSavedPhoneIsCompatibleWithoutTheNewSuccessFlag() {
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "iPhone")
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.WIRELESS)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertTrue(ConnectionEntryPolicy.configured(context))
    }
    @Test fun completedWirelessGuideStillRequiresPhoneAndValidHotspotDetails() {
        SetupGuide.markSeen(context)
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.WIRELESS)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        assertFalse(ConnectionEntryPolicy.configured(context))
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "iPhone")
        assertFalse(ConnectionEntryPolicy.configured(context))
        AirPlayPersistence.saveManualHotspotSsid(context, "Car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
        assertTrue(ConnectionEntryPolicy.configured(context))
    }
    @Test fun successfulUsbSessionIsEnoughForOldInstalls() {
        ConnectionEntryPolicy.markConnected(context)
        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.USB)
        assertTrue(ConnectionEntryPolicy.configured(context))
    }
}
