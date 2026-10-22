// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.widget.LinearLayout

/** Dispatch selected cards directly, without walking every card for each selection. */
internal class SettingsSectionRenderer(
    private val builders: Map<SettingsSection, (LinearLayout) -> Unit>,
) {
    init {
        require(builders.keys == SettingsSection.entries.toSet()) { "Every settings section needs a builder" }
    }

    fun render(content: LinearLayout, sections: Set<SettingsSection>, p7: Boolean) {
        for (section in sections) {
            if (p7 && section in SettingsInformationArchitecture.P7_UNAVAILABLE_SECTIONS) continue
            if (!p7 && section in setOf(SettingsSection.P7_CARPLAY_DATA, SettingsSection.INSTRUMENT_MAP)) continue
            builders.getValue(section)(content)
        }
    }
}
