package com.shilapi.xcertplay

import android.view.View
import com.shilapi.xcertplay.host.R

/** Select by the control's title, independent of its full accessibility announcement. */
internal val View.settingsControlTitle: CharSequence?
    get() = getTag(R.id.settings_control_title) as? CharSequence ?: contentDescription

/** Interact with custom forms through their real action buttons. */
internal fun android.app.Dialog.getButton(which: Int): android.widget.Button {
    if (this is android.app.AlertDialog) return getButton(which)
    val labels = when (which) {
        android.content.DialogInterface.BUTTON_POSITIVE -> listOf(R.string.save, R.string.save_details)
        android.content.DialogInterface.BUTTON_NEGATIVE -> listOf(R.string.cancel)
        else -> error("Unsupported action $which")
    }.map(context::getString)
    fun find(view: View): android.widget.Button? {
        if (view is android.widget.Button && view.text.toString() in labels) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    return requireNotNull(find(window!!.decorView)) { "Missing action: $labels" }
}
