package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R

import android.content.Context
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Scale waiting controls without changing host video geometry or touch coordinates. */
private fun connectionWaitingContext(base: Context): Context {
    val scaled = InterfaceSize.override(base.resources.configuration, InterfaceSize.preference(base)) ?: return base
    return ContextThemeWrapper(base, 0).apply {
        applyOverrideConfiguration(InterfaceSize.contextOverride(scaled))
    }
}

/** Shared production and debug-preview connection surface. */
internal class ConnectionWaitingView(hostContext: Context, onBack: () -> Unit) : FrameLayout(connectionWaitingContext(hostContext)) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(CONTENT_MARGIN_DP), dp(CONTENT_MARGIN_DP), dp(CONTENT_MARGIN_DP), dp(CONTENT_MARGIN_DP))
    }
    val viewport = ConnectionPreparationViewport(context, panel).apply { isClickable = true }
    val progress = ConnectionLoadingIndicator(context)
    val title = TextView(context).apply {
        setText(R.string.connection_loading)
        textSize = TITLE_SIZE_SP
        gravity = Gravity.CENTER
        typeface = AppFonts.medium(context)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setSingleLine(true)
    }
    private val logo = ImageView(context).apply {
        setImageResource(R.drawable.ic_carplay)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.carplay)
    }
    private val connectionLine = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val offeredWidth = MeasureSpec.getSize(widthMeasureSpec)
            val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
                dp(MAX_TITLE_WIDTH_DP) else (offeredWidth - paddingLeft - paddingRight).coerceAtLeast(1)
            title.textSize = TITLE_SIZE_SP
            title.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val naturalWidth = title.measuredWidth.coerceAtLeast(1)
            val animated = progress.visibility != GONE
            val naturalSide = if (animated) (title.textSize * INDICATOR_TO_TEXT_RATIO).toInt() + dp(HEADER_GAP_DP) else 0
            val room = (available - naturalSide * 2 - dp(LINE_FIT_INSET_DP)).coerceAtLeast(1)
            title.textSize = TITLE_SIZE_SP * minOf(1f, room.toFloat() / naturalWidth)
            title.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val indicatorSize = (title.textSize * INDICATOR_TO_TEXT_RATIO).toInt().coerceAtLeast(1)
            progress.layoutParams.width = indicatorSize
            progress.layoutParams.height = indicatorSize
            val side = if (animated) indicatorSize + dp(HEADER_GAP_DP) else 0
            val lineWidth = minOf(available, title.measuredWidth + side * 2 + dp(LINE_FIT_INSET_DP))
            super.onMeasure(MeasureSpec.makeMeasureSpec(lineWidth, MeasureSpec.EXACTLY), heightMeasureSpec)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (progress.visibility != GONE) {
                val start = title.right + dp(HEADER_GAP_DP)
                val y = (height - progress.measuredHeight) / 2
                progress.layout(start, y, start + progress.measuredWidth, y + progress.measuredHeight)
            }
        }
    }
    val stage = TextView(context).apply {
        textSize = DETAIL_SIZE_SP
        gravity = Gravity.CENTER
        typeface = AppFonts.regular(context)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val help = TextView(context).apply {
        textSize = DETAIL_SIZE_SP
        typeface = AppFonts.regular(context)
        gravity = Gravity.CENTER
        visibility = GONE
    }
    val recovery = DiPlayButton(context).apply { visibility = GONE }
    val retry = DiPlayButton(context).apply { visibility = GONE }
    val connect = DiPlayButton(context).apply { setText(R.string.connect_phone); visibility = GONE }
    private val back = DiPlayButton(context).apply {
        setText(R.string.settings_connection_back)
        textSize = BACK_TEXT_SP
        gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.settings_connection_back)
        tag = "connection_back_settings"
        isClickable = true
        isFocusable = true
        compoundDrawablePadding = dp(HEADER_GAP_DP)
        setOnClickListener { onBack() }
    }
    init {
        addView(viewport, LayoutParams(-1, -1))
        panel.addView(logo, LinearLayout.LayoutParams(dp(LOGO_SIZE_DP), dp(LOGO_SIZE_DP)).apply {
            bottomMargin = dp(CONTENT_GAP_DP)
        })
        connectionLine.addView(title, LayoutParams(-2, -2, Gravity.CENTER))
        val indicatorSize = (title.textSize * INDICATOR_TO_TEXT_RATIO).toInt()
        connectionLine.addView(progress, LayoutParams(indicatorSize, indicatorSize, Gravity.CENTER_VERTICAL or Gravity.END))
        panel.addView(connectionLine, LinearLayout.LayoutParams(-2, -2))
        panel.addView(stage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTENT_GAP_DP) })
        panel.addView(help, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTENT_GAP_DP) })
        for (button in listOf(connect, recovery, retry)) {
            button.textSize = ACTION_TEXT_SP
            button.minimumHeight = dp(ACTION_HEIGHT_DP)
            panel.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTENT_GAP_DP) })
        }
        val arrow = requireNotNull(context.getDrawable(R.drawable.ic_dp_back)).mutate()
        arrow.setBounds(0, 0, dp(BACK_ICON_DP), dp(BACK_ICON_DP))
        back.setCompoundDrawablesRelative(arrow, null, null, null)
        back.setPadding(dp(BACK_HORIZONTAL_PADDING_DP), dp(BACK_VERTICAL_PADDING_DP),
            dp(BACK_HORIZONTAL_PADDING_DP), dp(BACK_VERTICAL_PADDING_DP))
        back.minimumHeight = dp(BACK_TARGET_DP)
        addView(back, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            marginStart = dp(BACK_MARGIN_DP)
            topMargin = dp(BACK_MARGIN_DP)
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        val availableHeight = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(1)
        val roomy = availableWidth >= dp(ROOMY_WIDTH_DP) && availableHeight >= dp(ROOMY_HEIGHT_DP)
        val margin = dp(if (roomy) BACK_MARGIN_DP else COMPACT_BACK_MARGIN_DP)
        val backWidth = (availableWidth - margin * 2).coerceAtLeast(1)
        back.maxWidth = minOf(dp(BACK_MAX_WIDTH_DP), backWidth)
        back.minimumWidth = minOf(dp(if (roomy) BACK_MIN_WIDTH_DP else COMPACT_BACK_MIN_WIDTH_DP), backWidth)
        back.minimumHeight = dp(if (roomy) BACK_TARGET_DP else COMPACT_BACK_TARGET_DP)
        val textSize = if (roomy) BACK_TEXT_SP else COMPACT_BACK_TEXT_SP
        if (back.textSize != textSize * resources.displayMetrics.scaledDensity) back.textSize = textSize
        (back.layoutParams as LayoutParams).apply { marginStart = margin; topMargin = margin }
        val logoSize = minOf(dp(if (roomy) LOGO_SIZE_DP else COMPACT_LOGO_SIZE_DP),
            (availableWidth - dp(CONTENT_MARGIN_DP * 4)).coerceAtLeast(1))
        logo.layoutParams.width = logoSize
        logo.layoutParams.height = logoSize
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Reserve the actual button height, including enlarged accessibility text.
        val headerSpace = back.measuredHeight + margin * 2
        val bodyParams = viewport.layoutParams as LayoutParams
        if (bodyParams.topMargin != headerSpace) {
            bodyParams.topMargin = headerSpace
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
    fun applyPalette(night: Boolean) {
        val palette = DiPlayPalette.settings(night)
        setBackgroundColor(palette.background)
        progress.color = palette.accent
        title.setTextColor(palette.primaryText)
        stage.setTextColor(palette.secondaryText)
        help.setTextColor(palette.secondaryText)
        back.style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
        back.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(palette.primaryText) }
        connect.style(DiPlayButtonKind.PRIMARY, palette, overlay = false, compactCorners = true)
        retry.style(DiPlayButtonKind.PRIMARY, palette, overlay = false, compactCorners = true)
        recovery.style(DiPlayButtonKind.SECONDARY, palette, overlay = false, compactCorners = true)
    }
    private companion object {
        const val LOGO_SIZE_DP = 176
        const val COMPACT_LOGO_SIZE_DP = 128
        const val TITLE_SIZE_SP = 44f
        const val INDICATOR_TO_TEXT_RATIO = 0.65f
        const val LINE_FIT_INSET_DP = 8
        const val MAX_TITLE_WIDTH_DP = 560
        const val CONTENT_MARGIN_DP = 24
        const val CONTENT_GAP_DP = 28
        const val BACK_TARGET_DP = 80
        const val COMPACT_BACK_TARGET_DP = 64
        const val BACK_MIN_WIDTH_DP = 200
        const val COMPACT_BACK_MIN_WIDTH_DP = 160
        const val BACK_MAX_WIDTH_DP = 360
        const val BACK_TEXT_SP = 28f
        const val COMPACT_BACK_TEXT_SP = 22f
        const val DETAIL_SIZE_SP = 22f
        const val ACTION_TEXT_SP = 22f
        const val ACTION_HEIGHT_DP = 64
        const val BACK_ICON_DP = 28
        const val BACK_HORIZONTAL_PADDING_DP = 24
        const val BACK_VERTICAL_PADDING_DP = 16
        const val ROOMY_WIDTH_DP = 900
        const val ROOMY_HEIGHT_DP = 480
        const val COMPACT_BACK_MARGIN_DP = 16
        const val HEADER_GAP_DP = 8
        const val BACK_MARGIN_DP = 24
    }
}
