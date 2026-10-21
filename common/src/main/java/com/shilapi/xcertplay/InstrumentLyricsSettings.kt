package com.shilapi.xcertplay

import android.content.Context

/** Independent from CarPlay transport settings: all changes apply to instrument output only. */
internal object InstrumentLyricsSettings {
    fun prefs(context: Context) = context.getSharedPreferences("xplay-instrument-lyrics", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun autoStart(c: Context) = prefs(c).getBoolean("auto", true)
    fun online(c: Context) = prefs(c).getBoolean("online", true)
    fun lyrics(c: Context) = prefs(c).getBoolean("lyrics", true)
    fun cover(c: Context) = prefs(c).getBoolean("cover", false)
    fun hideLane(c: Context) = prefs(c).getBoolean("lane", false)
    fun delay(c: Context) = prefs(c).getInt("delay_ms", 0).coerceIn(0, 5000)
    fun saveDelay(c: Context, value: Int) {
        prefs(c).edit().putInt("delay_ms", value.coerceIn(0, 5000)).apply()
        InstrumentLyrics.settingsChanged(c)
    }
    fun font(c: Context) = prefs(c).getInt("font", 100).coerceIn(80, 140)
    fun save(c: Context, key: String, value: Boolean) {
        require(key in setOf("enabled", "auto", "online", "lyrics", "cover", "lane"))
        prefs(c).edit().putBoolean(key, value).apply()
        InstrumentLyrics.settingsChanged(c)
    }
    fun saveFont(c: Context, value: Int) {
        prefs(c).edit().putInt("font", value.coerceIn(80, 140)).apply()
        InstrumentLyrics.settingsChanged(c)
    }
}
