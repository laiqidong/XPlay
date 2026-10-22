package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Shared production and debug-preview connection surface. */
internal class ConnectionWaitingView(context: Context, onBack: () -> Unit) : FrameLayout(context) {
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
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private val logo = ImageView(context).apply {
        setImageResource(R.drawable.ic_carplay)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.carplay)
    }
    private val connectionLine = object : LinearLayout(context) {
        private var preferredTextWidth = 0f
        private var preferredTextHeight = 0f
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            var lineWidthSpec = widthMeasureSpec
            if (available > 0 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                if (preferredTextWidth == 0f) {
                    title.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    preferredTextWidth = title.measuredWidth.toFloat()
                    preferredTextHeight = title.textSize
                }
                // Include theme letter spacing and leave a small inset for glyph bearings and rounding.
                val desired = preferredTextWidth + preferredTextHeight * INDICATOR_TO_TEXT_RATIO +
                    dp(HEADER_GAP_DP + LINE_FIT_INSET_DP)
                lineWidthSpec = MeasureSpec.makeMeasureSpec(minOf(available, desired.toInt()), MeasureSpec.EXACTLY)
                title.textSize = TITLE_SIZE_SP * minOf(1f, available / desired)
                val indicatorSize = (title.textSize * INDICATOR_TO_TEXT_RATIO).toInt().coerceAtLeast(1)
                progress.layoutParams.width = indicatorSize
                progress.layoutParams.height = indicatorSize
                title.forceLayout()
                progress.forceLayout()
            }
            super.onMeasure(lineWidthSpec, heightMeasureSpec)
            // Account for TextView's final shaping, not just proportional font estimates.
            repeat(3) {
                val shapedWidth = title.layout?.getLineWidth(0) ?: return@repeat
                if (shapedWidth <= title.measuredWidth || title.measuredWidth == 0) return@repeat
                val room = (title.measuredWidth - dp(LINE_FIT_INSET_DP)).coerceAtLeast(1)
                title.textSize = TITLE_SIZE_SP * title.textSize / preferredTextHeight * room / shapedWidth
                val size = (title.textSize * INDICATOR_TO_TEXT_RATIO).toInt().coerceAtLeast(1)
                progress.layoutParams.width = size
                progress.layoutParams.height = size
                title.forceLayout()
                progress.forceLayout()
                super.onMeasure(lineWidthSpec, heightMeasureSpec)
            }
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        // This fixed English phrase keeps its animation after the word in every locale.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
    val stage = TextView(context).apply {
        textSize = 17f
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val help = TextView(context).apply { textSize = 17f; gravity = Gravity.CENTER; visibility = GONE }
    val recovery = DiPlayButton(context).apply { visibility = GONE }
    val retry = DiPlayButton(context).apply { visibility = GONE }
    val connect = DiPlayButton(context).apply { setText(R.string.connect_phone); visibility = GONE }
    private val back = TextView(context).apply {
        setText(R.string.settings)
        textSize = 20f
        gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.settings)
        isClickable = true
        isFocusable = true
        compoundDrawablePadding = dp(HEADER_GAP_DP)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { onBack() }
    }
    init {
        addView(viewport, LayoutParams(-1, -1))
        panel.addView(logo, LinearLayout.LayoutParams(dp(LOGO_SIZE_DP), dp(LOGO_SIZE_DP)).apply {
            bottomMargin = dp(CONTENT_GAP_DP)
        })
        connectionLine.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val indicatorSize = (title.textSize * INDICATOR_TO_TEXT_RATIO).toInt()
        connectionLine.addView(progress, LinearLayout.LayoutParams(indicatorSize, indicatorSize).apply {
            marginStart = dp(HEADER_GAP_DP)
        })
        panel.addView(connectionLine, LinearLayout.LayoutParams(-2, -2))
        panel.addView(stage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTENT_GAP_DP) })
        panel.addView(help, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTENT_GAP_DP) })
        for (button in listOf(connect, recovery, retry)) {
            button.textSize = 18f
            panel.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTENT_GAP_DP) })
        }
        // The gear follows the settings label's actual scaled font size.
        val gear = context.getDrawable(R.drawable.ic_dp_advanced)!!.mutate()
        val gearSize = back.textSize.toInt()
        gear.setBounds(0, 0, gearSize, gearSize)
        back.setCompoundDrawablesRelative(gear, null, null, null)
        back.setPadding(dp(HEADER_PADDING_DP), dp(HEADER_PADDING_DP), dp(HEADER_PADDING_DP), dp(HEADER_PADDING_DP))
        back.minimumHeight = dp(BACK_TARGET_DP)
        addView(back, LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(BACK_MARGIN_DP); topMargin = dp(BACK_MARGIN_DP)
        })
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight -
            dp(CONTENT_MARGIN_DP * 4)).coerceAtLeast(1)
        val logoSize = minOf(dp(LOGO_SIZE_DP), available)
        logo.layoutParams.width = logoSize
        logo.layoutParams.height = logoSize
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    fun applyPalette(night: Boolean) {
        val palette = DiPlayPalette.settings(night)
        setBackgroundColor(palette.background)
        progress.color = palette.accent
        title.setTextColor(palette.primaryText)
        stage.setTextColor(palette.secondaryText)
        help.setTextColor(palette.secondaryText)
        back.setTextColor(palette.secondaryText)
        back.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(palette.secondaryText) }
        connect.style(DiPlayButtonKind.PRIMARY, palette, overlay = false, compactCorners = true)
        retry.style(DiPlayButtonKind.PRIMARY, palette, overlay = false, compactCorners = true)
        recovery.style(DiPlayButtonKind.SECONDARY, palette, overlay = false, compactCorners = true)
    }
    private companion object {
        const val LOGO_SIZE_DP = 144
        const val TITLE_SIZE_SP = 44f
        const val INDICATOR_TO_TEXT_RATIO = 0.65f
        const val LINE_FIT_INSET_DP = 8
        const val CONTENT_MARGIN_DP = 24
        const val CONTENT_GAP_DP = 28
        const val BACK_TARGET_DP = 56
        const val HEADER_GAP_DP = 8
        const val HEADER_PADDING_DP = 12
        const val BACK_MARGIN_DP = 12
    }
}
