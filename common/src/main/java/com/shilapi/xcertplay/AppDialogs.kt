package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
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
internal fun Context.appDialog(): Dialog = Dialog(this, appDialogTheme())

/** Keeps the existing callback contract while owning every visible part of the dialog. */
internal class AppDialogBuilder(private val owner: Context) {
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var content: View? = null
    private var items: Array<out CharSequence>? = null
    private var selected = -1
    private var checked: BooleanArray? = null
    private var itemClick: DialogInterface.OnClickListener? = null
    private var multiClick: DialogInterface.OnMultiChoiceClickListener? = null
    private val actions = linkedMapOf<Int, Pair<CharSequence, DialogInterface.OnClickListener?>>()
    private var cancelable = true
    private var onCancel: DialogInterface.OnCancelListener? = null
    private var onDismiss: DialogInterface.OnDismissListener? = null
    fun setTitle(value: CharSequence) = apply { title = value }
    fun setTitle(value: Int) = setTitle(owner.getText(value))
    fun setMessage(value: CharSequence) = apply { message = value }
    fun setMessage(value: Int) = setMessage(owner.getText(value))
    fun setView(value: View) = apply { content = value }
    fun setCancelable(value: Boolean) = apply { cancelable = value }
    fun setOnDismissListener(value: DialogInterface.OnDismissListener?) = apply { onDismiss = value }
    fun setOnCancelListener(value: DialogInterface.OnCancelListener?) = apply { onCancel = value }
    fun setPositiveButton(text: CharSequence, click: DialogInterface.OnClickListener?) = apply { actions[DialogInterface.BUTTON_POSITIVE] = text to click }
    fun setNegativeButton(text: CharSequence, click: DialogInterface.OnClickListener?) = apply { actions[DialogInterface.BUTTON_NEGATIVE] = text to click }
    fun setNeutralButton(text: CharSequence, click: DialogInterface.OnClickListener?) = apply { actions[DialogInterface.BUTTON_NEUTRAL] = text to click }
    fun setPositiveButton(text: Int, click: DialogInterface.OnClickListener?) = setPositiveButton(owner.getText(text), click)
    fun setNegativeButton(text: Int, click: DialogInterface.OnClickListener?) = setNegativeButton(owner.getText(text), click)
    fun setNeutralButton(text: Int, click: DialogInterface.OnClickListener?) = setNeutralButton(owner.getText(text), click)
    fun setItems(values: Array<out CharSequence>, click: DialogInterface.OnClickListener?) = apply { items = values; itemClick = click }
    fun setSingleChoiceItems(values: Array<out CharSequence>, index: Int, click: DialogInterface.OnClickListener?) = apply {
        items = values; selected = index; itemClick = click; singleChoice = true
    }
    private var singleChoice = false
    fun setMultiChoiceItems(values: Array<out CharSequence>, checks: BooleanArray, click: DialogInterface.OnMultiChoiceClickListener?) = apply {
        items = values; checked = checks; multiClick = click
    }
    fun create(): AlertDialog = AppPanelDialog(owner, title, message, content, items, singleChoice, selected,
        checked, itemClick, multiClick, actions.toMap()).apply {
        setCancelable(cancelable)
        setCanceledOnTouchOutside(cancelable)
        setOnCancelListener(onCancel)
        setOnDismissListener(onDismiss)
    }
    fun show(): AlertDialog = create().apply { show() }
}

private const val PANEL_INSET_DP = 24
private const val PANEL_GAP_DP = 12
private const val PANEL_RADIUS_DP = 16
private const val PANEL_WIDTH_DP = 640
private const val PANEL_CONTROL_HEIGHT_DP = 60

