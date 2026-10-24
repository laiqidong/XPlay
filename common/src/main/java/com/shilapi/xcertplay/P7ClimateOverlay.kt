package com.shilapi.xcertplay

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.shilapi.xcertplay.host.R
import org.json.JSONObject
import java.util.Locale

/** In-activity A/C sheet. The CarPlay bar remains uncovered and continues receiving input. */
internal class P7ClimateOverlay(private val parent: FrameLayout, private val eligible: () -> Boolean,
    source: () -> View?, private val videoBounds: () -> RectF, private val cancelTouch: () -> Unit,
    private val supported: () -> Boolean = { P7FogButton.supported() },
    private val night: () -> Boolean = { parent.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES },
    private val iconLoader: (Context, String) -> Drawable? = { c, key -> ClimateIcons.load(c, key) },
    sessionFactory: (Context, (JSONObject?) -> Unit, () -> Unit) -> P7ClimateSession = { c, changed, failed ->
        P7ClimateController(c, changed, failed)
    }) {
    private val context = parent.context
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var disposed = false
    private var state: JSONObject? = null
    private var pending = false
    private var monitoring = false
    private var uiScale = 1f
    private var fanSlider: FanSlider? = null
    private var layoutWidth = 0
    private var layoutHeight = 0
    private val controls = mutableMapOf<String, TextView>()
    private val panel = GlassPanel(context).apply { videoSource = source; visibility = View.GONE }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val entry = ClimateControlView(context, null, 0f, false, iconLoader).apply {
        text = "—"; typeface = context.resources.getFont(R.font.pingfang_medium); textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.climate_open)
        setOnClickListener { if (isOpen()) dismiss() else open() }
    }
    private val entryContrast = CarPlayControlContrast(entry, source, night) { updateEntryColor() }
    private val controller = sessionFactory(context, { data ->
        state = data; pending = false; paint()
    }, { Toast.makeText(context, R.string.climate_failed, Toast.LENGTH_SHORT).show() })
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || disposed) return
            val show = supported() && enabled(context) && eligible()
            entry.visibility = if (show) View.VISIBLE else View.GONE
            if (!show && isOpen()) dismiss()
            monitor(show)
            if (show) entryContrast.refresh() else entryContrast.stop()
            updateEntryColor()
            position()
            main.postDelayed(this, REFRESH_MS)
        }
    }
    init {
        parent.addView(entry, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        parent.addView(panel, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        panel.addView(content, FrameLayout.LayoutParams(-1, -1))
        panel.setOnTouchListener { _, _ -> true } // consume sheet whitespace, never forward to CarPlay
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
    }
    fun isOpen() = panel.visibility == View.VISIBLE
    fun dismiss(): Boolean {
        if (!isOpen()) return false
        panel.visibility = View.GONE; panel.active(false)
        pending = false; paint()
        return true
    }
    fun open() {
        if (!resumed || !enabled(context) || !eligible()) return
        cancelTouch(); position()
        panel.visibility = View.VISIBLE; panel.active(true); build(); monitor(true)
    }
    fun resume() { if (!disposed) { resumed = true; main.removeCallbacks(refresh); refresh.run() } }
    fun pause() { resumed = false; main.removeCallbacks(refresh); entry.visibility = View.GONE; dismiss(); monitor(false); entryContrast.stop() }
    private fun monitor(active: Boolean) {
        if (monitoring == active) return
        monitoring = active
        if (active) controller.start() else {
            controller.stop(); state = null; pending = false; paint()
        }
    }
    fun close() { if (!disposed) { pause(); disposed = true; controller.close() } }
    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).toInt()
    private fun s(id: Int) = context.getString(id)
    private fun position() {
        if (parent.width <= 0 || parent.height <= 0) return
        val bounds = videoBounds().takeUnless { it.isEmpty } ?: RectF(0f, 0f, parent.width.toFloat(), parent.height.toFloat())
        val bar = (bounds.height() * BAR_FRACTION).toInt().coerceIn(1, bounds.width().toInt())
        val size = (bounds.height() * ENTRY_FRACTION).toInt().coerceIn(dp(MIN_TOUCH_DP), dp(MAX_ENTRY_DP))
            .coerceAtMost(bar)
        val edge = (bounds.height() * OUTER_MARGIN_FRACTION).toInt()
            .coerceAtLeast(dp(MIN_OUTER_MARGIN_DP)).coerceAtMost((minOf(bounds.width(), bounds.height()) / 8).toInt())
        val left = bounds.left.toInt() + bar
        val w = (bounds.right.toInt() - left - edge).coerceAtLeast(1)
        val h = (bounds.height().toInt() - edge * 2).coerceAtLeast(1)
        entry.textSize = (bounds.height() / context.resources.displayMetrics.density * ENTRY_TEXT_FRACTION).coerceIn(24f, 40f)
        panel.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = left; topMargin = bounds.top.toInt() + edge
        }
        // Temperature readout sits in the free bar area below the battery.
        val entryWidth = (bar - edge).coerceAtLeast(size)
        entry.background = null
        entry.layoutParams = FrameLayout.LayoutParams(entryWidth, size, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = bounds.left.toInt() + (bar - entryWidth) / 2
            topMargin = (bounds.top + bounds.height() * ENTRY_Y_FRACTION).toInt()
                .coerceIn(bounds.top.toInt(), (bounds.bottom - size).toInt().coerceAtLeast(bounds.top.toInt()))
        }
        if (layoutWidth != w || layoutHeight != h) {
            layoutWidth = w; layoutHeight = h
            if (isOpen()) build()
        }
    }
    private fun build() {
        controls.clear(); fanSlider = null; content.removeAllViews()
        val density = context.resources.displayMetrics.density
        val availableW = layoutWidth / density
        val availableH = layoutHeight / density
        val scale = minOf(availableW / DESIGN_WIDTH_DP, availableH / DESIGN_HEIGHT_DP).coerceIn(.72f, 3f)
        uiScale = scale
        val gap = dp(GAP_DP * scale)
        val pad = dp(PADDING_DP * scale)
        content.setPadding(pad, pad, pad, pad)
        content.layoutDirection = View.LAYOUT_DIRECTION_LTR // driver/passenger follow physical sides
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = button("", "back", "back", 30f * scale) { dismiss() }
        back.contentDescription = s(R.string.climate_back)
        header.addView(back, LinearLayout.LayoutParams(dp(HEADER_DP * scale).coerceAtLeast(dp(MIN_TOUCH_DP)), dp(HEADER_DP * scale).coerceAtLeast(dp(MIN_TOUCH_DP))))
        val status = TextView(context).apply { textSize = 13f * scale; setTextColor(MUTED); gravity = Gravity.CENTER }
        controls["status"] = status
        header.addView(status, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(header, LinearLayout.LayoutParams(-1, dp(HEADER_DP * scale).coerceAtLeast(dp(MIN_TOUCH_DP))))
        val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -1))
        run {
            body.gravity = Gravity.CENTER_VERTICAL
            val groups = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(temperature("driver", s(R.string.climate_driver), scale), LinearLayout.LayoutParams(0, -1, 1f))
            top.addView(Space(context), LinearLayout.LayoutParams(gap * 2, 1))
            top.addView(fanGroup(scale), LinearLayout.LayoutParams(0, -1, FAN_GROUP_WEIGHT))
            top.addView(Space(context), LinearLayout.LayoutParams(gap * 2, 1))
            top.addView(temperature("passenger", s(R.string.climate_passenger), scale), LinearLayout.LayoutParams(0, -1, 1f))
            groups.addView(top, LinearLayout.LayoutParams(-1, dp(TOP_GROUP_DP * scale)))
            fun sectionSpace() {
                groups.addView(Space(context), LinearLayout.LayoutParams(-1, 0).apply {
                    topMargin = dp(SECTION_GAP_DP * scale); bottomMargin = dp(SECTION_GAP_DP * scale)
                })
            }
            sectionSpace()
            val controlSize = dp(BOTTOM_GROUP_DP * scale).coerceAtLeast(dp(MIN_TOUCH_DP))
            fun controlLabel(key: String): String = when (key) {
                "auto" -> "AUTO"
                "ac" -> "A/C"
                "eco" -> "ECO"
                else -> s(when (key) {
                    "power" -> R.string.climate_power
                    "sync" -> R.string.climate_sync
                    "window" -> R.string.climate_window
                    "face" -> R.string.climate_face
                    "feet" -> R.string.climate_feet
                    "cycle" -> R.string.climate_cycle_auto
                    "front" -> R.string.climate_front
                    "rear" -> R.string.climate_rear
                    "aqs" -> R.string.climate_aqs
                    "defog" -> R.string.climate_smart_defog
                    "deodor" -> R.string.climate_deodor
                    "clean" -> R.string.climate_clean
                    else -> R.string.climate_smart_passenger
                })
            }
            fun controlRow(keys: List<String>) {
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                keys.forEach { key ->
                    val control = button(controlLabel(key), key, if (key == "ac") "snow" else key, 14f * scale) {
                        when {
                            key == "ac" -> cycleAcMode()
                            key == "cycle" -> send(when (valid("cycle") ?: return@button) { 1 -> "CYCLE_OUTSIDE"; 2 -> "CYCLE_AUTO"; else -> "CYCLE_INNER" })
                            key in listOf("window", "face", "feet") -> send("DIRECTION_${key.uppercase(Locale.ROOT)}")
                            key !in UNVERIFIED_KEYS -> toggle(key, key.uppercase(Locale.ROOT))
                        }
                    }
                    row.addView(FrameLayout(context).apply {
                        addView(control, FrameLayout.LayoutParams(controlSize, controlSize, Gravity.CENTER))
                    }, LinearLayout.LayoutParams(0, controlSize, 1f))
                }
                groups.addView(row, LinearLayout.LayoutParams(-1, controlSize))
            }
            controlRow(listOf("power", "ac", "auto", "sync", "window", "face", "feet", "cycle"))
            sectionSpace()
            controlRow(listOf("front", "rear", "deodor", "aqs", "clean", "smart_passenger", "defog", "eco"))
            if (availableW < MIN_LAYOUT_WIDTH_DP) {
                val horizontal = HorizontalScrollView(context).apply { isFillViewport = true }
                horizontal.addView(groups, FrameLayout.LayoutParams(dp(MIN_LAYOUT_WIDTH_DP), -2))
                body.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
            } else body.addView(groups, LinearLayout.LayoutParams(-1, -2))
        }
        paint()
    }
    private fun temperature(key: String, label: String, scale: Float) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        val valueRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        valueRow.addView(button("—", key, null, 40f * scale) {}.apply { contentDescription = label }, LinearLayout.LayoutParams(-1, -1))
        addView(valueRow, LinearLayout.LayoutParams(-1, dp(TEMPERATURE_VALUE_DP * scale)))
        val adjust = LinearLayout(context)
        val size = dp(TEMPERATURE_ADJUST_DP * scale).coerceAtLeast(dp(MIN_TOUCH_DP))
        adjust.addView(button("", "${key}_down", "minus", 20f) { send("${key.uppercase(Locale.ROOT)}_DOWN") }, LinearLayout.LayoutParams(size, -1))
        adjust.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        adjust.addView(button("", "${key}_up", "plus", 20f) { send("${key.uppercase(Locale.ROOT)}_UP") }, LinearLayout.LayoutParams(size, -1))
        addView(adjust, LinearLayout.LayoutParams(-1, dp(TEMPERATURE_ADJUST_DP * scale).coerceAtLeast(dp(MIN_TOUCH_DP))))
    }
    private fun fanGroup(scale: Float) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        val valueRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        valueRow.addView(button("—", "fan", "fan", FAN_VALUE_TEXT_DP * scale) {}.apply {
            contentDescription = s(R.string.climate_fan)
        }, LinearLayout.LayoutParams(dp(FAN_VALUE_WIDTH_DP * scale), -1))
        val slider = FanSlider(context,
            commit = { level -> send("FAN_LEVEL_$level") },
            preview = { level -> setFanReadout(level?.toString() ?: when(val actual = valid("fan")) {
                null -> "—"; 14 -> "AUTO"; else -> actual.toString()
            }) }).also { fanSlider = it }
        valueRow.addView(slider, LinearLayout.LayoutParams(0, dp(TEMPERATURE_ADJUST_DP * scale), 1f).apply {
            leftMargin = dp(INNER_DP * scale)
        })
        addView(valueRow, LinearLayout.LayoutParams(-1, dp(TEMPERATURE_VALUE_DP * scale)))
        val adjust = LinearLayout(context)
        val controlSize = dp(TEMPERATURE_ADJUST_DP * scale).coerceAtLeast(dp(MIN_TOUCH_DP))
        adjust.addView(button("", "fan_down", "minus", 20f) { send("FAN_DOWN") }, LinearLayout.LayoutParams(controlSize, controlSize))
        adjust.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        adjust.addView(button("", "fan_up", "plus", 20f) { send("FAN_UP") }, LinearLayout.LayoutParams(controlSize, controlSize))
        addView(adjust, LinearLayout.LayoutParams(-1, controlSize))
    }
    private fun setFanReadout(value: String) {
        controls["fan"]?.apply {
            text = value
            textSize = (if (value == "AUTO") FAN_AUTO_TEXT_DP else FAN_VALUE_TEXT_DP) * uiScale
        }
    }
    private fun button(label: String, key: String, icon: String?, font: Float, action: () -> Unit): TextView {
        val valueOnly = key in listOf("driver", "passenger", "fan")
        return ClimateControlView(context, icon, (when (icon) {
            "back", "more" -> BACK_ICON_DP
            "plus", "minus" -> ADJUST_ICON_DP
            "fan" -> FAN_TITLE_ICON_DP
            "power", "front", "rear", "window" -> LINE_ICON_DP
            "face", "feet" -> AIRFLOW_ICON_DP
            "sync" -> SYNC_ICON_DP
            "up", "down" -> CHEVRON_DP
            else -> ICON_DP
        }) * uiScale, key in ICON_ONLY_KEYS, iconLoader, key in HORIZONTAL_KEYS).apply {
            text = label
            typeface = context.resources.getFont(if (key in listOf("driver", "passenger", "fan", "back")) R.font.pingfang_regular else R.font.pingfang_medium)
            textScaleX = 1f
            textSize = font; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(dp(INNER_DP), dp(INNER_DP), dp(INNER_DP), dp(INNER_DP))
            isSingleLine = false
            contentDescription = when {
                key.endsWith("_up") -> s(if (key.startsWith("fan")) R.string.climate_increase_fan else R.string.climate_increase_temp)
                key.endsWith("_down") -> s(if (key.startsWith("fan")) R.string.climate_decrease_fan else R.string.climate_decrease_temp)
                else -> label
            }
            if (!valueOnly) { isClickable = true; isFocusable = true; setOnClickListener { action() } }
            if (icon != null && key in ICON_ONLY_KEYS) setOnLongClickListener {
                Toast.makeText(context, contentDescription, Toast.LENGTH_SHORT).show(); true
            }
            controls[key] = this
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val plain = valueOnly
                if (!plain) background = surface(this, isSelected, roundControl(key))
            }
        }
    }
    private fun valid(key: String): Int? {
        val data = state ?: return null
        if (data.isNull(key) || !data.has(key)) return null
        val value = data.optInt(key, -1)
        return when (key) {
            "fan" -> value.takeIf { it in 0..10 || it == 14 }
            "direction" -> value.takeIf { it in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 14) }
            "sync" -> value.takeIf { it in 1..3 }
            "cycle" -> value.takeIf { it == 1 || it == 2 || it == 6 }
            "ac" -> value.takeIf { it in 0..7 }
            else -> value.takeIf { it == 0 || it == 1 }
        }
    }
    private fun temperatureValue(key: String): Double? = state?.takeUnless { it.isNull(key) }?.optDouble(key)
        ?.takeIf { it.isFinite() && it in 10.0..40.0 }
    private fun selected(key: String): Boolean = when (key) {
        "driver", "passenger", "fan" -> false
        "sync" -> valid(key) in listOf(1, 2)
        "ac" -> valid(key) in listOf(1, 3, 5, 7)
        "cycle" -> valid(key) == 1
        "window" -> valid("direction") in listOf(4, 5, 6, 7, 8, 9)
        "face" -> valid("direction") in listOf(1, 2, 8, 9)
        "feet" -> valid("direction") in listOf(2, 3, 4, 5, 7, 9)
        "facefeet" -> valid("direction") == 2
        else -> valid(key) == 1
    }
    private fun updateEntryColor() {
        val dark = entryContrast.darkBackground
        entry.setTextColor(if (valid("power") == 1) {
            if (dark) Color.WHITE else INK
        } else if (dark) MUTED else Color.rgb(85, 91, 101))
    }
    private fun paint() {
        val power = valid("power")
        entry.text = when (power) {
            0 -> "OFF"
            1 -> temperatureValue("driver")?.let { String.format(Locale.getDefault(), "%.1f°", it) } ?: "—"
            else -> "—"
        }
        updateEntryColor()
        entry.contentDescription = s(R.string.climate_open) + " · " + entry.text

        val hasState = state != null
        controls["status"]?.text = if (hasState) "" else s(R.string.climate_unavailable)
        controls.forEach { (key, view) ->
            if (key in listOf("back", "more", "status")) return@forEach
            val property = when {
                key.endsWith("_up") -> key.removeSuffix("_up")
                key.endsWith("_down") -> key.removeSuffix("_down")
                key in listOf("window", "face", "feet", "facefeet") -> "direction"
                else -> key
            }
            val available = if (key in UNVERIFIED_KEYS) false else if (key in STOCK_KEYS) context.packageManager.getLaunchIntentForPackage(STOCK_PACKAGE) != null else if (property == "driver" || property == "passenger") temperatureValue(property) != null else valid(property) != null
            view.isEnabled = available && !pending
            view.alpha = if (available) 1f else .4f
            view.isSelected = selected(key)
            if (key in listOf("driver", "passenger")) {
                view.text = temperatureValue(key)?.let { String.format(Locale.getDefault(), "%.1f°", it) } ?: "—"
            } else if (key == "fan" && fanSlider?.isTracking != true) setFanReadout(when (val v = valid(key)) { null -> "—"; 14 -> "AUTO"; else -> v.toString() })
            else if (key == "cycle") view.text = s(when (valid(key)) { 1 -> R.string.climate_cycle_inner; 2 -> R.string.climate_cycle_outside; else -> R.string.climate_cycle_auto })
            if (key !in listOf("driver", "passenger", "fan")) {
                view.background = surface(view, view.isSelected, roundControl(key))
            }
            val color = if (view.isSelected) INK else Color.WHITE
            view.setTextColor(color)
            if (key == "ac") valid("ac")?.let { mode ->
                (view as? ClimateControlView)?.setIcon("ac_mode_${mode / 2 + 1}")
                view.contentDescription = context.getString(AC_MODE_LABELS[mode / 2])
            }
            if (key == "cycle") view.contentDescription = view.text
            if (key == "cycle") (view as? ClimateControlView)?.setIcon(when(valid("cycle")) {
                1 -> "cycle_inner"; 2 -> "cycle_outside"; else -> "cycle"
            })
            if (view.isClickable) view.contentDescription = "${view.contentDescription.toString().substringBefore(" · ")} · ${if (!available) s(R.string.climate_unavailable) else if (view.isSelected) s(R.string.climate_on) else s(R.string.climate_off)}"
        }
        fanSlider?.apply {
            isEnabled = valid("fan") != null && !pending
            value = valid("fan")?.takeIf { it in 0..FAN_LEVELS }
            automatic = valid("fan") == 14
            alpha = if (valid("fan") == null) .4f else 1f
        }
    }
    private fun openStockClimate() {
        val intent = context.packageManager.getLaunchIntentForPackage(STOCK_PACKAGE)
        if (intent != null) runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, R.string.climate_unavailable, Toast.LENGTH_SHORT).show() }
    }
    private fun cycleAcMode() {
        val current = valid("ac") ?: return
        if (current !in 0..7) return
        send(AC_MODE_COMMANDS[(current / 2 + 1) % AC_MODE_COMMANDS.size])
    }
    private fun toggle(key: String, command: String) { if (valid(key) != null) send("${command}_${if (selected(key)) "OFF" else "ON"}") }
    private fun send(command: String) {
        if (pending || !isOpen() || !resumed || !enabled(context) || !eligible()) return
        if (controller.request(command)) { pending = true; paint() }
    }
    private fun roundControl(key: String) = key == "back" || key in BOTTOM_KEYS || key in MIDDLE_KEYS || key.endsWith("_up") || key.endsWith("_down")

    private fun surface(owner: View, selected: Boolean, circle: Boolean = false): Drawable =
        ClimateGlassDrawable(owner, selected, circle)

    /** Samples the same blurred scene as the sheet; only the button lens and tint differ. No ripple. */
    private inner class ClimateGlassDrawable(private val owner: View, private val selected: Boolean,
        private val circle: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private val path = Path()
        private var opacity = 255
        private fun radius(): Float = minOf(bounds.width(), bounds.height()) / 2f
        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val radius = radius()
            val density = context.resources.displayMetrics.density
            val shadowInset = density * BUTTON_SHADOW_INSET_DP
            rect.set(bounds); rect.inset(shadowInset, shadowInset)
            glassShape(path, rect, (radius - shadowInset).coerceAtLeast(0f), circle)
            // Keep a soft contact shadow inside drawable bounds so the view cannot clip it.
            paint.shader = null; paint.style = Paint.Style.STROKE
            val shadowSave = canvas.save()
            canvas.translate(0f, density * BUTTON_SHADOW_OFFSET_DP)
            for (step in 3 downTo 1) {
                paint.strokeWidth = density * step
                paint.color = Color.BLACK; paint.alpha = 12 * opacity / 255
                canvas.drawPath(path, paint)
            }
            canvas.restoreToCount(shadowSave)
            panel.drawLens(canvas, owner, if (owner.isPressed) PRESSED_LENS_SCALE else LENS_SCALE, opacity, path)
            paint.style = Paint.Style.FILL; paint.shader = null
            // A raised button needs its own neutral density, independent of the sheet.
            // Bright scenes get more absorption; dark scenes retain a translucent lens.
            val brightness = panel.brightness(owner) ?: 128f
            val absorption = (BUTTON_MIN_ABSORPTION + brightness / 255f * BUTTON_ABSORPTION_RANGE).toInt()
            paint.color = Color.BLACK; paint.alpha = absorption * opacity / 255
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL; paint.shader = null
            paint.color = if (selected) Color.argb(if (owner.isPressed) 236 else 250, 250, 251, 252)
                else Color.argb(if (owner.isPressed) 28 else 12, 255, 255, 255)
            paint.alpha = Color.alpha(paint.color) * opacity / 255
            canvas.drawPath(path, paint)
            // A weak top glint gives the lens depth without painting an opaque gradient tile.
            paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(0x18ffffff, 0x00ffffff, 0x00ffffff, 0x10ffffff), floatArrayOf(0f, .18f, .8f, 1f), Shader.TileMode.CLAMP)
            paint.alpha = opacity
            canvas.drawPath(path, paint)
            val stroke = context.resources.displayMetrics.density * GlassRim.WIDTH_DP
            rect.inset(stroke / 2f, stroke / 2f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
            paint.shader = GlassRim.shader(rect, emphasized = selected, raised = true)
            paint.alpha = opacity
            glassShape(path, rect, (radius - shadowInset - stroke / 2).coerceAtLeast(0f), circle)
            canvas.drawPath(path, paint)
            paint.shader = null; paint.style = Paint.Style.FILL
        }
        override fun getOutline(outline: Outline) {
            // Android 9 rejects some continuous cubic contours in setConvexPath.
            // The material draws its own contour; this outline is only for system shadows.
            if (circle) outline.setOval(bounds) else outline.setRoundRect(bounds, radius())
        }
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    /** One continuous hit area; a gesture commits exactly once on release. */
    private class FanSlider(context: Context, private val commit: (Int) -> Unit, private val preview: (Int?) -> Unit) : View(context) {
        var value: Int? = null
            set(newValue) { field = newValue; if (!dragging) invalidate() }
        var automatic = false
        val isTracking: Boolean get() = dragging
        private var dragging = false
        private var draft = 1
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            isFocusable = true; isClickable = true
            contentDescription = context.getString(R.string.climate_fan)
        }
        override fun onDraw(canvas: Canvas) {
            val top = height * FAN_TRACK_TOP_FRACTION
            val bottom = height * FAN_TRACK_BOTTOM_FRACTION
            val step = width.toFloat() / FAN_LEVELS
            val shown = if (dragging) draft else value
            for (i in 0 until FAN_LEVELS) {
                val bar = RectF(step * (i + FAN_BAR_INSET_FRACTION), top, step * (i + 1 - FAN_BAR_INSET_FRACTION), bottom)
                paint.color = if (shown != null && i < shown) Color.WHITE else 0x38ffffff
                canvas.drawRoundRect(bar, bar.width() / 2, bar.width() / 2, paint)
            }

        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                    updateDraft(event.x); return true
                }
                MotionEvent.ACTION_MOVE -> { updateDraft(event.x); return true }
                MotionEvent.ACTION_UP -> {
                    updateDraft(event.x); dragging = false; parent?.requestDisallowInterceptTouchEvent(false)
                    performClick(); commit(draft); invalidate(); return true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; preview(null); parent?.requestDisallowInterceptTouchEvent(false); invalidate(); return true }
            }
            return super.onTouchEvent(event)
        }
        private fun updateDraft(x: Float) {
            val next = ((x / width.coerceAtLeast(1) * FAN_LEVELS).toInt() + 1).coerceIn(1, FAN_LEVELS)
            if (next != draft) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            draft = next
            preview(draft)
            contentDescription = context.getString(R.string.climate_fan_level, draft)
            invalidate()
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.SeekBar"
            info.rangeInfo = android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1f, FAN_LEVELS.toFloat(), (value ?: 1).toFloat())
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
            if (!isEnabled) return false
            val target = when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> (value ?: 1) + 1
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> (value ?: 1) - 1
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ->
                    args?.getFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.toInt() ?: return false
                else -> return super.performAccessibilityAction(action, args)
            }.coerceIn(1, FAN_LEVELS)
            commit(target); return true
        }
    }
    /** Draw the icon and label as one centered block; compound drawables pin to the top on E28. */
    private class ClimateControlView(context: Context, icon: String?, private val iconDp: Float, private val iconOnly: Boolean = false, private val loader: (Context, String) -> Drawable?, private val horizontal: Boolean = false) : TextView(context) {
        init { stateListAnimator = null; foreground = null }
        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            if (isClickable) {
                animate().cancel()
                val scale = if (pressed) PRESS_SCALE else 1f
                animate().scaleX(scale).scaleY(scale).setDuration(PRESS_DURATION_MS).start()
            }
            background?.invalidateSelf()
        }
        private var iconKey = icon
        private var glyph = icon?.let { loader(context, it) }
        fun setIcon(key: String) {
            if (iconKey == key) return
            iconKey = key; glyph = loader(context, key); invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val icon = glyph ?: return super.onDraw(canvas)
            val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
            val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
            paint.color = currentTextColor
            if (horizontal && !iconOnly && text.isNotBlank()) {
                val iconSize = minOf((iconDp * resources.displayMetrics.density).toInt(), availableHeight)
                val gap = resources.displayMetrics.density * INNER_DP * 2
                val textWidth = paint.measureText(text.toString()).coerceAtMost((availableWidth - iconSize - gap).coerceAtLeast(1f))
                val left = paddingLeft + (availableWidth - iconSize - gap - textWidth) / 2f
                val top = paddingTop + (availableHeight - iconSize) / 2f
                val fit = minOf(iconSize.toFloat() / icon.intrinsicWidth.coerceAtLeast(1), iconSize.toFloat() / icon.intrinsicHeight.coerceAtLeast(1))
                val w = icon.intrinsicWidth.coerceAtLeast(1) * fit; val h = icon.intrinsicHeight.coerceAtLeast(1) * fit
                val iconX = if (iconKey == "more") left + textWidth + gap else left
                icon.setTint(currentTextColor)
                icon.setBounds((iconX + (iconSize - w) / 2).toInt(), (top + (iconSize - h) / 2).toInt(),
                    (iconX + (iconSize + w) / 2).toInt(), (top + (iconSize + h) / 2).toInt())
                icon.draw(canvas)
                val metrics = paint.fontMetrics
                val baseline = paddingTop + (availableHeight - metrics.ascent - metrics.descent) / 2f
                val textX = if (iconKey == "more") left else left + iconSize + gap
                canvas.drawText(text.toString(), textX, baseline, paint)
                return
            }
            val label = if (!iconOnly && text.isNotBlank()) android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build() else null
            val gap = if (label != null) (resources.displayMetrics.density * INNER_DP).toInt() else 0
            val iconSize = minOf((iconDp * resources.displayMetrics.density).toInt(), availableWidth,
                (availableHeight - (label?.height ?: 0) - gap).coerceAtLeast(1))
            val top = paddingTop + (availableHeight - iconSize - (label?.height ?: 0) - gap) / 2
            val left = paddingLeft + (availableWidth - iconSize) / 2
            icon.setTint(currentTextColor)
            // OEM wordmarks and automotive icons have different natural aspect ratios.
            // Fit inside the slot without forcing every drawable into a square.
            val naturalWidth = icon.intrinsicWidth.takeIf { it > 0 } ?: iconSize
            val naturalHeight = icon.intrinsicHeight.takeIf { it > 0 } ?: iconSize
            val fit = minOf(iconSize.toFloat() / naturalWidth, iconSize.toFloat() / naturalHeight)
            val drawWidth = (naturalWidth * fit).toInt().coerceAtLeast(1)
            val drawHeight = (naturalHeight * fit).toInt().coerceAtLeast(1)
            val drawLeft = left + (iconSize - drawWidth) / 2
            val drawTop = top + (iconSize - drawHeight) / 2
            icon.setBounds(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight)
            val iconSave = canvas.save()
            icon.draw(canvas)
            canvas.restoreToCount(iconSave)
            label?.let {
                val save = canvas.save()
                canvas.translate(paddingLeft.toFloat(), (top + iconSize + gap).toFloat())
                it.draw(canvas); canvas.restoreToCount(save)
            }
        }
    }
    private class GlassPanel(context: Context) : FrameLayout(context) {
        private val backdrop = FloatingLyricsBackdrop(this) {
            invalidate()
            fun refresh(view: View) {
                if (view.background is ClimateGlassDrawable) view.postInvalidateOnAnimation()
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) refresh(view.getChildAt(i))
            }
            for (i in 0 until childCount) refresh(getChildAt(i))
        }
        var videoSource: (() -> View?)?
            get() = backdrop.source
            set(value) { backdrop.source = value }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip = Path()
        private val ownerPosition = IntArray(2)
        private val panelPosition = IntArray(2)
        private val lensBounds = RectF()
        fun brightness(owner: View): Float? {
            val frame = backdrop.frame ?: return null
            owner.getLocationInWindow(ownerPosition); getLocationInWindow(panelPosition)
            val x = (ownerPosition[0] - panelPosition[0]).toFloat()
            val y = (ownerPosition[1] - panelPosition[1]).toFloat()
            return frame.brightness(RectF(x, y, x + owner.width, y + owner.height))
        }
        fun drawLens(canvas: Canvas, owner: View, scale: Float, opacity: Int, shape: Path) {
            val frame = backdrop.frame ?: return
            if (frame.bitmap.isRecycled) return
            owner.getLocationInWindow(ownerPosition); getLocationInWindow(panelPosition)
            lensBounds.set(frame.bounds)
            lensBounds.offset((panelPosition[0] - ownerPosition[0]).toFloat(), (panelPosition[1] - ownerPosition[1]).toFloat())
            val shader = BitmapShader(frame.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix().apply {
                setRectToRect(RectF(0f, 0f, frame.bitmap.width.toFloat(), frame.bitmap.height.toFloat()), lensBounds, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, owner.width / 2f, owner.height / 2f)
            }
            shader.setLocalMatrix(matrix)
            paint.shader = shader; paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.alpha = opacity
            // Share the continuous contour with every material layer, without a hard clip.
            canvas.drawPath(shape, paint)
            paint.shader = null
        }
        init { setWillNotDraw(false) }
        fun active(active: Boolean) { backdrop.setActive(active) }
        override fun onDetachedFromWindow() { backdrop.setActive(false); super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            val box = RectF(0f, 0f, width.toFloat(), height.toFloat())
            backdrop.region = box
            val radius = minOf(height * CARD_CORNER_FRACTION, width / 2f)
            clip.reset(); clip.addRoundRect(box, radius, radius, Path.Direction.CW)
            val save = canvas.save(); canvas.clipPath(clip)
            val frame = backdrop.frame
            if (frame != null && !frame.bitmap.isRecycled) {
                paint.color = Color.WHITE
                canvas.drawBitmap(frame.bitmap, null, frame.bounds, paint)
                paint.color = 0x38000000
            } else paint.color = 0xff242424.toInt()
            canvas.drawRect(box, paint)
            canvas.restoreToCount(save)
            paint.color = Color.WHITE; paint.alpha = 255; paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * GlassRim.WIDTH_DP
            paint.shader = GlassRim.shader(box)
            val edge = RectF(box).apply { inset(paint.strokeWidth / 2, paint.strokeWidth / 2) }
            canvas.drawRoundRect(edge, radius, radius, paint); paint.style = Paint.Style.FILL; paint.shader = null
        }
    }
    companion object {
        /** Two cubic segments per corner enter and leave the straight edges with zero curvature. */
        private fun glassShape(path: Path, box: RectF, radius: Float, circle: Boolean) {
            path.reset()
            if (circle) { path.addOval(box, Path.Direction.CW); return }
            val r = radius.coerceAtMost(minOf(box.width(), box.height()) / 2f)
            fun corner(x: Float, y: Float, tx: Float, ty: Float, nx: Float, ny: Float) {
                fun px(a: Float, b: Float) = x + r * (tx * a + nx * b)
                fun py(a: Float, b: Float) = y + r * (ty * a + ny * b)
                path.cubicTo(px(.45f, 0f), py(.45f, 0f), px(.68f, 0f), py(.68f, 0f), px(.82f, .18f), py(.82f, .18f))
                path.cubicTo(px(1f, .32f), py(1f, .32f), px(1f, .55f), py(1f, .55f), px(1f, 1f), py(1f, 1f))
            }
            path.moveTo(box.left + r, box.top)
            path.lineTo(box.right - r, box.top)
            corner(box.right - r, box.top, 1f, 0f, 0f, 1f)
            path.lineTo(box.right, box.bottom - r)
            corner(box.right, box.bottom - r, 0f, 1f, -1f, 0f)
            path.lineTo(box.left + r, box.bottom)
            corner(box.left + r, box.bottom, -1f, 0f, 0f, -1f)
            path.lineTo(box.left, box.top + r)
            corner(box.left, box.top + r, 0f, -1f, 1f, 0f)
            path.close()
        }
        private const val PREFS = "p7_climate_overlay"
        fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("enabled", true)
        fun enable(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        }
        private const val REFRESH_MS = 200L
        private const val BAR_FRACTION = .13f
        private const val ENTRY_FRACTION = .08f
        private const val ENTRY_Y_FRACTION = .17f
        private const val MIN_TOUCH_DP = 48f
        private const val MAX_ENTRY_DP = 112f
        private const val OUTER_MARGIN_FRACTION = .008333333f
        private const val MIN_OUTER_MARGIN_DP = 6f
        private const val ENTRY_TEXT_FRACTION = .032f
        private val MIDDLE_KEYS = setOf("power", "ac", "sync", "window", "face", "feet")
        private val BOTTOM_KEYS = setOf("auto", "cycle", "front", "rear", "deodor", "aqs", "clean", "smart_passenger", "defog", "eco")
        private val UNVERIFIED_KEYS = setOf("deodor", "clean", "smart_passenger")
        private val QUIET_KEYS = setOf("power", "ac", "sync", "auto", "cycle", "front", "rear")
        private val ICON_ONLY_KEYS = BOTTOM_KEYS + setOf("back", "more", "power", "ac", "sync", "window", "face", "feet", "cycle", "front", "rear")
        private val HORIZONTAL_KEYS = setOf("cycle", "front", "rear", "more", "fan")
        private const val MORE_WIDTH_DP = 140f
        private const val TOP_GROUP_DP = 150f
        private const val MIDDLE_GROUP_DP = 56f
        private const val BOTTOM_GROUP_DP = 56f
        private const val SECTION_GAP_DP = 20f
        private const val TEMPERATURE_VALUE_DP = 64f
        private const val TEMPERATURE_ADJUST_DP = 50f
        private const val ADJUST_ICON_DP = 26f
        private const val FAN_TITLE_ICON_DP = 32f
        private const val FAN_GROUP_WEIGHT = 1f
        private const val FAN_VALUE_WIDTH_DP = 104f
        private const val FAN_AUTO_TEXT_DP = 14f
        private const val FAN_VALUE_TEXT_DP = 18f
        private const val FAN_LEVELS = 10
        private const val STOCK_PACKAGE = "com.xiaopeng.carhvac"
        private val STOCK_KEYS = setOf("max", "stock")
        private const val AC_PICKER_WIDTH_DP = 320f
        private const val AC_PICKER_HEIGHT_DP = 84f
        private const val POPUP_CORNER_DP = 18f
        private val AC_MODE_LABELS = intArrayOf(R.string.climate_ac_cool, R.string.climate_ac_heat, R.string.climate_ac_natural, R.string.climate_ac_auto)
        private val AC_MODE_COMMANDS = arrayOf("AC_COOL", "AC_HEAT", "AC_NATURAL", "AC_AUTO")
        private const val FAN_TRACK_TOP_FRACTION = .18f
        private const val FAN_TRACK_BOTTOM_FRACTION = .82f
        private const val FAN_BAR_INSET_FRACTION = .22f
        private const val FAN_BAR_WIDTH_DP = 14f
        private const val FAN_BAR_MIN_DP = 20f
        private const val FAN_BAR_RANGE_DP = 20f
        private const val BACK_ICON_DP = 24f
        private const val MIN_LAYOUT_WIDTH_DP = 600f
        private const val DESIGN_WIDTH_DP = 1000f
        private const val DESIGN_HEIGHT_DP = 560f
        private const val GAP_DP = 14f
        private const val PADDING_DP = 24f
        private const val HEADER_DP = 48f
        private const val BOTTOM_DP = 64f
        private const val MIN_BODY_DP = 240f
        private const val LABEL_DP = 42f
        private const val EXTRA_HEIGHT_DP = 100f
        private const val INNER_DP = 6f
        private const val CHEVRON_DP = 40f
        // Optical sizes compensate for the heavier strokes in the OEM automotive artwork.
        private const val LINE_ICON_DP = 36f
        private const val AIRFLOW_ICON_DP = 38f
        private const val SYNC_ICON_DP = 34f
        private const val ICON_DP = 38f
        private const val DEFAULT_CONTROL_DP = 160f
        private const val CARD_CORNER_FRACTION = .045f
        private const val BUTTON_SHADOW_INSET_DP = 2f
        private const val BUTTON_SHADOW_OFFSET_DP = 1f
        private const val BUTTON_MIN_ABSORPTION = 48f
        private const val BUTTON_ABSORPTION_RANGE = 72f
        private const val LENS_SCALE = 1.035f
        private const val PRESSED_LENS_SCALE = 1.055f
        private const val PRESS_SCALE = .975f
        private const val PRESS_DURATION_MS = 110L
        private const val EDGE_DP = .8f
        private val MUTED = Color.rgb(174, 185, 205)
        private val RED = Color.rgb(255, 76, 86)
        private val BLUE = Color.rgb(40, 164, 255)
        private val ACCENT = Color.rgb(64, 224, 234)
        private val INK = Color.rgb(23, 29, 39)
    }
}

