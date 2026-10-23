package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.HapticFeedbackConstants

/** In-window frosted lyrics; ordinary gestures pass through until a long press takes ownership. */
internal class FloatingLyricsView(context: Context) : View(context) {
    private data class TextFrame(val current: StaticLayout, val next: StaticLayout?, val gap: Float,
        val width: Float, val height: Float)
    private data class LayoutKey(val current: String, val next: String?, val status: Boolean,
        val font: Float, val width: Int, val height: Int, val density: Float)
    private var layoutKey: LayoutKey? = null
    private var cachedTextFrame: TextFrame? = null
    private var layoutSong = ""
    private var marqueeStartedAt = android.os.SystemClock.uptimeMillis()
    private var lastTextFrame: TextFrame? = null
    private var pendingFrom: TextFrame? = null
    private var animationFrom: TextFrame? = null
    private var animation: ValueAnimator? = null
    private var animationProgress = 1f
    private var snapshot = InstrumentLyrics.FloatingSnapshot()
    private var removeObserver: (() -> Unit)? = null
    private val box = RectF()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val currentTypeface = AppFonts.medium(context)
    private val nextTypeface = AppFonts.regular(context)
    private val backgroundPath = Path()
    private val backdrop = FloatingLyricsBackdrop(this) { invalidate() }
    var videoSource: (() -> View?)?
        get() = backdrop.source
        set(value) { backdrop.source = value }
    private var lightMaterial: Boolean? = null
    private var pendingLight: Boolean? = null
    private var pendingLightAt = 0L
    private var dragging = false
    private var dragX: Int? = null
    private var dragY: Int? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var pendingDrag = false
    var cancelCarPlayTouch: (() -> Unit)? = null
    private val beginLongDrag = Runnable {
        if (pendingDrag && snapshot.visible) {
            pendingDrag = false; dragging = true
            stopTransition()
            cancelCarPlayTouch?.invoke()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
    private var metadataTransition = false
    private fun cancelPendingDrag() { pendingDrag = false; removeCallbacks(beginLongDrag) }

    init { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeObserver = InstrumentLyrics.observeFloating {
            val previous = snapshot
            val advances = it != previous && previous.visible && it.visible && !previous.status && !it.status &&
                it.songKey.isNotBlank() && it.songKey == previous.songKey && it.motionEpoch == previous.motionEpoch &&
                it.lineIndex > previous.lineIndex && previous.next == it.current &&
                InstrumentLyricsSettings.floatingAnimation(context) && !dragging && animation?.isRunning != true
            if (it != previous) {
                if (it.current != previous.current || it.next != previous.next || it.songKey != previous.songKey)
                    marqueeStartedAt = android.os.SystemClock.uptimeMillis()
                stopTransition()
                metadataTransition = it.songKey == previous.songKey && it.motionEpoch == previous.motionEpoch &&
                    it.visible && previous.visible && it.status != previous.status &&
                    InstrumentLyricsSettings.floatingAnimation(context) && !dragging
                pendingFrom = lastTextFrame.takeIf { advances || metadataTransition }
            } else { stopTransition(); lastTextFrame = null }
            snapshot = it
            backdrop.setActive(it.visible)
            visibility = if (it.visible) VISIBLE else GONE
            if (!it.visible) { cancelPendingDrag(); dragging = false; box.setEmpty(); lightMaterial = null; pendingLight = null }
            invalidate()
        }
    }
    override fun onDetachedFromWindow() {
        cancelPendingDrag()
        stopTransition(); lastTextFrame = null; cachedTextFrame = null; layoutKey = null
        backdrop.setActive(false)
        removeObserver?.invoke(); removeObserver = null
        snapshot = InstrumentLyrics.FloatingSnapshot(); box.setEmpty(); dragging = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!snapshot.visible || width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val margin = EDGE_DP * density
        val padding = PADDING_DP * density
        val verticalPadding = VERTICAL_PADDING_DP * density
        val maximum = (minOf(width - 2 * margin, width * MAX_WIDTH_FRACTION) - 2 * padding).coerceAtLeast(1f)
        val font = InstrumentLyricsSettings.floatingValue(context, "font").coerceIn(80, 140) / 100f
        val nextText = snapshot.next.takeIf { (snapshot.status || InstrumentLyricsSettings.floatingNext(context)) && it.isNotBlank() }
        val key = LayoutKey(snapshot.current, nextText, snapshot.status, font, width, height, density)
        val target = cachedTextFrame.takeIf { layoutKey == key } ?: run {
            val currentSize = if (snapshot.status) STATUS_TITLE_DP else FONT_DP
            val nextSize = if (snapshot.status) STATUS_DETAIL_DP else NEXT_FONT_DP
            val measure = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = currentTypeface; textSize = currentSize * density * font }
            val currentWidth = measure.measureText(snapshot.current)
            measure.typeface = nextTypeface; measure.textSize = nextSize * density * font
            val nextWidth = nextText?.let(measure::measureText) ?: 0f
            val desired = maxOf(currentWidth, nextWidth)
            val minimum = minOf(MIN_CONTENT_DP * density, maximum)
            val quantum = WIDTH_QUANTUM_DP * density
            var contentWidth = (kotlin.math.ceil(desired / quantum) * quantum).coerceIn(minimum, maximum)
            val previous = cachedTextFrame
            if (layoutSong == snapshot.songKey && layoutKey?.status == snapshot.status &&
                layoutKey?.width == width && layoutKey?.height == height && previous != null &&
                kotlin.math.abs(contentWidth + 2 * padding - previous.width) < WIDTH_HOLD_DP * density) {
                contentWidth = (previous.width - 2 * padding).coerceIn(minimum, maximum)
            }
            val available = kotlin.math.ceil(contentWidth).toInt()
            fun layout(text: String, size: Float, typeface: Typeface): StaticLayout {
                val singleLine = text.replace(Regex("[\\r\\n]+"), " ")
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size * density * font; this.typeface = typeface
                }
                // Lay out the complete line; the card clips and scrolls overflow without resizing type.
                val textWidth = kotlin.math.ceil(maxOf(available.toFloat(), paint.measureText(singleLine))).toInt() + 1
                return StaticLayout.Builder.obtain(singleLine, 0, singleLine.length, paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(1)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
            }
            val current = layout(snapshot.current, currentSize, currentTypeface)
            val next = nextText?.let { layout(it, nextSize, nextTypeface) }
            val gap = if (next != null) LINE_GAP_DP * density else 0f
            TextFrame(current, next, gap, (available + 2 * padding).coerceAtMost(width.toFloat()),
                (current.height + (next?.height ?: 0) + gap + 2 * verticalPadding).coerceAtMost(height.toFloat())).also {
                    cachedTextFrame = it; layoutKey = key; layoutSong = snapshot.songKey
                }
        }
        val current = target.current; val next = target.next; val gap = target.gap
        pendingFrom?.let { source ->
            pendingFrom = null; animationFrom = source; animationProgress = 0f
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TRANSITION_MILLIS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animationProgress = it.animatedValue as Float
                    if (animationProgress >= 1f) { animationFrom = null; animation = null }
                    postInvalidateOnAnimation()
                }
                start()
            }
        }
        val from = animationFrom
        val progress = animationProgress
        fun interpolate(start: Float, end: Float) = start + (end - start) * progress
        val boxWidth = from?.let { interpolate(it.width, target.width) } ?: target.width
        val boxHeight = from?.let { interpolate(it.height, target.height) } ?: target.height
        lastTextFrame = target
        val roomX = (width - boxWidth - 2 * margin).coerceAtLeast(0f)
        val topMargin = EDGE_DP * density
        val roomY = (height - boxHeight - topMargin - margin).coerceAtLeast(0f)
        val x = margin.coerceAtMost((width - boxWidth).coerceAtLeast(0f)) + roomX *
            (dragX ?: InstrumentLyricsSettings.floatingValue(context, "x")).coerceIn(0, 100) / 100f
        val y = topMargin.coerceAtMost((height - boxHeight).coerceAtLeast(0f)) + roomY *
            (dragY ?: InstrumentLyricsSettings.floatingValue(context, "y")).coerceIn(0, 100) / 100f
        box.set(x, y, x + boxWidth, y + boxHeight)
        backdrop.region = RectF(box)
        val light = materialIsLight()
        current.paint.color = if (light) Color.rgb(25, 29, 35) else Color.WHITE
        next?.paint?.color = if (light) Color.rgb(85, 94, 107) else Color.rgb(190, 198, 210)
        background.color = if (light) Color.WHITE else Color.BLACK
        val opacity = InstrumentLyricsSettings.floatingValue(context, "opacity").coerceIn(0, 100) / 100f
        if (opacity > 0f) {
            val hasBackdrop = backdrop.frame?.bitmap?.isRecycled == false
            // Keep the sampled colours; a very light neutral correction protects the text.
            background.alpha = ((if (!hasBackdrop) 205 else if (light) 14 else 36) * opacity).toInt()
            val radius = minOf(CORNER_DP * density, box.height() / 2f, box.width() / 2f)
            for (step in SHADOW_STEPS downTo 1) {
                val spread = step * density * .5f
                val shadow = RectF(box).apply { inset(-spread, -spread); offset(0f, 2f * density) }
                shadowPaint.color = Color.BLACK
                shadowPaint.alpha = (SHADOW_ALPHA * opacity).toInt()
                canvas.drawRoundRect(shadow, radius + spread, radius + spread, shadowPaint)
            }
            // Backdrop blur replaces the sharp pixels. Fading this whole layer would reveal
            // the original video again; only the neutral material tint uses opacity.
            val layer = canvas.saveLayer(box, null)
            backgroundPath.reset()
            backgroundPath.addRoundRect(box, radius, radius, Path.Direction.CW)
            canvas.clipPath(backgroundPath)
            backdrop.frame?.let { frame ->
                if (!frame.bitmap.isRecycled) {
                    imagePaint.alpha = 255
                    canvas.drawBitmap(frame.bitmap, null, if (frame.bounds.contains(box)) frame.bounds else box, imagePaint)
                }
            }
            canvas.drawRect(box, background)
            canvas.restoreToCount(layer)
            // A narrow highlight and darker lower edge give the surface a little thickness.
            val stroke = EDGE_STROKE_DP * density
            val edge = RectF(box).apply { inset(stroke / 2, stroke / 2) }
            edgePaint.strokeWidth = stroke
            edgePaint.alpha = (255 * opacity).toInt()
            edgePaint.shader = LinearGradient(box.left, box.top, box.left, box.bottom,
                intArrayOf(Color.argb(if (light) 150 else 95, 255, 255, 255),
                    Color.argb(24, 255, 255, 255), Color.argb(48, 0, 0, 0)),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(edge, (radius - stroke / 2).coerceAtLeast(0f),
                (radius - stroke / 2).coerceAtLeast(0f), edgePaint)
        }
        val textSave = canvas.save()
        backgroundPath.reset()
        backgroundPath.addRoundRect(box, minOf(CORNER_DP * density, box.height() / 2f, box.width() / 2f),
            minOf(CORNER_DP * density, box.height() / 2f, box.width() / 2f), Path.Direction.CW)
        canvas.clipPath(backgroundPath)
        canvas.clipRect(box.left + padding, box.top + verticalPadding, box.right - padding, box.bottom - verticalPadding)
        val primaryColor = current.paint.color
        val secondaryColor = next?.paint?.color ?: if (light) Color.rgb(85, 94, 107) else Color.rgb(190, 198, 210)
        fun drawLine(layout: StaticLayout, top: Float, scale: Float, alpha: Float, color: Int) {
            if (alpha <= 0f) return
            val saved = canvas.save()
            val viewport = (box.width() - 2 * padding).coerceAtLeast(1f)
            val overflow = (layout.width * scale - viewport).coerceAtLeast(0f)
            var left = box.centerX() - layout.width * scale / 2f
            if (overflow > 1f) {
                val travelMillis = overflow / (MARQUEE_SPEED_DP * density) * 1000f
                val cycleMillis = 2 * MARQUEE_HOLD_MILLIS + 2 * travelMillis
                val elapsed = ((android.os.SystemClock.uptimeMillis() - marqueeStartedAt).toFloat() % cycleMillis)
                val offset = when {
                    elapsed < MARQUEE_HOLD_MILLIS -> 0f
                    elapsed < MARQUEE_HOLD_MILLIS + travelMillis ->
                        overflow * (elapsed - MARQUEE_HOLD_MILLIS) / travelMillis
                    elapsed < 2 * MARQUEE_HOLD_MILLIS + travelMillis -> overflow
                    else -> overflow * (1f - (elapsed - 2 * MARQUEE_HOLD_MILLIS - travelMillis) / travelMillis)
                }
                left = box.left + padding - offset
                if (!dragging) postInvalidateOnAnimation()
            }
            canvas.translate(left, box.top + verticalPadding + top)
            canvas.scale(scale, scale)
            val oldColor = layout.paint.color
            val oldAlpha = layout.paint.alpha
            layout.paint.color = color
            layout.paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
            layout.draw(canvas)
            layout.paint.color = oldColor; layout.paint.alpha = oldAlpha
            canvas.restoreToCount(saved)
        }
        if (from != null && metadataTransition) {
            drawLine(from.current, 0f, 1f, 1f - progress, primaryColor)
            from.next?.let { drawLine(it, from.current.height + from.gap, 1f, 1f - progress, secondaryColor) }
            drawLine(current, 0f, 1f, progress, primaryColor)
            next?.let { drawLine(it, current.height + gap, 1f, progress, secondaryColor) }
        } else if (from != null) {
            val oldNextTop = from.current.height + from.gap
            val promotedTop = interpolate(oldNextTop, 0f)
            drawLine(from.current, -progress * (from.current.height + from.gap), 1f, 1f - progress, primaryColor)
            from.next?.let {
                drawLine(it, promotedTop, interpolate(1f, FONT_DP / NEXT_FONT_DP), 1f - progress, secondaryColor)
            }
            drawLine(current, promotedTop, interpolate(NEXT_FONT_DP / FONT_DP, 1f), progress, primaryColor)
            next?.let {
                drawLine(it, current.height + gap + (1f - progress) * it.height, 1f, progress, secondaryColor)
            }
        } else {
            drawLine(current, 0f, 1f, 1f, primaryColor)
            next?.let { drawLine(it, current.height + gap, 1f, 1f, secondaryColor) }
        }
        canvas.restoreToCount(textSave)
    }

