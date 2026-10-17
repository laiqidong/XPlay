package com.shilapi.xcertplay.location

import kotlin.math.abs

/** GGA measurements from the receiver, never estimates derived from Android accuracy. */
internal data class GpsNmeaMetadata(
    val valid: Boolean,
    val utcMillisOfDay: Long,
    val receivedAtMillis: Long,
    val satellitesUsed: Int?,
    val hdop: Double?,
    val altitudeMslMeters: Double?,
    val geoidSeparationMeters: Double?,
) {
    fun matches(fixTimeMillis: Long, nowMillis: Long): Boolean {
        if (!valid || nowMillis - receivedAtMillis !in 0..3_000 || fixTimeMillis <= 0) return false
        val difference = abs(fixTimeMillis % DAY_MILLIS - utcMillisOfDay)
        return minOf(difference, DAY_MILLIS - difference) <= 1_000
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        fun parse(sentence: String, receivedAtMillis: Long): GpsNmeaMetadata? = runCatching {
            val text = sentence.trim()
            if (!text.startsWith("$")) return null
            val body = text.substring(1).substringBefore('*')
            val checksum = text.substringAfter('*', "").takeIf { it.length == 2 }?.toIntOrNull(16) ?: return null
            if (body.fold(0) { value, char -> value xor char.code } != checksum) return null
            val fields = body.split(',')
            if (fields.size != 15 || fields[0] !in setOf("GPGGA", "GNGGA")) return null
            val quality = fields[6].toIntOrNull()?.takeIf { it in 0..8 } ?: return null
            // A no-fix GGA is meaningful even before the receiver knows UTC.
            val time = fields[1]
            val utc = if (time.isEmpty() && quality == 0) 0L else {
                if (!Regex("""\d{6}(\.\d+)?""").matches(time)) return null
                val hour = time.substring(0, 2).toInt().takeIf { it in 0..23 } ?: return null
                val minute = time.substring(2, 4).toInt().takeIf { it in 0..59 } ?: return null
                val second = time.substring(4).toDouble().takeIf { it in 0.0..<60.0 } ?: return null
                hour * 3_600_000L + minute * 60_000L + (second * 1_000).toLong()
            }
            fun number(index: Int) = fields[index].toDoubleOrNull()?.takeIf { it.isFinite() }
            GpsNmeaMetadata(quality in setOf(1, 2, 4, 5), utc, receivedAtMillis,
                fields[7].toIntOrNull()?.takeIf { it in 0..99 },
                number(8)?.takeIf { it > 0 },
                number(9)?.takeIf { fields[10] == "M" },
                number(11)?.takeIf { fields[12] == "M" })
        }.getOrNull()
    }
}
