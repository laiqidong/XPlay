package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionWaitingScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h480dp-xhdpi")
    fun compact() = render("compact", 320, 480)
    @Test @Config(qualifiers = "zh-rCN-w960dp-h540dp-xhdpi")
    fun full() = render("full", 960, 540)
    @Test @Config(qualifiers = "ar-ldrtl-w960dp-h540dp-xhdpi")
    fun arabic() = render("arabic", 960, 540)

    private fun render(name: String, widthDp: Int, heightDp: Int) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        var returned = false
        val view = ConnectionWaitingView(activity) { returned = true }
        view.stage.setText(R.string.connection_waiting_iphone_hotspot)
        activity.setContentView(view)
        val density = activity.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        // Freeze at a visible point in the original ring-resize cycle.
        val phase = ConnectionLoadingIndicator::class.java.getDeclaredField("phase").apply { isAccessible = true }
        for (night in listOf(false, true)) {
            view.applyPalette(night)
            phase.setFloat(view.progress, 0.1f)
            repeat(3) {
                view.forceLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
            }
            assertEquals(activity.getString(R.string.connection_loading), view.title.text)
            assertSame(view.title.parent, view.progress.parent)
            assertTrue("Animation follows the English title", view.progress.left >= view.title.right)
            assertEquals("Connecting title stays on one line at large font scale", 1, view.title.layout.lineCount)
            assertTrue("Title text is not clipped", view.title.layout.getLineWidth(0) <= view.title.width)
            val line = view.title.parent as android.view.ViewGroup
            assertTrue("Animation right=${view.progress.right}, line=${line.width}, title=${view.title.width}, font=${view.title.textSize}, indicator=${view.progress.width}", view.progress.right <= line.width)
            assertEquals(View.GONE, view.help.visibility)
            assertEquals(View.GONE, view.retry.visibility)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/connection-screenshots/$name-${if (night) "dark" else "light"}.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        view.getChildAt(1).performClick()
        assertTrue(returned)
    }
}
