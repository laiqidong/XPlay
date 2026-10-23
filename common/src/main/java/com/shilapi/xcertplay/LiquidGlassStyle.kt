package com.shilapi.xcertplay

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils

/** Shared glass material; semantic tints retain primary/danger action recognition. */
internal object LiquidGlassStyle {
    const val CONTROL_RADIUS_RATIO = .5f
    fun button(view: View, night: Boolean, tint: Int?, ripple: Int): RippleDrawable {
        val material = ButtonGlassDrawable(view, night, tint)
        val mask = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            override fun draw(canvas: android.graphics.Canvas) {
                val rect = android.graphics.RectF(bounds)
                val radius = rect.height() * CONTROL_RADIUS_RATIO
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
            @Deprecated("Deprecated in Android")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        return RippleDrawable(ColorStateList.valueOf(ripple), material, mask)
    }

    fun dialog(dialog: Dialog, night: Boolean, owner: android.content.Context = dialog.context) {
        DialogBackdrop.attach(dialog, owner)
        val window = dialog.window ?: return
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val panel = content.getChildAt(0) ?: return
        // Forms may provide their own opaque wrapper; keep previews and input controls intact.
        fun clearWrappers(view: View) {
            if (view is LinearLayout || view is FrameLayout || view is ScrollView ||
                view is androidx.core.widget.NestedScrollView) {
                if (view.background is ColorDrawable || view.background is GradientDrawable) view.background = null
                if (view is ViewGroup) for (i in 0 until view.childCount) clearWrappers(view.getChildAt(i))
            }
        }
        clearWrappers(panel)
        for (name in listOf("parentPanel", "topPanel", "contentPanel", "customPanel", "buttonPanel", "custom")) {
            val id = panel.resources.getIdentifier(name, "id", "android")
            if (id != 0) panel.findViewById<View>(id)?.background = null
        }
        fun clearLists(view: View) {
            if (view is android.widget.ListView) {
                view.background = null
                view.cacheColorHint = Color.TRANSPARENT
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) clearLists(view.getChildAt(i))
        }
        clearLists(panel)
        panel.background = SettingsBackdrop.surface(panel, night)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}
