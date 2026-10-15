package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherIntentTest {
    private var controller: ActivityController<DiPlayActivity>? = null
    private var stops = 0

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }

    @After fun cleanup() {
        controller?.destroy()
        CarPlayBackgroundSession.clear()
    }
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test
    fun onlyAPlainLauncherIntentReturnsToCarPlay() {
        assertTrue(DiPlayActivity.isLauncherIntent(launcher()))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", "settings")))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", null as String?)))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", 123)))
        assertFalse(DiPlayActivity.isLauncherIntent(Intent(Intent.ACTION_MAIN)))
        assertFalse(DiPlayActivity.isLauncherIntent(Intent().putExtra("page", "connection")))
    }

    @Test fun freshLauncherOpensOverviewWithoutStoppingTheSession() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher()).create()
        val activity = controller!!.get()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
    }

    @Test fun launcherDeliveredToExistingSettingsOpensOverviewAndClearsReturnNavigation() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).create()
        val activity = controller!!.get()
        ReflectionHelpers.setField(activity, "connectionSettingsReturnCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, launcher()))
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(ReflectionHelpers.getField<SettingsCategory?>(activity, "connectionSettingsReturnCategory"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, launcher().putExtra("page", "settings")))
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun recreationRetainsSettingsInsteadOfRedirectingToProjection() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher())
            .create(Bundle().apply { putString("page", "settings") })
        val activity = controller!!.get()
        assertFalse(activity.isFinishing)
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
    }
    private fun overview(): DiPlayActivity {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).create().start()
        return controller!!.get()
    }

    @Test fun singleBackWaitsThenReturnsToCarPlayWithoutStoppingIt() {
        val activity = overview()
        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(349))
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
    }

    @Test fun doubleBackGoesHomeAndCancelsThePendingCarPlayReturn() {
        val activity = overview()
        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        activity.onBackPressedDispatcher.onBackPressed()
        val destination = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, destination?.action)
        assertTrue(destination?.hasCategory(Intent.CATEGORY_HOME) == true)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
    }

    @Test fun backWithoutASessionGoesHomeImmediately() {
        CarPlayBackgroundSession.clear()
        val activity = overview()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(shadowOf(activity).nextStartedActivity?.hasCategory(Intent.CATEGORY_HOME) == true)
    }

    @Test fun backFromACategoryOnlyReturnsToOverview() {
        val activity = overview()
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.AUDIO)
        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun leavingTheActivityCancelsPendingBack() {
        val activity = overview()
        activity.onBackPressedDispatcher.onBackPressed()
        controller!!.resume().pause()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
    }

}
