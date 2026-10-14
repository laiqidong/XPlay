package com.shilapi.xcertplay

/** The screen before CarPlay's video shares the app appearance used by Home and Settings. */
internal data class WaitingScreenColors(val background: Int, val text: Int, val secondary: Int) {
    companion object {
        fun of(night: Boolean): WaitingScreenColors = DiPlayPalette.settings(night).let {
            WaitingScreenColors(it.background, it.primaryText, it.secondaryText)
        }
    }
}
