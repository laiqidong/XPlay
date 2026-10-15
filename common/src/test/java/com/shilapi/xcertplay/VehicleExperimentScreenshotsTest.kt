package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleExperimentScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h800dp-xhdpi")
    fun compact() = render("compact", 320)

    @Test @Config(qualifiers = "zh-rCN-w600dp-h800dp-xhdpi")
    fun full() = render("full", 600)

    @Test @Config(qualifiers = "ar-ldrtl-w600dp-h800dp-xhdpi")
    fun arabic() = render("arabic", 600)

    private fun render(name: String, widthDp: Int) {
        ShadowBuild.setManufacturer("XiaoPeng")
        ShadowBuild.setBrand("XPENG")
        ShadowBuild.setModel("E28")
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = configuration.layoutDirection
        }
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.EXPERIMENTS)
        ReflectionHelpers.setField(activity, "vehicleProbeReport",
            VehicleInformationProbe.format(activity, listOf("4", "0.0", "0", "0", "49")))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshAppearance")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "settingsCategoryContent",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, controls))
        val width = (widthDp * activity.resources.displayMetrics.density).toInt()
        controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        controls.layout(0, 0, width, controls.measuredHeight)
        assertTrue(controls.height > 0)
        val bitmap = Bitmap.createBitmap(width, controls.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(28, 32, 42))
        controls.draw(canvas)
        val output = File("build/vehicle-experiment-screenshots/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
