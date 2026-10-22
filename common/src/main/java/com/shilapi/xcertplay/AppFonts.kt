package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.shilapi.xcertplay.host.R

/** Shared faces for native controls, Canvas overlays and lyrics; loaded once per process. */
internal object AppFonts {
    private val faces = mutableMapOf<Int, Typeface>()
    @Synchronized private fun face(context: Context, resource: Int): Typeface = faces.getOrPut(resource) {
        requireNotNull(ResourcesCompat.getFont(context.applicationContext, resource))
    }
    fun regular(context: Context) = face(context, R.font.pingfang_regular)
    fun medium(context: Context) = face(context, R.font.pingfang_medium)
    fun semibold(context: Context) = face(context, R.font.pingfang_semibold)
}
