package com.shilapi.xcertplay

import android.graphics.Color

/** Semantic colors for DiPlay-owned chrome. Media canvases and cluster-specific palettes stay separate. */
internal data class DiPlayPalette(
    val background: Int,
    val surface: Int,
    val button: Int,
    val outline: Int,
    val railSelected: Int,
    val railSelectedOutline: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val warning: Int,
    val success: Int,
    val danger: Int,
    val ripple: Int,
    val focusRing: Int,
    val systemBar: Int,
    val systemBarIconsAreDark: Boolean,
    val overlayBackground: Int,
    val overlayPrimaryText: Int,
    val overlaySecondaryText: Int,
    val overlayAccent: Int,
    val overlayOnAccent: Int,
    val overlayAccentTrack: Int,
    val overlayTrackOff: Int,
    val overlayDanger: Int,
) {
    companion object {
        fun of(night: Boolean): DiPlayPalette = if (night) DARK else LIGHT

        /** Shared macOS-style appearance for every app page. */
        fun settings(night: Boolean): DiPlayPalette = of(night)

        val DARK = DiPlayPalette(
            background = Color.rgb(41, 38, 38),
            surface = Color.rgb(46, 45, 45),
            button = Color.rgb(62, 61, 61),
            outline = Color.rgb(55, 54, 54),
            railSelected = Color.rgb(0, 122, 255),
            railSelectedOutline = Color.TRANSPARENT,
            primaryText = Color.rgb(222, 222, 222),
            secondaryText = Color.rgb(158, 158, 158),
            accent = Color.rgb(0, 145, 255),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(0, 122, 255),
            trackOff = Color.rgb(99, 99, 102),
            warning = Color.rgb(255, 196, 128),
            success = Color.rgb(127, 205, 154),
            danger = Color.rgb(220, 69, 69),
            ripple = 0x336F9FD9,
            focusRing = Color.rgb(0, 122, 255),
            systemBar = Color.rgb(41, 38, 38),
            systemBarIconsAreDark = false,
            overlayBackground = Color.rgb(41, 38, 38),
            overlayPrimaryText = Color.rgb(222, 222, 222),
            overlaySecondaryText = Color.rgb(158, 158, 158),
            overlayAccent = Color.rgb(0, 145, 255),
            overlayOnAccent = Color.WHITE,
            overlayAccentTrack = Color.rgb(0, 122, 255),
            overlayTrackOff = Color.rgb(99, 99, 102),
            overlayDanger = Color.rgb(220, 69, 69),
        )

        val LIGHT = DiPlayPalette(
            background = Color.WHITE,
            surface = Color.rgb(247, 247, 247),
            button = Color.rgb(229, 229, 229),
            outline = Color.rgb(233, 233, 233),
            railSelected = Color.rgb(0, 112, 245),
            railSelectedOutline = Color.TRANSPARENT,
            primaryText = Color.rgb(39, 39, 39),
            secondaryText = Color.rgb(118, 118, 118),
            accent = Color.rgb(0, 107, 234),
            onAccent = Color.WHITE,
            accentTrack = Color.rgb(0, 104, 223),
            trackOff = Color.rgb(199, 199, 204),
            warning = Color.rgb(122, 69, 0),
            success = Color.rgb(40, 107, 63),
            danger = Color.rgb(155, 48, 48),
            ripple = 0x33245A9C,
            focusRing = Color.rgb(0, 104, 223),
            systemBar = Color.WHITE,
            systemBarIconsAreDark = true,
            overlayBackground = Color.WHITE,
            overlayPrimaryText = Color.rgb(39, 39, 39),
            overlaySecondaryText = Color.rgb(118, 118, 118),
            overlayAccent = Color.rgb(0, 107, 234),
            overlayOnAccent = Color.WHITE,
            overlayAccentTrack = Color.rgb(0, 104, 223),
            overlayTrackOff = Color.rgb(199, 199, 204),
            overlayDanger = Color.rgb(155, 48, 48),
        )
    }
}
