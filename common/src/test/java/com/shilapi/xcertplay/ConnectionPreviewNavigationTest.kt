package com.shilapi.xcertplay

import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ConnectionPreviewNavigationTest {
    @Test fun settingsOpensSettingsInsteadOfClosingThePreview() {
        val controller = Robolectric.buildActivity(ConnectionPreviewActivity::class.java).create()
        val activity = controller.get()
        try {
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val waiting = content.getChildAt(0) as ConnectionWaitingView
            waiting.getChildAt(1).performClick()
            val next = shadowOf(activity).nextStartedActivity!!
            assertEquals(DiPlayActivity::class.java.name, next.component?.className)
            assertEquals("settings", next.getStringExtra("page"))
            assertTrue(DiPlayActivity.explicitSettingsEntryPending)
            assertFalse(activity.isFinishing)
        } finally {
            controller.destroy()
            DiPlayActivity.explicitSettingsEntryPending = false
        }
    }
}
