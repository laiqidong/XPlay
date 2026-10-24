package com.shilapi.xcertplay

import android.graphics.Color
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.ComposeShader
import android.graphics.PorterDuff

/** One lighting direction for glass cards, controls and floating surfaces. */
internal object GlassRim {
    const val WIDTH_DP = .8f

    fun shader(bounds: RectF, light: Boolean = false, emphasized: Boolean = false, raised: Boolean = false): Shader {
        val side = Color.argb(if (emphasized) 36 else if (raised) 80 else if (light) 44 else 32, 0, 0, 0)
        val highlight = Color.argb(if (emphasized) 192 else if (raised) 176 else if (light) 168 else 136, 255, 255, 255)
        // Axis-aligned bands cover the whole straight edge, independent of aspect ratio.
        // Their overlap blends the light and shade around each rounded corner.
        val transition = minOf(bounds.width(), bounds.height()) * CORNER_TRANSITION_RATIO
        val verticalStop = (transition / bounds.height().coerceAtLeast(1f)).coerceIn(.001f, .49f)
        val horizontalStop = (transition / bounds.width().coerceAtLeast(1f)).coerceIn(.001f, .49f)
        val vertical = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
            intArrayOf(highlight, Color.TRANSPARENT, Color.TRANSPARENT, highlight),
            floatArrayOf(0f, verticalStop, 1f - verticalStop, 1f), Shader.TileMode.CLAMP)
        val horizontal = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.top,
            intArrayOf(side, Color.TRANSPARENT, Color.TRANSPARENT, side),
            floatArrayOf(0f, horizontalStop, 1f - horizontalStop, 1f), Shader.TileMode.CLAMP)
        return ComposeShader(vertical, horizontal, PorterDuff.Mode.SRC_OVER)
    }

    private const val CORNER_TRANSITION_RATIO = .35f
}
