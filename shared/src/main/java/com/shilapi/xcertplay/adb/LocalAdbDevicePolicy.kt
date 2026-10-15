package com.shilapi.xcertplay.adb

import android.os.Build

/** Keep the P7 engineering-mode endpoint separate from other head units. */
object LocalAdbDevicePolicy {
    fun isXpengP7(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND,
        model: String = Build.MODEL): Boolean =
        manufacturer.equals("XiaoPeng", ignoreCase = true) &&
            brand.equals("XPENG", ignoreCase = true) && model.equals("E28", ignoreCase = true)

    fun port(): Int = if (isXpengP7()) 5050 else 5555
}
