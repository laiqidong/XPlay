package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

internal data class InstrumentSong(val title: String, val artist: String, val album: String, val duration: Long) {
    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$title\u0000$artist\u0000$album\u0000$duration".toByteArray())
        .joinToString("") { "%02x".format(it) }
}
internal data class InstrumentLyricLine(val time: Long, val text: String)
internal data class InstrumentLyricResult(val lines: List<InstrumentLyricLine>, val status: String)

/** Bounded HTTPS lookup. No Apple credentials, audio, location or device identifiers are sent. */
internal class InstrumentLyricsRepository(context: Context) {
    val chinese = InstrumentChineseText(context.applicationContext)
    private val matcher = InstrumentSongMatcher(chinese)
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
        val deadline = android.os.SystemClock.elapsedRealtime() + 30000
        var networkFailure = false
        fun query(base: String, fields: Map<String, String>): String? {
            if (ticket != generation || android.os.SystemClock.elapsedRealtime() >= deadline) return null
            val params = fields.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val conn = URL("$base?$params").openConnection() as HttpURLConnection
            connection = conn
            try {
                conn.connectTimeout = 3500; conn.readTimeout = 3500
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "XPlay-InstrumentLyrics/1.1")
                if (base.startsWith("https://music.163.com/")) conn.setRequestProperty("Referer", "https://music.163.com/")
                val code = conn.responseCode
                if (code == 404) return null
                check(code == 200)
                return conn.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        check(ticket == generation && android.os.SystemClock.elapsedRealtime() < deadline)
                        val count = input.read(chunk)
                        if (count < 0) break
                        check(buffer.size() + count <= MAX_BYTES)
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray().toString(Charsets.UTF_8)
                }
            } catch (_: Exception) { networkFailure = true; return null }
            finally { conn.disconnect(); if (connection === conn) connection = null }
        }
        fun objects(array: JSONArray?): List<JSONObject> = (0 until minOf(array?.length() ?: 0, 30))
            .mapNotNull { array?.optJSONObject(it) }
        fun lrclib(): String? {
            val variants = listOf(song.copy(title = chinese.simplified(song.title),
                artist = chinese.simplified(song.artist), album = chinese.simplified(song.album)), song).distinct()
            fun candidate(item: JSONObject) = InstrumentSongMatcher.Candidate(item.optString("id"),
                item.optString("trackName"), listOf(item.optString("artistName")), item.optString("albumName"),
                (item.optDouble("duration", -1.0) * 1000).toLong(), item.optString("syncedLyrics"))
            fun usable(items: List<JSONObject>) = items.filter { parse(it.optString("syncedLyrics")).isNotEmpty() }.map(::candidate)
            for (variant in variants) {
                val exact = query("https://lrclib.net/api/get", mapOf("track_name" to variant.title,
                    "artist_name" to variant.artist, "album_name" to variant.album,
                    "duration" to (variant.duration / 1000.0).toString()))?.let(::JSONObject)
                matcher.select(song, usable(listOfNotNull(exact)))?.lyrics?.let { return it }
            }
            val found = mutableListOf<InstrumentSongMatcher.Candidate>()
            for (title in listOf(matcher.searchTitle(song.title), song.title).distinct()) {
                val response = query("https://lrclib.net/api/search", mapOf("track_name" to title))?.let(::JSONArray)
                found += usable(objects(response))
                matcher.select(song, found)?.lyrics?.let { return it }
            }
            return null
        }
        fun netease(): String? {
            val title = matcher.searchTitle(song.title)
            val response = query("https://music.163.com/api/search/get/web", mapOf("s" to "$title ${chinese.simplified(song.artist)}",
                "type" to "1", "limit" to "30", "offset" to "0"))?.let(::JSONObject) ?: return null
            check(response.optInt("code") == 200)
            val candidates = objects(response.optJSONObject("result")?.optJSONArray("songs")).map { item ->
                InstrumentSongMatcher.Candidate(item.optString("id"), item.optString("name"),
                    objects(item.optJSONArray("artists")).map { it.optString("name") },
                    item.optJSONObject("album")?.optString("name").orEmpty(), item.optLong("duration", -1))
            }
            val selected = matcher.select(song, candidates) ?: return null
            val lyrics = query("https://music.163.com/api/song/lyric", mapOf("id" to selected.id,
                "lv" to "-1", "kv" to "-1", "tv" to "-1"))?.let(::JSONObject) ?: return null
            check(lyrics.optInt("code") == 200)
            return lyrics.optJSONObject("lrc")?.optString("lyric")?.takeIf { parse(it).isNotEmpty() }
        }
        fun safely(provider: () -> String?): String? = try { provider() }
            catch (_: Exception) { networkFailure = true; null }
        return try {
            val raw = (safely(::netease) ?: safely(::lrclib)).orEmpty()
            val lines = parse(raw)
            if (ticket != generation) return InstrumentLyricResult(emptyList(), "cancelled")
            if (lines.isEmpty()) InstrumentLyricResult(emptyList(), if (networkFailure) "network_error" else "not_found")
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
