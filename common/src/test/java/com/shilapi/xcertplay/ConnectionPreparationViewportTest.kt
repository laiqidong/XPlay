package com.shilapi.xcertplay

import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ConnectionPreparationViewportTest {
    @Test fun shortCarWindowScrollsContentWithoutShrinkingItsText() {
        val context = RuntimeEnvironment.getApplication()
        val density = context.resources.displayMetrics.density
        val card = TextView(context).apply {
            text = "CarPlay"
            textSize = 22f
            minimumHeight = (600 * density).toInt()
        }
        val originalTextSize = card.textSize
        val viewport = ConnectionPreparationViewport(context, card)
        val width = (1000 * density).toInt()
        val height = (240 * density).toInt()
        viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        viewport.layout(0, 0, width, height)
        assertEquals(1f, card.scaleX, 0f)
        assertEquals(1f, card.scaleY, 0f)
        assertEquals(originalTextSize, card.textSize, 0f)
        assertTrue(viewport.getChildAt(0).height > height)
        viewport.scrollTo(0, 100)
        assertTrue(viewport.scrollY > 0)
    }

    @Test fun cardWidthStaysInsideANarrowWindow() {
        val context = RuntimeEnvironment.getApplication()
        val density = context.resources.displayMetrics.density
        val card = TextView(context).apply { text = "CarPlay" }
        val viewport = ConnectionPreparationViewport(context, card)
        val width = (280 * density).toInt()
        val height = (400 * density).toInt()
        viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        viewport.layout(0, 0, width, height)
        assertTrue(card.width < width)
        assertTrue(card.left >= 0)
    }
}
