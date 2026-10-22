// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context

/** P7 instrument projection is exclusively the independent CarPlay map channel. */
internal object InstrumentMapSettings {
    // Reuse the existing store and migration to preserve the driver's saved map settings.
    private fun prefs(c: Context) = InstrumentLyricsSettings.prefs(c)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun autoStart(c: Context) = prefs(c).getBoolean("auto", true)
    fun hideLane(c: Context) = prefs(c).getBoolean("lane", false)
    fun save(c: Context, key: String, value: Boolean) {
        require(key in setOf("enabled", "auto", "lane"))
        prefs(c).edit().putBoolean(key, value).apply()
        InstrumentLyrics.mapSettingsChanged(c)
    }
}
