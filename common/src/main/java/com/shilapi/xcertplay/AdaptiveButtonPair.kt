package com.shilapi.xcertplay

import android.content.Context
import android.text.Layout
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.ceil
import kotlin.math.max

/** Equal-width actions when both labels fit; stacked actions on narrow or enlarged-text windows. */
internal class AdaptiveButtonPair(context: Context, private val buttons: List<Button>) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private val gap get() = dp(ACTION_GAP_DP)
    private val separator = View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    init {
        require(buttons.size == 2)
        buttons.forEachIndexed { index, button ->
            if (index > 0) addView(separator, LayoutParams(0, 0))
            button.minimumHeight = max(button.minimumHeight, button.layoutParams.height.coerceAtLeast(0))
            addView(button, LayoutParams(-1, -2))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val required = buttons.maxOf { button ->
            ceil(Layout.getDesiredWidth(button.text, button.paint).toDouble()).toInt() +
                button.compoundPaddingLeft + button.compoundPaddingRight
        }.coerceAtLeast(dp(MIN_ACTION_WIDTH_DP))
        val horizontal = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            available >= 2 * required + gap
        orientation = if (horizontal) HORIZONTAL else VERTICAL
        (separator.layoutParams as LayoutParams).apply {
            width = if (horizontal) gap else 0
            height = if (horizontal) 0 else gap
        }
        buttons.forEach { button ->
            (button.layoutParams as LayoutParams).apply {
                width = if (horizontal) 0 else -1
                height = -2
                weight = if (horizontal) 1f else 0f
                setMargins(0, 0, 0, 0)
                marginStart = 0
                marginEnd = 0
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (horizontal) {
            val commonHeight = buttons.maxOf { it.measuredHeight }
            buttons.forEach { (it.layoutParams as LayoutParams).height = commonHeight }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private companion object {
        const val ACTION_GAP_DP = 12
        const val MIN_ACTION_WIDTH_DP = 144
    }
}
