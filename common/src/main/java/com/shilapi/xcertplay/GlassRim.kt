package com.shilapi.xcertplay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.PorterDuff
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/** Shared bevel: a directional outer reflection and a separated, softer inner edge. */
internal object GlassRim {
    private const val OUTER_WIDTH_DP = .85f
    private const val INNER_WIDTH_DP = .75f
    private const val INNER_INSET_DP = 1.15f
    private const val BEVEL_WIDTH_DP = 1.6f

    fun draw(canvas: Canvas, bounds: RectF, radius: Float, density: Float,
        paint: Paint, opacity: Int = 255, light: Boolean = false, emphasized: Boolean = false,
        raised: Boolean = false, contour: ((Path, RectF, Float) -> Unit)? = null) {
        if (bounds.isEmpty) return
        val rect = RectF()
        val path = Path()
        val savedColor = paint.color
        val savedAlpha = paint.alpha
        val savedStyle = paint.style
        val savedWidth = paint.strokeWidth
        val savedShader = paint.shader
        fun shape(inset: Float) {
            rect.set(bounds); rect.inset(inset, inset)
            path.reset()
            val r = (radius - inset).coerceIn(0f, minOf(rect.width(), rect.height()).coerceAtLeast(0f) / 2f)
            if (contour != null) contour(path, rect, r)
            else path.addRoundRect(rect, r, r, Path.Direction.CW)
        }
        // Broad, translucent highlight fading across the face; no opaque white tile.
        shape(0f)
        paint.style = Paint.Style.FILL; paint.alpha = opacity
        paint.shader = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
            if (light) intArrayOf(0x06ffffff, 0x02ffffff, 0x00000000, 0x08000000)
            else intArrayOf(Color.argb(if (emphasized) 8 else if (raised) 20 else 14, 230, 235, 242),
                0x04ffffff, 0x00000000, 0x14000000),
            floatArrayOf(0f, .35f, .62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        // The wider bevel connects the two lines around all four sides and corners.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = BEVEL_WIDTH_DP * density
        shape(paint.strokeWidth / 2f)
        paint.shader = mutedSides(bounds,
            edgeReflection(bounds, if (light) 0x08ffffff else 0x0ce6ebf2,
                if (light) 0x08ffffff else 0x0ae6ebf2, radius), 56, light, radius)
        canvas.drawPath(path, paint)
        paint.strokeWidth = (if (light) .65f else OUTER_WIDTH_DP) * density
        shape(paint.strokeWidth / 2f)
        paint.shader = shader(bounds, light, emphasized, raised, radius)
        canvas.drawPath(path, paint)
        paint.strokeWidth = (if (light) .55f else INNER_WIDTH_DP) * density
        val innerInset = minOf(INNER_INSET_DP * density, minOf(bounds.width(), bounds.height()) / 4f)
        shape(innerInset)
        paint.shader = mutedSides(bounds,
            edgeReflection(bounds, if (light) 0x20ffffff else 0x34e6ebf2,
                if (light) 0x24ffffff else 0x38e6ebf2, radius), 76, light, radius)
        canvas.drawPath(path, paint)
        paint.shader = savedShader; paint.style = savedStyle; paint.strokeWidth = savedWidth
        paint.color = savedColor; paint.alpha = savedAlpha
    }

    /** Settings use the same bevel as the HVAC surfaces, with their existing geometry. */
    fun drawSettings(canvas: Canvas, bounds: RectF, radius: Float, density: Float,
        paint: Paint, opacity: Int = 255, light: Boolean = false, emphasized: Boolean = false,
        raised: Boolean = false) {
        draw(canvas, bounds, radius, density, paint, opacity, light, emphasized, raised)
    }

    private fun shader(bounds: RectF, light: Boolean, emphasized: Boolean, raised: Boolean, radius: Float): Shader {
        val highlight = Color.argb(if (light) 12 else if (emphasized) 14 else if (raised) 20 else 16,
            154, 162, 174)
        return mutedSides(bounds, edgeReflection(bounds, highlight, highlight, radius), 80, light, radius)
    }

    /** Feather the reflection into the side shade rather than joining two hard bands. */
    private fun edgeReflection(bounds: RectF, top: Int, bottom: Int, radius: Float): Shader {
        val cornerEnd = (radius / bounds.height().coerceAtLeast(1f)).coerceIn(.001f, .49f)
        val transition = (cornerEnd + .06f).coerceAtMost(.499f)
        fun softened(color: Int) = Color.argb((Color.alpha(color) * .35f).toInt(),
            Color.red(color), Color.green(color), Color.blue(color))
        val reflection = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
            intArrayOf(top, softened(top), Color.TRANSPARENT, Color.TRANSPARENT, softened(bottom), bottom),
            floatArrayOf(0f, cornerEnd, transition, 1f - transition, 1f - cornerEnd, 1f),
            Shader.TileMode.CLAMP)
        val cornerWidth = (radius / bounds.width().coerceAtLeast(1f)).coerceIn(.001f, .499f)
        val cornerAttenuation = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.top,
            intArrayOf(0x30ffffff, 0x44ffffff, Color.WHITE, Color.WHITE, 0x44ffffff, 0x30ffffff),
            floatArrayOf(0f, cornerWidth * .7f, cornerWidth,
                1f - cornerWidth, 1f - cornerWidth * .7f, 1f), Shader.TileMode.CLAMP)
        return ComposeShader(reflection, cornerAttenuation, PorterDuff.Mode.DST_IN)
    }

    private fun mutedSides(bounds: RectF, vertical: Shader, alpha: Int, light: Boolean, radius: Float): Shader {
        val transition = (minOf(bounds.width(), bounds.height()) * .35f / bounds.width().coerceAtLeast(1f))
            .coerceIn(.001f, .49f)
        val shade = if (light) Color.argb((alpha * .12f).toInt(), 56, 61, 68)
            else Color.argb(alpha, 32, 36, 42)
        val feather = Color.argb((Color.alpha(shade) * .35f).toInt(),
            Color.red(shade), Color.green(shade), Color.blue(shade))
        val horizontal = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.top,
            intArrayOf(shade, feather, Color.TRANSPARENT, Color.TRANSPARENT, feather, shade),
            floatArrayOf(0f, transition * .45f, transition, 1f - transition, 1f - transition * .45f, 1f), Shader.TileMode.CLAMP)
        // Side shade starts after the curved corner. A circle has no straight sides.
        if (radius * 2f >= bounds.height() - 1f) return vertical
        val cornerEnd = (radius / bounds.height().coerceAtLeast(1f)).coerceIn(.001f, .49f)
        val fadeEnd = (cornerEnd + .06f).coerceAtMost(.499f)
        val sideMask = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE, Color.WHITE,
                Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0f, cornerEnd, fadeEnd, 1f - fadeEnd, 1f - cornerEnd, 1f), Shader.TileMode.CLAMP)
        val straightSides = ComposeShader(horizontal, sideMask, PorterDuff.Mode.DST_IN)
        return ComposeShader(vertical, straightSides, PorterDuff.Mode.SRC_OVER)
    }
}
