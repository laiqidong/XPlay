// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

/** XPlay follows the head unit language; retire overrides persisted by older versions. */
object AppLocale {
    const val SYSTEM = "system"

    @Suppress("UNUSED_PARAMETER")
    fun preference(context: Context): String = SYSTEM

    fun wrap(context: Context): Context {
        val prefs = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        if (prefs.contains("app_language") || prefs.contains("app_language_platform_migrated")) {
            prefs.edit().remove("app_language").remove("app_language_platform_migrated").apply()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            if (!manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.getEmptyLocaleList()
        }
        return context
    }

    /** Restore system locales while retaining the current window/density configuration. */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        val locales = Resources.getSystem().configuration.locales
        val resources = context.resources
        if (resources.configuration.locales == locales) return false
        val configuration = Configuration(resources.configuration).apply {
            setLocales(locales)
            if (!locales.isEmpty) setLayoutDirection(locales[0])
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return true
    }
}
