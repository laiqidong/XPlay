package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import androidx.core.graphics.ColorUtils

internal enum class DiPlayButtonKind { PRIMARY, SECONDARY, DANGER, TONAL_DANGER }

/**
 * Button for views built in code outside `DiPlayActivity`, whose own `button()` stays private.
 * The corner radius follows the control height, as in `DiPlayActivity.buttonShape`.
 */
internal class DiPlayButton(context: Context) : Button(context) {
    var glassBackground: android.graphics.drawable.Drawable? = null
        set(value) { field = value; repaint() }

    private var kind = DiPlayButtonKind.SECONDARY
    private var palette: DiPlayPalette? = null
    private var overlay = false
    private var compactCorners = false

    init {
        isAllCaps = false
        stateListAnimator = null
        typeface = AppFonts.medium(context)
    }

    /** [overlay] picks the in-car menu colours instead of the app chrome colours. */
    fun style(kind: DiPlayButtonKind, palette: DiPlayPalette, overlay: Boolean, compactCorners: Boolean = false) {
        this.kind = kind
        this.palette = palette
        this.overlay = overlay
        this.compactCorners = compactCorners || !overlay
        repaint()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) repaint()
    }

    private fun repaint() {
        val palette = palette ?: return
        val density = resources.displayMetrics.density
        val fill: Int
        val text: Int
        when (kind) {
            DiPlayButtonKind.PRIMARY -> {
                fill = if (overlay) palette.overlayAccent else palette.accent
                text = if (overlay) palette.overlayOnAccent else palette.onAccent
            }
            DiPlayButtonKind.SECONDARY -> {
                fill = if (overlay) palette.overlayTrackOff else palette.button
                text = if (overlay) palette.overlayPrimaryText else palette.primaryText
            }
            DiPlayButtonKind.DANGER -> {
                fill = palette.overlayDanger
                text = Color.WHITE
            }
            DiPlayButtonKind.TONAL_DANGER -> {
                val surface = if (overlay) palette.overlayBackground else palette.surface
                fill = ColorUtils.blendARGB(surface, palette.overlayDanger, 0.16f)
                text = if (ColorUtils.calculateLuminance(surface) < 0.5)
                    ColorUtils.blendARGB(palette.overlayDanger, Color.WHITE, 0.40f)
                else palette.overlayDanger
            }
        }
        val controlHeight = if (height > 0) height else minHeight
        val radius = controlHeight * LiquidGlassStyle.CONTROL_RADIUS_RATIO
        setTextColor(text)
        val night = ColorUtils.calculateLuminance(palette.background) < .5
        val tint = when (kind) {
            DiPlayButtonKind.PRIMARY, DiPlayButtonKind.DANGER, DiPlayButtonKind.TONAL_DANGER -> fill
            DiPlayButtonKind.SECONDARY -> null
        }
        background = if (glassBackground != null) RippleDrawable(
            ColorStateList.valueOf(palette.ripple), glassBackground,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius },
        ) else LiquidGlassStyle.button(this, night, tint, palette.ripple)
        // Remote and D-pad users need to see where they are; touch mode never shows it.
        foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = radius
                setStroke((3 * density).toInt(), palette.focusRing)
            })
        }
    }
}
