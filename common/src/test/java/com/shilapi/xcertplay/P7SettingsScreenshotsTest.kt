package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P7SettingsScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h900dp-xhdpi")
    fun compactLargeFont() = render("compact-zh", 320)

    @Test @Config(qualifiers = "zh-rCN-w1600dp-h900dp-xhdpi")
    fun fullLargeFont() = render("full-zh", 1600)

    @Test @Config(qualifiers = "ar-ldrtl-w1600dp-h900dp-xhdpi")
    fun arabicLargeFont() = render("full-ar", 1600)

    private fun render(name: String, widthDp: Int) {
        ShadowBuild.setManufacturer("XiaoPeng")
        ShadowBuild.setBrand("XPENG")
        ShadowBuild.setModel("E28")
        val context = RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setFontScale(1.6f)
        InterfaceSize.save(context, 100)
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val activity = controller.get()
            activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
            for (category in SettingsInformationArchitecture.categories(true) + SettingsCategory.DIAGNOSTICS) {
                // Diagnostics is a subpage, reached from Advanced in the real UI.
                ReflectionHelpers.setField(activity, "settingsCategory", category)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val density = activity.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                val height = (900 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                assertTrue(root.height > 0)
                for (text in descendants(root).filterIsInstance<TextView>()) {
                    if (!text.isShown || text.text.isEmpty() || text.width == 0) continue
                    val layout = text.layout ?: continue
                    for (line in 0 until layout.lineCount) assertEquals("Truncated $name/$category: ${text.text}",
                        0, layout.getEllipsisCount(line))
                    assertTrue("Clipped $name/$category: ${text.text} layout=${layout.height} height=${text.height}", layout.height <=
                        text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val output = File("build/reports/p7-settings/$name-${category.name.lowercase()}.png")
                requireNotNull(output.parentFile).mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                if (category == SettingsCategory.DIAGNOSTICS) {
                    val direction = descendants(root).filterIsInstance<TextView>().first {
                        it.text.toString() == activity.getString(R.string.settings_direction_title)
                    }
                    val card = direction.parent as ViewGroup
                    val detail = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                    card.draw(Canvas(detail))
                    File("build/reports/p7-settings/$name-direction.png").outputStream().use {
                        detail.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    detail.recycle()
                }
            }
        } finally {
            controller.pause().stop().destroy()
            AppAppearanceRuntime.resetForTest()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
