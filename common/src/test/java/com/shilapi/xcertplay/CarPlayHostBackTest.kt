package com.shilapi.xcertplay

import android.os.Looper
import java.time.Duration
import android.content.Intent
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostBackTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    }

    @After fun cleanup() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "cancelSystemBack")
        ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(activity, "teardownExecutor").shutdownNow()
    }

    private fun back() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "handleSystemBack")

    @Test fun singleBackOpensVehicleHome() {
        back()
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertTrue(shadowOf(activity).isTaskMovedToBack)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
    }

    @Test fun closingAPicturePanelKeepsCarPlayVisibleUntilNextBack() {
        ReflectionHelpers.setField(activity, "picturePanel", FrameLayout(activity))
        back()
        assertNull(ReflectionHelpers.getField<FrameLayout?>(activity, "picturePanel"))
        assertNull(shadowOf(activity).nextStartedActivity)
        back()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertTrue(shadowOf(activity).isTaskMovedToBack)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun doubleBackOpensSettingsWithoutOpeningHome() {
        back()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        back()
        assertEquals("settings", shadowOf(activity).nextStartedActivity?.getStringExtra("page"))
        assertFalse(shadowOf(activity).isTaskMovedToBack)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun pausingCancelsPendingHome() {
        back()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "cancelSystemBack")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun explicitVehicleButtonEntryStillOpensSettingsImmediately() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showDiPlayHome",
            ReflectionHelpers.ClassParameter(String::class.java, "settings"))
        assertEquals("settings", shadowOf(activity).nextStartedActivity?.getStringExtra("page"))
    }
}
