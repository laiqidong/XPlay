// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

internal enum class SettingsCategory {
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, CONTROLS, VEHICLE, DIAGNOSTICS, LYRICS, INSTRUMENT, VEHICLE_CONTROL, ABOUT,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    CLIMATE_OVERLAY,
    FOG_OVERLAY,
    P7_CARPLAY_DATA,
    INSTRUMENT_MAP,
    FLOATING_LYRICS,
    LYRICS_SHARED,
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    CONNECTION_SETUP,
    AUTHENTICATION,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    USB_CONNECTION,
    BYD_ADB,
    DISPLAY_AND_PERFORMANCE,
    DISPLAY_APPEARANCE,
    DISPLAY_SCREEN,
    EXPERIMENTAL_DISPLAY,
    ADVANCED_MEDIA,
    ADVANCED_VIDEO,
    AMBIENT_LIGHTING,
    CAR_BUTTON,
    AUDIO_ROUTING,
    NAVIGATION_WHEEL,
    LOCATION,
    CLUSTER_MAP,
    BYD_NAVIGATION,
    PERMISSIONS_AND_HELP,
    BACKUP,
}

internal object SettingsInformationArchitecture {
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.OVERVIEW to emptySet(),
        SettingsCategory.CONNECTION to setOf(SettingsSection.CONNECTION_SETUP,
            SettingsSection.AUTOMATIC_CONNECTION, SettingsSection.USB_CONNECTION, SettingsSection.BYD_ADB),
        SettingsCategory.DISPLAY to setOf( SettingsSection.DISPLAY_APPEARANCE, SettingsSection.DISPLAY_SCREEN,
            SettingsSection.DISPLAY_AND_PERFORMANCE, SettingsSection.EXPERIMENTAL_DISPLAY, SettingsSection.ADVANCED_VIDEO),
        SettingsCategory.AUDIO to setOf(SettingsSection.AUDIO_ROUTING, SettingsSection.NAVIGATION_WHEEL,
            SettingsSection.ADVANCED_MEDIA),
        SettingsCategory.NAVIGATION to setOf(SettingsSection.LOCATION, SettingsSection.P7_CARPLAY_DATA, SettingsSection.BYD_NAVIGATION,
            SettingsSection.CLUSTER_MAP),
        SettingsCategory.CONTROLS to setOf(SettingsSection.CARPLAY_CONTROLS, SettingsSection.WHEEL_KEYS,
            SettingsSection.CAR_BUTTON),
        SettingsCategory.VEHICLE to setOf(SettingsSection.AMBIENT_LIGHTING),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS, SettingsSection.AUTHENTICATION,
            SettingsSection.PERMISSIONS_AND_HELP, SettingsSection.BACKUP),
        SettingsCategory.LYRICS to setOf(SettingsSection.FLOATING_LYRICS, SettingsSection.LYRICS_SHARED),
        SettingsCategory.INSTRUMENT to setOf(SettingsSection.INSTRUMENT_MAP),
        SettingsCategory.VEHICLE_CONTROL to setOf(SettingsSection.CLIMATE_OVERLAY, SettingsSection.FOG_OVERLAY),
        SettingsCategory.ABOUT to emptySet(),
    )

    val P7_UNAVAILABLE_SECTIONS = setOf(SettingsSection.CLUSTER_MAP, SettingsSection.AMBIENT_LIGHTING,
        SettingsSection.BYD_NAVIGATION)

    fun categories(@Suppress("UNUSED_PARAMETER") p7: Boolean): List<SettingsCategory> =
        SettingsCategory.entries.filter { it !in setOf(SettingsCategory.OVERVIEW, SettingsCategory.VEHICLE) }

    fun category(category: SettingsCategory, @Suppress("UNUSED_PARAMETER") p7: Boolean): SettingsCategory =
        when (category) {
            SettingsCategory.OVERVIEW -> SettingsCategory.CONNECTION
            SettingsCategory.VEHICLE -> SettingsCategory.NAVIGATION
            else -> category
        }

    fun sections(category: SettingsCategory, p7: Boolean): Set<SettingsSection> =
        sectionsByCategory.getValue(category).let { sections ->
            if (p7) sections - P7_UNAVAILABLE_SECTIONS
            else sections
        }

}

