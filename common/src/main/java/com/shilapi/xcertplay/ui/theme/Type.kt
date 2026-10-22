package com.shilapi.xcertplay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.shilapi.xcertplay.host.R

private val PingFang = FontFamily(
    Font(R.font.pingfang_regular, FontWeight.Normal),
    Font(R.font.pingfang_medium, FontWeight.Medium),
    Font(R.font.pingfang_semibold, FontWeight.SemiBold),
    Font(R.font.pingfang_semibold, FontWeight.Bold),
)
private val defaults = Typography()
val Typography = Typography(
    displayLarge = defaults.displayLarge.copy(fontFamily = PingFang),
    displayMedium = defaults.displayMedium.copy(fontFamily = PingFang),
    displaySmall = defaults.displaySmall.copy(fontFamily = PingFang),
    headlineLarge = defaults.headlineLarge.copy(fontFamily = PingFang),
    headlineMedium = defaults.headlineMedium.copy(fontFamily = PingFang),
    headlineSmall = defaults.headlineSmall.copy(fontFamily = PingFang),
    titleLarge = defaults.titleLarge.copy(fontFamily = PingFang),
    titleMedium = defaults.titleMedium.copy(fontFamily = PingFang),
    titleSmall = defaults.titleSmall.copy(fontFamily = PingFang),
    bodyLarge = defaults.bodyLarge.copy(fontFamily = PingFang),
    bodyMedium = defaults.bodyMedium.copy(fontFamily = PingFang),
    bodySmall = defaults.bodySmall.copy(fontFamily = PingFang),
    labelLarge = defaults.labelLarge.copy(fontFamily = PingFang),
    labelMedium = defaults.labelMedium.copy(fontFamily = PingFang),
    labelSmall = defaults.labelSmall.copy(fontFamily = PingFang),
)
