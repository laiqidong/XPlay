package com.shilapi.xcertplay

import android.content.Context

/** Independent output preferences. Requesting a new map stream needs the next CarPlay connection. */
internal object InstrumentLyricsSettings {
    fun prefs(context: Context): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences("xplay-instrument-lyrics", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("floating_layout_v2", false)) {
            val editor = prefs.edit().putBoolean("floating_layout_v2", true).putBoolean("floating_next", true)
                .putBoolean("floating_locked", true)
            if (!prefs.contains("floating_y") || prefs.getInt("floating_y", 80) == 80) editor.putInt("floating_y", 0)
            editor.apply()
        }
        return prefs
    }
    fun map(c: Context) = prefs(c).getBoolean("map", false)
    fun floating(c: Context) = prefs(c).getBoolean("floating", false)
    fun floatingNext(c: Context) = prefs(c).getBoolean("floating_next", true)
    fun floatingAnimation(c: Context) = prefs(c).getBoolean("floating_animation", true)
    fun floatingLocked(c: Context) = prefs(c).getBoolean("floating_locked", true)
    fun floatingValue(c: Context, key: String): Int = prefs(c).getInt("floating_$key",
        when (key) { "x" -> 50; "y" -> 0; "font" -> 100; else -> 85 })
    fun saveFloating(c: Context, key: String, value: Int) {
        require(key in setOf("x", "y", "font", "opacity"))
        prefs(c).edit().putInt("floating_$key", value.coerceIn(if (key == "font") 80..140 else 0..100)).apply()
        InstrumentLyrics.floatingStyleChanged()
    }
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
        require(key in setOf("enabled", "auto", "online", "lyrics", "cover", "lane", "map", "floating", "floating_next", "floating_locked", "floating_animation"))
        prefs(c).edit().putBoolean(key, value).apply()
        if (key in setOf("floating_next", "floating_locked", "floating_animation")) InstrumentLyrics.floatingStyleChanged()
        else InstrumentLyrics.settingsChanged(c)
    }
    fun saveFont(c: Context, value: Int) {
        prefs(c).edit().putInt("font", value.coerceIn(80, 140)).apply()
        InstrumentLyrics.settingsChanged(c)
    }
}