    private fun stopTransition() {
        animation?.removeAllUpdateListeners(); animation?.cancel(); animation = null
        animationFrom = null; pendingFrom = null; animationProgress = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stopTransition(); lastTextFrame = null; cachedTextFrame = null; layoutKey = null
    }

    private fun materialIsLight(): Boolean {
        val brightness = backdrop.frame?.let { it.brightness(if (it.bounds.contains(box)) box else it.bounds) }
        val previous = lightMaterial
        val target = when {
            brightness == null -> !snapshot.night
            previous == null -> brightness >= 140f
            previous -> brightness >= 125f
            else -> brightness >= 155f
        }
        if (previous == null) { lightMaterial = target; return target }
        if (target == previous) { pendingLight = null; return previous }
        val now = android.os.SystemClock.elapsedRealtime()
        if (pendingLight != target) { pendingLight = target; pendingLightAt = now }
        else if (now - pendingLightAt >= MATERIAL_HOLD_MILLIS) {
            lightMaterial = target; pendingLight = null
        }
        return lightMaterial ?: target
    }

    // The gesture layer observes a down before forwarding it to CarPlay. Ordinary taps/moves
    // remain untouched; a confirmed long press cancels the iPhone touch before taking over.
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    fun handleHostTouch(event: MotionEvent): Boolean {
        if (!snapshot.visible) return false
        if (event.pointerCount > 1) {
            cancelPendingDrag()
            if (!dragging) return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPendingDrag()
                if (!box.contains(event.x, event.y)) return false
                downX = event.x; downY = event.y
                startX = InstrumentLyricsSettings.floatingValue(context, "x").toFloat()
                startY = InstrumentLyricsSettings.floatingValue(context, "y").toFloat()
                if (InstrumentLyricsSettings.floatingLocked(context)) {
                    pendingDrag = true
                    postDelayed(beginLongDrag, ViewConfiguration.getLongPressTimeout().toLong())
                    return false
                }
                stopTransition()
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingDrag) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop)
                        cancelPendingDrag()
                }
                if (dragging) {
                    val margin = EDGE_DP * resources.displayMetrics.density
                    dragX = (startX + (event.x - downX) * 100 / (width - box.width() - 2 * margin).coerceAtLeast(1f)).toInt().coerceIn(0, 100)
                    dragY = (startY + (event.y - downY) * 100 / (height - box.height() - 2 * margin).coerceAtLeast(1f)).toInt().coerceIn(0, 100)
                    invalidate(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingDrag()
                if (dragging) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        dragX?.let { InstrumentLyricsSettings.saveFloating(context, "x", it) }
                        dragY?.let { InstrumentLyricsSettings.saveFloating(context, "y", it) }
                    }
                    dragX = null; dragY = null; dragging = false; invalidate()
                    return true
                }
            }
        }
        return dragging
    }
    companion object {
        private const val MARQUEE_SPEED_DP = 36f
        private const val MARQUEE_HOLD_MILLIS = 1200f
        private const val STATUS_TITLE_DP = 32f
        private const val STATUS_DETAIL_DP = 26f
        private const val MIN_CONTENT_DP = 180f
        private const val WIDTH_QUANTUM_DP = 16f
        private const val WIDTH_HOLD_DP = 32f
        private const val TRANSITION_MILLIS = 250L
        private const val VERTICAL_PADDING_DP = 18f
        private const val MAX_WIDTH_FRACTION = .72f
        private const val MATERIAL_HOLD_MILLIS = 600L
        private const val EDGE_DP = 12f
        private const val PADDING_DP = 24f
        private const val FONT_DP = 54f
        private const val NEXT_FONT_DP = 40f
        private const val LINE_GAP_DP = 6f
        private const val CORNER_DP = 28f
        private const val EDGE_STROKE_DP = .8f
        private const val SHADOW_STEPS = 8
        private const val SHADOW_ALPHA = 3
    }
}
