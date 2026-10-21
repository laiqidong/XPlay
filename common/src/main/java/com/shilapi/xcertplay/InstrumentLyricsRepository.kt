package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

internal data class InstrumentSong(val title: String, val artist: String, val album: String, val duration: Long) {
    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$title\u0000$artist\u0000$album\u0000$duration".toByteArray())
        .joinToString("") { "%02x".format(it) }
}
internal data class InstrumentLyricLine(val time: Long, val text: String)
internal data class InstrumentLyricResult(val lines: List<InstrumentLyricLine>, val status: String)

/** Bounded HTTPS lookup. No Apple credentials, audio, location or device identifiers are sent. */
internal class InstrumentLyricsRepository(context: Context) {
    private val directory = File(context.cacheDir, "instrument-lyrics")
    @Volatile var generation = 0L
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() { generation++; connection?.disconnect() }
    fun bytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L
    @Synchronized fun clear() { cancel(); directory.listFiles()?.forEach { it.delete() } }

    fun find(song: InstrumentSong, online: Boolean, ticket: Long): InstrumentLyricResult {
        val file = File(directory, song.key + ".lrc")
        if (file.isFile && file.length() <= MAX_BYTES) {
            val lines = parse(file.readText())
            if (lines.isNotEmpty()) {
                file.setLastModified(System.currentTimeMillis())
                return InstrumentLyricResult(lines, "cached")
            }
        }
        if (!online) return InstrumentLyricResult(emptyList(), "offline")
        if (song.duration <= 0) return InstrumentLyricResult(emptyList(), "waiting_duration")
        fun query(path: String, fields: Map<String, String>): String? {
            if (ticket != generation) return null
            val query = fields.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val conn = URL("https://lrclib.net/api/$path?$query").openConnection() as HttpURLConnection
            connection = conn
            try {
                conn.connectTimeout = 6000; conn.readTimeout = 6000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "XPlay-InstrumentLyrics/1.0")
                val code = conn.responseCode
                if (code == 404) return null
                check(code == 200)
                return conn.inputStream.use { input ->
                    val deadline = android.os.SystemClock.elapsedRealtime() + 12000
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        check(ticket == generation && android.os.SystemClock.elapsedRealtime() < deadline)
                        val count = input.read(chunk)
                        if (count < 0) break
                        check(buffer.size() + count <= MAX_BYTES)
                        buffer.write(chunk, 0, count)
                    }
                    val bytes = buffer.toByteArray()
                    check(bytes.size <= MAX_BYTES)
                    bytes.toString(Charsets.UTF_8)
                }
            } finally { conn.disconnect(); if (connection === conn) connection = null }
        }
        return try {
            val exact = query("get", mapOf("track_name" to song.title, "artist_name" to song.artist,
                "album_name" to song.album, "duration" to (song.duration / 1000.0).toString()))
                ?.let(::JSONObject)
            fun suitable(item: JSONObject): Boolean =
                normalized(item.optString("trackName")) == normalized(song.title) &&
                normalized(item.optString("artistName")) == normalized(song.artist) &&
                abs(item.optDouble("duration", -100.0) - song.duration / 1000.0) <= 2.0 &&
                (!item.optString("syncedLyrics").isBlank() && item.optString("syncedLyrics") != "null")
            var match = exact?.takeIf(::suitable)
            if (match == null && ticket == generation) {
                val found = query("search", mapOf("track_name" to song.title, "artist_name" to song.artist))
                    ?.let(::JSONArray)
                val candidates = (0 until (found?.length() ?: 0)).map { found!!.getJSONObject(it) }.filter(::suitable)
                // Ambiguous recordings do not silently choose a different release.
                match = candidates.firstOrNull { normalized(it.optString("albumName")) == normalized(song.album) }
                    ?: candidates.singleOrNull()
            }
            val raw = match?.optString("syncedLyrics").orEmpty()
            val lines = parse(raw)
            if (ticket != generation) return InstrumentLyricResult(emptyList(), "cancelled")
            if (lines.isEmpty()) InstrumentLyricResult(emptyList(), "not_found")
            else {
                synchronized(this) {
                    if (ticket == generation) {
                        directory.mkdirs()
                        val temp = File(directory, song.key + ".tmp")
                        temp.writeText(raw)
                        if (!temp.renameTo(file)) temp.delete()
                        var total = bytes()
                        directory.listFiles()?.sortedBy { it.lastModified() }?.forEach {
                            if (total > CACHE_BYTES) { val size = it.length(); if (it.delete()) total -= size }
                        }
                    }
                }
                InstrumentLyricResult(lines, "matched")
            }
        } catch (_: Exception) { InstrumentLyricResult(emptyList(), "network_error") }
    }

    companion object {
        private const val MAX_BYTES = 1_048_576
        private const val CACHE_BYTES = 50L * 1024 * 1024
        private fun normalized(value: String) = value.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
        fun parse(raw: String): List<InstrumentLyricLine> {
            if (raw.length > MAX_BYTES) return emptyList()
            val stamp = Regex("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
            val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60000, 60000) ?: 0L
            return raw.lineSequence().take(10000).flatMap { line ->
                val text = line.replace(stamp, "").replace(Regex("<[^>]*>"), "").trim().take(500)
                stamp.findAll(line).map { m ->
                    val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    InstrumentLyricLine((m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + fraction - offset).coerceAtLeast(0), text)
                }
            }.sortedBy { it.time }.distinct().toList()
        }
    }
}
