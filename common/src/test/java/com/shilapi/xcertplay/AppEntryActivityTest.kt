package com.shilapi.xcertplay
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppEntryActivityTest {
    @After fun cleanup() { CarPlayBackgroundSession.clear(); DiPlayActivity.explicitSettingsEntryPending = false }
    @Test fun connectedEntryGoesStraightToCarPlay() {
        CarPlayBackgroundSession.active = true
        val a = Robolectric.buildActivity(AppEntryActivity::class.java).create().get()
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        assertTrue(a.isFinishing)
    }
    @Test fun newInstallOpensTheGuide() {
        CarPlayBackgroundSession.clear()
        val a = Robolectric.buildActivity(AppEntryActivity::class.java).create().get()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, next?.component?.className)
        assertEquals("setup", next?.getStringExtra("page"))
    }
    @Test fun completedSetupOpensConnectionWithoutForcingAutoConnect() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.shilapi.xcertplay.setup.SetupGuide.markSeen(app)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        DiPlayPreferences.saveAutoConnect(app, false)
        val a = Robolectric.buildActivity(AppEntryActivity::class.java).create().get()
        val next = shadowOf(a).nextStartedActivity!!
        assertEquals(CarPlayHostActivity::class.java.name, next.component?.className)
        assertFalse(next.getBooleanExtra(ConnectionEntryPolicy.EXTRA_START, true))
    }
    @Test fun completedSetupRespectsAutomaticConnection() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.shilapi.xcertplay.setup.SetupGuide.markSeen(app)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        DiPlayPreferences.saveAutoConnect(app, true)
        val a = Robolectric.buildActivity(AppEntryActivity::class.java).create().get()
        assertTrue(shadowOf(a).nextStartedActivity!!.getBooleanExtra(ConnectionEntryPolicy.EXTRA_START, false))
    }
    @Test fun explicitReturnStillAllowsSettings() {
        CarPlayBackgroundSession.active = true
        val c = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "settings")).create().start()
        assertFalse(c.get().isFinishing)
        assertNull(shadowOf(c.get()).nextStartedActivity)
        c.stop().destroy()
    }
    @Test fun backgroundResumeRoutesBeforeShowingSettings() {
        CarPlayBackgroundSession.clear()
        val c = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "settings")).setup()
        c.pause().stop()
        CarPlayBackgroundSession.active = true
        c.restart().start()
        assertTrue(c.get().isFinishing)
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(c.get()).nextStartedActivity?.component?.className)
        c.destroy()
    }
    @Test fun stoppedSettingsAcceptsExplicitCarPlayReturnBeforeNewIntentDelivery() {
        CarPlayBackgroundSession.clear()
        val c = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "settings")).setup()
        c.pause().stop()
        CarPlayBackgroundSession.active = true
        DiPlayActivity.explicitSettingsEntryPending = true
        c.restart().start()
        assertFalse(c.get().isFinishing)
        assertNull(shadowOf(c.get()).nextStartedActivity)
        c.stop().destroy()
    }

    @Test fun reopeningDisconnectedSettingsRetriesEnabledAutoConnect() {
        CarPlayBackgroundSession.clear()
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).setup()
        val a = c.get()
        DiPlayPreferences.saveAutoConnect(a, true)
        AirPlayPersistence.saveWirelessEnabled(a, false)
        org.robolectric.util.ReflectionHelpers.setField(a, "setupError", null)
        c.pause().stop()
        c.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        c.restart().start().resume()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        DiPlayPreferences.saveAutoConnect(a, false)
        c.pause().stop().destroy()
    }

}
