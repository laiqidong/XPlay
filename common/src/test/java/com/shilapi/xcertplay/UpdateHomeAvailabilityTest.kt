package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
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
        val preferences = app.getSharedPreferences("diplay_update_availability", android.content.Context.MODE_PRIVATE)
        preferences.edit().putString("tag", "v99.0.0").putString("apk_name", "DiPlay.apk")
            .putString("apk_url", "https://example.com/DiPlay.apk")
            .putString("checksums_url", "https://example.com/SHA256SUMS.txt")
            .putBoolean("background_checks", true).commit()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertEquals(setOf("background_checks"), preferences.all.keys)
            assertFalse(preferences.getBoolean("background_checks", true))
            val labels = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertFalse(labels.contains(activity.getString(R.string.update_available_home)))
            assertFalse(labels.contains(activity.getString(R.string.update_download)))
        } finally { controller.pause().stop().destroy(); preferences.edit().clear().commit() }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
