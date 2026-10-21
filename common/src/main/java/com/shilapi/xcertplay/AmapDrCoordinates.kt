package com.shilapi.xcertplay

import kotlin.math.*

/** Experimental inverse of the mainland GCJ-02 offset used by AMap matched coordinates. */
internal object AmapDrCoordinates {
    fun toWgs84(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271) return latitude to longitude
        var lat = latitude
        var lon = longitude
        repeat(6) {
            val (dLat, dLon) = offset(lat, lon)
            lat -= lat + dLat - latitude
            lon -= lon + dLon - longitude
        }
        return lat to lon
    }
    private fun offset(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon - 105.0
        val y = lat - 35.0
        var dLat = -100.0 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*sqrt(abs(x))
        var dLon = 300.0 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*sqrt(abs(x))
        val wave = (20*sin(6*x*PI) + 20*sin(2*x*PI))*2/3
        dLat += wave + (20*sin(y*PI) + 40*sin(y*PI/3))*2/3 +
            (160*sin(y*PI/12) + 320*sin(y*PI/30))*2/3
        dLon += wave + (20*sin(x*PI) + 40*sin(x*PI/3))*2/3 +
            (150*sin(x*PI/12) + 300*sin(x*PI/30))*2/3
        val rad = lat*PI/180
        val magic = 1 - 0.00669342162296594323*sin(rad).pow(2)
        val root = sqrt(magic)
        return (dLat*180 / ((6378245.0*(1-0.00669342162296594323)/(magic*root))*PI)) to
            (dLon*180 / (6378245.0/root*cos(rad)*PI))
    }
}
