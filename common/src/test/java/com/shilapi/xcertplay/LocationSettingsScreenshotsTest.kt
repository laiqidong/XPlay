package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationSettingsScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h800dp-xhdpi")
    fun compactLargeFont() = render("compact-zh", 320)

    @Test @Config(qualifiers = "zh-rCN-w900dp-h800dp-xhdpi")
    fun fullLargeFont() = render("full-zh", 900)

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h800dp-xhdpi")
    fun arabicCompactLargeFont() = render("compact-ar", 320)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h800dp-xhdpi")
    fun p7DataCompactLargeFont() = render("p7-compact-zh", 320, true)

    @Test @Config(qualifiers = "zh-rCN-w900dp-h800dp-xhdpi")
    fun p7DataFullLargeFont() = render("p7-full-zh", 900, true)

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h800dp-xhdpi")
    fun p7DataArabicLargeFont() = render("p7-compact-ar", 320, true)

    private fun render(name: String, widthDp: Int, p7: Boolean = false) {
        if (p7) {
            ShadowBuild.setManufacturer("XiaoPeng")
            ShadowBuild.setBrand("XPENG")
            ShadowBuild.setModel("E28")
        }
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(
            if (p7) SettingsSection.P7_CARPLAY_DATA else SettingsSection.LOCATION))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = config.layoutDirection
        }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        val width = (widthDp * activity.resources.displayMetrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, width, content.measuredHeight)
        val views = descendants(content).toList()
        val toggle = views.filterIsInstance<Switch>().single {
            it.settingsControlTitle == activity.getString(if (p7) R.string.settings_p7_battery else R.string.settings_location_low_latency)
        }
        assertTrue(toggle.width > 0 && toggle.left >= 0 && toggle.right <= (toggle.parent as View).width)
        for (text in views.filterIsInstance<TextView>()) {
            if (text is Switch || text.text.isEmpty()) continue
            val layout = requireNotNull(text.layout)
            for (line in 0 until layout.lineCount) assertEquals("Clipped: ${text.text}", 0, layout.getEllipsisCount(line))
            assertTrue("Vertical clipping: ${text.text} layout=${layout.height} height=${text.height} padding=${text.compoundPaddingTop + text.compoundPaddingBottom}",
                layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        }
        val bitmap = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(12, 14, 20))
        content.draw(canvas)
        val output = File("build/reports/location-settings/$name.png").apply { parentFile.mkdirs() }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
