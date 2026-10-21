package com.shilapi.xcertplay

internal enum class CarPlayLocationMode(val usesDr: Boolean = false) {
    SYSTEM, GPS_FIRST(true), DR_FIRST(true), GPS_ONLY, DR_ONLY(true)
}
