package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ConnectionHostStartupTest {
    @Test fun disabledAutoConnectShowsConnectionPageWithoutStartingASession() {
        CarPlayBackgroundSession.clear()
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java,
            Intent().putExtra(ConnectionEntryPolicy.EXTRA_START, false)).create()
        val activity = controller.get()
        try {
            assertFalse(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "startupRequested"))
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(activity, "connectionStartButton").visibility)
            assertEquals(View.GONE, ReflectionHelpers.getField<View>(activity, "connectionProgress").visibility)
        } finally { controller.destroy(); CarPlayBackgroundSession.clear() }
    }
}
