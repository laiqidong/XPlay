package com.shilapi.xcertplay

import android.content.Context
import kotlin.math.roundToInt

/** One visual scale for the entry and the controls, based on the CarPlay shortcut circles. */
internal object P7VehicleControlStyle {
    private const val BUTTON_HEIGHT_FRACTION = .075f
    private const val MIN_BUTTON_DP = 80f
    private const val MAX_BUTTON_DP = 104f
    const val ICON_FRACTION = .5f
    fun buttonSize(context: Context, height: Float): Int {
        val density = context.resources.displayMetrics.density
        return (height * BUTTON_HEIGHT_FRACTION).roundToInt()
            .coerceIn((MIN_BUTTON_DP * density).roundToInt(), (MAX_BUTTON_DP * density).roundToInt())
    }
}
