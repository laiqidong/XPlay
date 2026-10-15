package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w900dp-h600dp-land")
class UpdateHomeAvailabilityTest {
    @Test fun reopeningRetiresCachedUpstreamUpdateInsteadOfOfferingIt() = verifyRetired()
    @Test @Config(qualifiers = "en-w400dp-h900dp-port")
    fun compactHomeAlsoRetiresCachedUpstreamUpdate() = verifyRetired()

    private fun verifyRetired() {
        val app = RuntimeEnvironment.getApplication()
        UpdateAvailability.save(app, UpdateRelease("v99.0.0", "DiPlay.apk",
            "https://example.com/DiPlay.apk", "https://example.com/SHA256SUMS.txt"))
        UpdateAvailability.saveBackgroundChecksEnabled(app, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertNull(UpdateAvailability.available(app, "0.0.0"))
            assertFalse(UpdateAvailability.backgroundChecksEnabled(app))
            val labels = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertFalse(labels.contains(activity.getString(R.string.update_available_home)))
            assertFalse(labels.contains(activity.getString(R.string.update_download)))
        } finally { controller.pause().stop().destroy(); UpdateAvailability.clearAllForTest(app) }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
