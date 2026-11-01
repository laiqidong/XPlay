package com.shilapi.xcertplay

import android.content.Context

/** Latest transport stages only: no callback URIs, tokens, OEM payloads or exception messages. */
internal object P7ClimateDiagnostics {
    private const val PREFS = "p7_climate_diagnostics"
    @Synchronized fun note(context: Context, key: String, value: String) {
        val safe = value.take(160).replace(Regex("[^A-Za-z0-9_:.${'$'}-]"), "?")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(key, null) != safe) prefs.edit().putString(key, safe)
            .putLong("updatedAt", System.currentTimeMillis()).apply()
    }
    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return "P7 smart passenger: " + listOf("transport", "write", "callback_provider", "callback_binder", "callback", "readback").joinToString(" ") {
            "$it=${prefs.getString(it, "not-observed")}"
        } + " updatedAtEpochMs=${prefs.getLong("updatedAt", 0)}; acknowledged is not physical confirmation"
    }
}
