package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection

/** XPlay must never offer an upstream DiPlay APK that would replace its customization. */
internal object UpdateReleaseLookup {
    @Suppress("UNUSED_PARAMETER")
    fun latest(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateRelease? = null
}
