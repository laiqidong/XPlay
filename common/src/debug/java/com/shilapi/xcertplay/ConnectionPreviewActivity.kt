package com.shilapi.xcertplay

import android.content.Intent
import android.app.Activity
import android.os.Bundle

/** Debug-only preview of the production connection view; no authentication or network session. */
class ConnectionPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = android.content.res.Configuration(resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(intent.getStringExtra("locale") ?: "zh-CN"))
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        val night = intent.getBooleanExtra("night", true)
        val palette = DiPlayPalette.settings(night)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        setContentView(ConnectionWaitingView(this) {
            DiPlayActivity.explicitSettingsEntryPending = true
            startActivity(Intent(this, DiPlayActivity::class.java)
                .putExtra("page", "settings")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.apply {
            applyPalette(night)
            stage.setText(com.shilapi.xcertplay.host.R.string.connecting_to_your_iphone)
        })
    }
}
