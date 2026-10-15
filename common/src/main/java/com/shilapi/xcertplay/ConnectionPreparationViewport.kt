package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.roundToInt

/** Centers short connection cards and scrolls tall ones without shrinking text or touch targets. */
internal class ConnectionPreparationViewport(context: Context, private val card: View) : ScrollView(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        clipToPadding = false
        addView(FrameLayout(context).apply {
            setPadding(0, dp(CARD_MARGIN_DP), 0, dp(CARD_MARGIN_DP))
            addView(card, FrameLayout.LayoutParams(dp(CARD_MAX_WIDTH_DP), -2, Gravity.CENTER))
        }, LayoutParams(-1, -2))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - dp(CARD_MARGIN_DP * 2)).coerceAtLeast(1)
        card.layoutParams.width = minOf(dp(CARD_MAX_WIDTH_DP), available)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        const val CARD_MAX_WIDTH_DP = 560
        const val CARD_MARGIN_DP = 24
    }
}