private class AppPanelDialog(
    owner: Context,
    private val heading: CharSequence?,
    private val body: CharSequence?,
    private val custom: View?,
    private val entries: Array<out CharSequence>?,
    private val single: Boolean,
    private val selected: Int,
    private val checked: BooleanArray?,
    private val itemClick: DialogInterface.OnClickListener?,
    private val multiClick: DialogInterface.OnMultiChoiceClickListener?,
    private val actions: Map<Int, Pair<CharSequence, DialogInterface.OnClickListener?>>,
) : AlertDialog(owner, owner.appDialogTheme()) {
    private val colors = DiPlayPalette.of(owner.dialogNight())
    private val buttons = mutableMapOf<Int, Button>()
    private var choices: ListView? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun text(value: CharSequence, size: Float, muted: Boolean = false) = TextView(context).apply {
        text = value; textSize = size
        setTextColor(if (muted) colors.secondaryText else colors.primaryText)
    }
    override fun getButton(whichButton: Int): Button? = buttons[whichButton]
    override fun getListView(): ListView? = choices
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PANEL_INSET_DP), dp(PANEL_INSET_DP), dp(PANEL_INSET_DP), dp(PANEL_INSET_DP))
            background = panelBackground()
        }
        heading?.let { panel.addView(text(it, 24f).apply { setTypeface(typeface, Typeface.BOLD) }) }
        val contents = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body?.let { contents.addView(text(it, 17f, true).apply { setPadding(0, dp(PANEL_GAP_DP), 0, dp(PANEL_GAP_DP)) }) }
        custom?.let { supplied ->
            // Callers historically wrapped forms/reports in a ScrollView. Keep a single scroller.
            var content = supplied
            while ((content is ScrollView || content is androidx.core.widget.NestedScrollView) &&
                (content as ViewGroup).childCount == 1) {
                val wrapper = content as ViewGroup
                content = wrapper.getChildAt(0)
                wrapper.removeView(content)
            }
            contents.addView(content, LinearLayout.LayoutParams(-1, -2))
        }
        if (entries != null) {
            choices = ListView(context).apply {
                divider = null
                isNestedScrollingEnabled = true
                choiceMode = when { checked != null -> ListView.CHOICE_MODE_MULTIPLE; single -> ListView.CHOICE_MODE_SINGLE; else -> ListView.CHOICE_MODE_NONE }
                adapter = object : ArrayAdapter<CharSequence>(context, 0, entries) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        return CheckedTextView(context).apply {
                            text = entries[position]; textSize = 18f
                            setTextColor(colors.primaryText)
                            gravity = Gravity.CENTER_VERTICAL
                            minHeight = dp(PANEL_CONTROL_HEIGHT_DP)
                            setPadding(dp(PANEL_GAP_DP), dp(PANEL_GAP_DP), dp(PANEL_GAP_DP), dp(PANEL_GAP_DP))
                            if (single || checked != null) {
                                checkMarkDrawable = if (checked != null) android.graphics.drawable.StateListDrawable().apply {
                                    addState(intArrayOf(android.R.attr.state_checked), context.getDrawable(android.R.drawable.checkbox_on_background))
                                    addState(intArrayOf(), context.getDrawable(android.R.drawable.checkbox_off_background))
                                } else context.getDrawable(android.R.drawable.btn_radio)
                                checkMarkTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(colors.accent, colors.secondaryText))
                            }
                        }
                    }
                }
                if (single && selected >= 0) setItemChecked(selected, true)
                checked?.forEachIndexed { index, value -> setItemChecked(index, value) }
                setOnItemClickListener { _, _, index, _ ->
                    if (checked != null) {
                        checked[index] = isItemChecked(index)
                        multiClick?.onClick(this@AppPanelDialog, index, checked[index])
                    } else {
                        itemClick?.onClick(this@AppPanelDialog, index)
                        if (!single) dismiss()
                    }
                }
            }
            panel.addView(choices, LinearLayout.LayoutParams(-1,
                minOf(entries.size * dp(PANEL_CONTROL_HEIGHT_DP + PANEL_GAP_DP),
                    (context.resources.displayMetrics.heightPixels * 0.45f).toInt()).coerceAtLeast(dp(PANEL_CONTROL_HEIGHT_DP))).apply { topMargin = dp(PANEL_GAP_DP) })
        } else {
            panel.addView(contents, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PANEL_GAP_DP) })
        }
        fun actionControl(id: Int): Button? = actions[id]?.let { (caption, listener) ->
            DiPlayButton(context).apply {
                this.id = when (id) {
                    DialogInterface.BUTTON_POSITIVE -> android.R.id.button1
                    DialogInterface.BUTTON_NEGATIVE -> android.R.id.button2
                    else -> android.R.id.button3
                }
                text = caption; textSize = 18f; minHeight = dp(PANEL_CONTROL_HEIGHT_DP)
                style(if (id == DialogInterface.BUTTON_POSITIVE) DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY, colors, false)
                setOnClickListener { listener?.onClick(this@AppPanelDialog, id); dismiss() }
                buttons[id] = this
            }
        }
        // Keep an optional third action separate; cancel and confirm share one row everywhere.
        actionControl(DialogInterface.BUTTON_NEUTRAL)?.let { control ->
            panel.addView(control, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PANEL_GAP_DP) })
        }
        val confirmActions = listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE)
            .mapNotNull(::actionControl)
        if (confirmActions.isNotEmpty()) {
            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            confirmActions.forEachIndexed { index, control ->
                actionRow.addView(control, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (index > 0) marginStart = dp(PANEL_GAP_DP)
                })
            }
            panel.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PANEL_GAP_DP) })
        }
        // Scroll the whole panel, including actions: keyboard/large text must never hide a button.
        window?.setContentView(AppDialogScrollView(context).apply { addView(panel) })
        window?.apply {
            setBackgroundDrawable(panelBackground())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    override fun show() {
        super.show()
        val metrics = context.resources.displayMetrics
        window?.setLayout(minOf(dp(PANEL_WIDTH_DP), metrics.widthPixels - dp(PANEL_INSET_DP * 2)).coerceAtLeast(1),
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun panelBackground() = GradientDrawable().apply {
        setColor(this@AppPanelDialog.colors.surface); cornerRadius = dp(PANEL_RADIUS_DP).toFloat()
    }
}

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
