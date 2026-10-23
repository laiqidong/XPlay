// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** App language defaults to the head unit; an explicit choice only changes XPlay UI. */
object AppLocale {
    const val SYSTEM = "system"
    val languages = listOf(SYSTEM, "en", "zh-CN", "zh-TW", "ja", "es", "ru", "uk", "ar")

    fun preference(context: Context): String = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        .getString("app_language", SYSTEM)?.takeIf { it in languages } ?: SYSTEM

    /** True when Android 13+ owns the resulting configuration update. */
    fun save(context: Context, language: String): Boolean {
        require(language in languages)
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().putString("app_language", language).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = if (language == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
            val manager = context.getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales != locales) {
                manager.applicationLocales = locales
                return true
            }
        }
        return false
    }

    private fun locales(context: Context): LocaleList {
        val language = preference(context)
        return if (language == SYSTEM) Resources.getSystem().configuration.locales
            else LocaleList(Locale.forLanguageTag(language))
    }

    fun wrap(context: Context): Context {
        val locales = locales(context)
        if (context.resources.configuration.locales == locales) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(locales)
            if (!locales.isEmpty) setLayoutDirection(locales[0])
        })
    }

    /** Retain window/density configuration while applying the selected UI language. */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        val locales = locales(context)
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
