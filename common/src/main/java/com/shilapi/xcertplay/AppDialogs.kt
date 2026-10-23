package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.shilapi.xcertplay.host.R

internal interface AppAppearanceOwner {
    val currentAppNight: Boolean
}

private fun Context.dialogNight() = (this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow()
internal fun Context.appDialogTheme(): Int = if (dialogNight()) R.style.Theme_Xcertplay_Dialog_Dark else R.style.Theme_Xcertplay_Dialog_Light
internal fun Context.appDialogContext(): Context = ContextThemeWrapper(this, appDialogTheme())
internal fun Context.appDialogBuilder() = AppDialogBuilder(this)
internal fun Context.appDialog(): Dialog {
    val night = dialogNight()
    return object : Dialog(this, appDialogTheme()) {
        override fun onStart() {
            super.onStart()
            LiquidGlassStyle.dialog(this, night, this@appDialog)
        }
    }
}

/** Native AlertDialog owns title, lists, action buttons, scrolling and measurement. */
internal class AppDialogBuilder(private val owner: Context) {
    private val builder = AlertDialog.Builder(owner, owner.appDialogTheme())
    private var cancelable = true
    private var destructivePositive = false
    fun setTitle(value: CharSequence) = apply { builder.setTitle(value) }
    fun setTitle(value: Int) = apply { builder.setTitle(value) }
    fun setMessage(value: CharSequence) = apply { builder.setMessage(value) }
    fun setMessage(value: Int) = apply { builder.setMessage(value) }
    fun setView(value: View) = apply {
        builder.setView(LinearLayout(owner.appDialogContext()).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            setPadding((PANEL_INSET_DP * density).toInt(), (PANEL_GAP_DP * density).toInt(),
                (PANEL_INSET_DP * density).toInt(), (PANEL_GAP_DP * density).toInt())
            addView(value, LinearLayout.LayoutParams(-1, -2))
        })
    }
    fun setCancelable(value: Boolean) = apply { cancelable = value; builder.setCancelable(value) }
    fun setOnDismissListener(value: DialogInterface.OnDismissListener?) = apply { builder.setOnDismissListener(value) }
    fun setOnCancelListener(value: DialogInterface.OnCancelListener?) = apply { builder.setOnCancelListener(value) }
    fun setPositiveButton(text: CharSequence, click: DialogInterface.OnClickListener?) = apply { builder.setPositiveButton(text, click) }
    fun setNegativeButton(text: CharSequence, click: DialogInterface.OnClickListener?) = apply { builder.setNegativeButton(text, click) }
    fun setNeutralButton(text: CharSequence, click: DialogInterface.OnClickListener?) = apply { builder.setNeutralButton(text, click) }
    fun setPositiveButton(text: Int, click: DialogInterface.OnClickListener?) = apply { builder.setPositiveButton(text, click) }
    fun setDestructivePositiveButton(text: Int, click: DialogInterface.OnClickListener?) = apply {
        destructivePositive = true
        builder.setPositiveButton(text, click)
    }
    fun setNegativeButton(text: Int, click: DialogInterface.OnClickListener?) = apply { builder.setNegativeButton(text, click) }
    fun setNeutralButton(text: Int, click: DialogInterface.OnClickListener?) = apply { builder.setNeutralButton(text, click) }
    fun setItems(values: Array<out CharSequence>, click: DialogInterface.OnClickListener?) = apply { builder.setItems(values, click) }
    fun setSingleChoiceItems(values: Array<out CharSequence>, index: Int, click: DialogInterface.OnClickListener?) = apply {
        builder.setSingleChoiceItems(values, index, click)
    }
    fun setMultiChoiceItems(values: Array<out CharSequence>, checks: BooleanArray, click: DialogInterface.OnMultiChoiceClickListener?) = apply {
        builder.setMultiChoiceItems(values, checks, click)
    }
    fun create(): AlertDialog = builder.create().apply {
        setCanceledOnTouchOutside(cancelable)
        // Inflate once through the native controller, before caller-specific OnShow listeners run.
        create()
        val night = owner.dialogNight()
        val colors = DiPlayPalette.of(night)
        LiquidGlassStyle.dialog(this, night, owner)
        for (id in listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_POSITIVE)) {
            getButton(id)?.apply {
                isAllCaps = false
                textSize = 18f
                typeface = AppFonts.medium(context)
                val density = resources.displayMetrics.density
                minHeight = (PANEL_CONTROL_HEIGHT_DP * resources.configuration.fontScale.coerceAtLeast(1f) * density).toInt()
                minWidth = (PANEL_CONTROL_MIN_WIDTH_DP * density).toInt()
                setPadding((PANEL_INSET_DP * density).toInt(), 0, (PANEL_INSET_DP * density).toInt(), 0)
                stateListAnimator = null
                val primary = id == DialogInterface.BUTTON_POSITIVE
                setTextColor(if (primary) colors.onAccent else colors.primaryText)
                val tint = if (primary) { if (destructivePositive) colors.danger else colors.accent } else null
                background = LiquidGlassStyle.button(this, night, tint, colors.ripple)
            }
        }
        arrangeNativeActions(this)
        window?.apply {
            val width = minOf((PANEL_WIDTH_DP * owner.resources.displayMetrics.density).toInt(),
                owner.resources.displayMetrics.widthPixels - (PANEL_INSET_DP * 2 * owner.resources.displayMetrics.density).toInt())
            setLayout(width.coerceAtLeast(1), ViewGroup.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    fun show(): AlertDialog = create().apply { show() }
}

/** Keep the native action bar and callbacks; add explicit spacing around glass button surfaces. */
private fun arrangeNativeActions(dialog: AlertDialog) {
    val buttons = listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_POSITIVE)
        .mapNotNull(dialog::getButton).filter { it.visibility != View.GONE }
    val bar = buttons.firstOrNull()?.parent as? LinearLayout ?: return
    val density = bar.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    bar.isBaselineAligned = false
    bar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    bar.setPadding(dp(PANEL_INSET_DP), dp(PANEL_GAP_DP), dp(PANEL_INSET_DP), dp(PANEL_ACTION_BOTTOM_INSET_DP))
    val ordered = buttons.sortedBy(bar::indexOfChild)
    val dialogWidth = minOf(dp(PANEL_WIDTH_DP), bar.resources.displayMetrics.widthPixels - dp(PANEL_INSET_DP * 2))
    val required = ordered.sumOf { maxOf(it.minWidth,
        (it.paint.measureText(it.text.toString()) + it.paddingLeft + it.paddingRight).toInt()) } + dp(PANEL_GAP_DP) * (ordered.size - 1)
    val stacked = required > dialogWidth - dp(PANEL_INSET_DP * 2)
    bar.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    if (stacked) for (i in 0 until bar.childCount) {
        val child = bar.getChildAt(i)
        if (child !is Button) child.visibility = View.GONE
    }
    ordered.forEachIndexed { index, button ->
        (button.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.weight = 0f
            params.marginStart = if (!stacked && index > 0) dp(PANEL_GAP_DP) else 0
            params.topMargin = if (stacked && index > 0) dp(PANEL_GAP_DP) else 0
            button.layoutParams = params
        }
    }
}

private const val PANEL_INSET_DP = 24
private const val PANEL_GAP_DP = 12
private const val PANEL_WIDTH_DP = 640
private const val PANEL_CONTROL_HEIGHT_DP = 60
private const val PANEL_CONTROL_MIN_WIDTH_DP = 104
private const val PANEL_ACTION_BOTTOM_INSET_DP = 16

/** A real constrained viewport; its child retains its full height so overflowing content scrolls. */
internal class AppDialogScrollView(context: Context) : androidx.core.widget.NestedScrollView(context) {
    init {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val frame = android.graphics.Rect()
        getWindowVisibleDisplayFrame(frame)
        val screen = resources.displayMetrics.heightPixels
        val available = if (frame.height() > 0) minOf(screen, frame.height()) else screen
        val parentLimit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
            Int.MAX_VALUE else View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = minOf(parentLimit, (available * 0.85f).toInt()).coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
    }
}
