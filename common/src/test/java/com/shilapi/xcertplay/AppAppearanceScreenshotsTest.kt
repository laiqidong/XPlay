package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppAppearanceScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-night-xhdpi")
    fun fullAutoDark() = render("full-auto-dark", 1280, true)

    private fun render(name: String, widthDp: Int, night: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setFontScale(1.5f)
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)
        // A different CarPlay policy must not affect the app's automatic appearance.
        AirPlayPersistence.saveCarPlayNightMode(context, if (night) CarPlayNightMode.DAY else CarPlayNightMode.NIGHT)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val activity = controller.get()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "openSettingsCategory",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val density = activity.resources.displayMetrics.density
            val width = (widthDp * density).toInt()
            val height = (800 * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertSame(DiPlayPalette.of(night), ReflectionHelpers.getField<DiPlayPalette>(activity, "palette"))
            assertFalse(descendants(root).any { it.tag == "app_appearance_button" || it.tag == "settings_search_button" })
            for (button in descendants(root).filterIsInstance<android.widget.Button>()) {
                if (button.text.isEmpty() || button.layout == null) continue
                assertTrue("Clipped button: ${button.text}", button.layout.height <=
                    button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val output = File("build/reports/app-appearance/$name.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally {
            controller.pause().stop().destroy()
            AppAppearanceRuntime.resetForTest()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
