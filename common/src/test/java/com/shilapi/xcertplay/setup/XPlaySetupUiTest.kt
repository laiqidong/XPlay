package com.shilapi.xcertplay.setup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayActivity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class XPlaySetupUiTest {
    @Test @Config(qualifiers = "en-w360dp-h800dp-xhdpi")
    fun compactGuide() = render("compact", 360)

    @Test @Config(qualifiers = "en-w960dp-h800dp-xhdpi")
    fun fullGuide() = render("full", 960)

    @Test @Config(qualifiers = "ar-ldrtl-w360dp-h800dp-xhdpi")
    fun arabicGuide() = render("arabic", 360)

    private fun render(name: String, widthDp: Int) {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        assertEquals("XPlay", activity.getString(R.string.app_name))
        for (step in listOf(SetupGuide.STEP_CONNECTION, SetupGuide.STEP_FEATURES)) {
            ReflectionHelpers.setField(activity, "setupStep", step)
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = configuration.layoutDirection
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "setupGuide",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
            val text = texts(content)
            assertFalse(text.any { it.contains("DiLink") || it.contains("BYD") })
            assertTrue(text.contains(activity.getString(if (step == 0) R.string.setup_connection_title else R.string.setup_features_title)))
            val width = (widthDp * activity.resources.displayMetrics.density).toInt()
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            content.layout(0, 0, width, content.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(28, 32, 42))
            content.draw(canvas)
            val directory = File("build/reports/xplay-setup").apply { mkdirs() }
            File(directory, "$name-$step.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        controller.destroy()
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