/** Use the installed OEM HVAC artwork; do not redraw automotive pictograms. */
internal object ClimateIcons {
    private const val OEM_PACKAGE = "com.xiaopeng.carhvac"
    private var oemResources: android.content.res.Resources? = null
    fun load(context: Context, key: String, resourceOverride: android.content.res.Resources? = null): Drawable? {
        if (key == "sync") return context.getDrawable(R.drawable.ic_climate_sync)?.mutate()
        if (key == "facefeet") {
            val face = load(context, "face", resourceOverride) ?: return null
            val feet = load(context, "feet", resourceOverride) ?: return null
            val size = maxOf(face.intrinsicWidth, face.intrinsicHeight, 1)
            return android.graphics.drawable.LayerDrawable(arrayOf(face, feet)).apply {
                setLayerSize(0, size, size); setLayerSize(1, size, size)
                setLayerInset(0, 0, 0, size, 0); setLayerInset(1, size, 0, 0, 0)
            }
        }
        val name = if (key.startsWith("ac_mode_")) "hvac_ac_mode_level" else when (key) {
            "back" -> "ic_common_arrowleft_o"
            "more" -> "ic_common_arrowright_o"
            "plus" -> "ic_common_add_o"
            "minus" -> "ic_common_reduce_o"
            "up" -> "ic_common_arrowup_o"
            "down" -> "ic_common_arrowdown_o"
            "auto" -> "ic_auto_o"
            "cycle" -> "x_ic_autocycle_o"
            "cycle_inner" -> "ic_insidecycle_o"
            "cycle_outside" -> "ic_outcycle_o"
            "deodor" -> "ic_deodorization_o"
            "aqs" -> "ic_aqs_o"
            "clean" -> "ic_acdry_o"
            "smart_passenger" -> "ic_ventilation_o"
            "defog" -> "x_ic_smartdemister_o"
            "eco" -> "ic_eco_o"
            "front" -> "ic_frontwindowdemister_o"
            "rear" -> "ic_backwindowdemister_o"
            "power" -> "ic_common_switch_o"
            "snow" -> "ic_ac_o"
            "sync" -> "x_ic_sync_o"
            "fan" -> "ic_airvolume_o"
            "window" -> "ic_blowwindow_o"
            "face" -> "ic_blowchest_o"
            "feet" -> "ic_blowfoot_o"
            else -> return null
        }
        return runCatching {
            val resources = resourceOverride ?: oemResources ?: context.packageManager.getResourcesForApplication(OEM_PACKAGE).also { oemResources = it }
            val id = resources.getIdentifier(name, "drawable", OEM_PACKAGE)
            if (id == 0) null else resources.getDrawable(id, null)?.mutate()?.also { if (key.startsWith("ac_mode_")) it.level = key.substringAfterLast('_').toInt() }
        }.getOrNull()
    }
}
