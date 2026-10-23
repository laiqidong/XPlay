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
    val key: String by lazy { MessageDigest.getInstance("SHA-256")
        .digest("$title\u0000$artist\u0000$album\u0000$duration".toByteArray())
        .joinToString("") { "%02x".format(it) } }
}
internal data class InstrumentLyricLine(val time: Long, val text: String)
internal data class InstrumentLyricResult(val lines: List<InstrumentLyricLine>, val status: String, val source: String = "", val retryAfterMillis: Long = 0)

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

    fun forget(key: String) { cancel(); synchronized(this) {
        for (suffix in listOf(".lrc", ".json", ".miss", ".v2.miss")) File(directory, key + suffix).delete()
    } }
    fun find(song: InstrumentSong, online: Boolean, ticket: Long): InstrumentLyricResult {
        val file = File(directory, song.key + ".lrc")
        val cached = runCatching {
            if (file.isFile && file.length() <= MAX_BYTES) parse(file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        if (cached.any { it.text.isNotBlank() }) {
            file.setLastModified(System.currentTimeMillis())
            val metadata = File(directory, song.key + ".json")
            val source = runCatching {
                metadata.takeIf { it.length() in 1..4096 }?.readText()?.let(::JSONObject)?.optString("source").orEmpty()
            }.getOrDefault("")
            if (metadata.isFile) metadata.setLastModified(System.currentTimeMillis())
            return InstrumentLyricResult(cached, "cached", source)
        }
        if (!online) return InstrumentLyricResult(emptyList(), "offline")
        if (song.duration <= 0) return InstrumentLyricResult(emptyList(), "waiting_duration")
        val miss = File(directory, song.key + ".v2.miss")
        val missAge = System.currentTimeMillis() - miss.lastModified()
        if (miss.isFile && missAge in 0 until MISS_TTL)
            return InstrumentLyricResult(emptyList(), "not_found", retryAfterMillis = MISS_TTL - missAge)
        val deadline = android.os.SystemClock.elapsedRealtime() + 30000
        var providerDeadline = deadline
        var networkFailure = false
        fun query(base: String, fields: Map<String, String>): String? {
            if (ticket != generation) return null
            if (android.os.SystemClock.elapsedRealtime() >= minOf(deadline, providerDeadline)) { networkFailure = true; return null }
            val params = fields.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val conn = URL("$base?$params").openConnection() as HttpURLConnection
            connection = conn
            try {
                val remaining = (minOf(deadline, providerDeadline) - android.os.SystemClock.elapsedRealtime()).coerceIn(1, 3500).toInt()
                conn.connectTimeout = remaining; conn.readTimeout = remaining
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "XPlay-InstrumentLyrics/1.1")
                when (URL(base).host) {
                    "music.163.com" -> conn.setRequestProperty("Referer", "https://music.163.com/")
                    "c.y.qq.com" -> conn.setRequestProperty("Referer", "https://y.qq.com/")
                    "lyrics.kugou.com" -> conn.setRequestProperty("Referer", "https://www.kugou.com/")
                }
                val code = conn.responseCode
                if (code == 404) return null
                check(code == 200)
                return conn.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        check(ticket == generation && android.os.SystemClock.elapsedRealtime() < minOf(deadline, providerDeadline))
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
            val remaining = candidates.toMutableList()
            repeat(3) {
                val selected = matcher.select(song, remaining) ?: return null
                val lyrics = query("https://music.163.com/api/song/lyric", mapOf("id" to selected.id,
                    "lv" to "-1", "kv" to "-1", "tv" to "-1"))?.let(::JSONObject)
                if (lyrics != null) {
                    check(lyrics.optInt("code") == 200)
                    lyrics.optJSONObject("lrc")?.optString("lyric")?.takeIf { parse(it).any { line -> line.text.isNotBlank() } }
                        ?.let { return it }
                }
                remaining.removeAll { it.id == selected.id }
            }
            return null
        }
        fun qqmusic(): String? {
            val response = query("https://c.y.qq.com/soso/fcgi-bin/client_search_cp", mapOf(
                "w" to "${matcher.searchTitle(song.title)} ${chinese.simplified(song.artist)}",
                "format" to "json", "p" to "1", "n" to "30"))?.let(::JSONObject) ?: return null
            check(response.optInt("code", -1) == 0)
            val remaining = objects(response.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list"))
                .map { item -> InstrumentSongMatcher.Candidate(item.optString("songmid"), item.optString("songname"),
                    objects(item.optJSONArray("singer")).map { it.optString("name") },
                    item.optString("albumname"), item.optLong("interval", -1) * 1000) }
                .filter { it.id.isNotBlank() }.toMutableList()
            repeat(3) {
                val selected = matcher.select(song, remaining) ?: return null
                val responseLyrics = query("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg", mapOf(
                    "songmid" to selected.id, "format" to "json", "nobase64" to "0"))?.let(::JSONObject)
                if (responseLyrics != null) {
                    check(responseLyrics.optInt("code", -1) == 0)
                    val raw = runCatching { String(android.util.Base64.decode(responseLyrics.optString("lyric"),
                        android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
                    if (parse(raw).any { it.text.isNotBlank() }) return raw
                }
                remaining.removeAll { it.id == selected.id }
            }
            return null
        }
        fun kugou(): String? {
            val response = query("https://lyrics.kugou.com/search", mapOf("ver" to "1", "man" to "yes",
                "keyword" to "${matcher.searchTitle(song.title)} ${chinese.simplified(song.artist)}",
                "duration" to song.duration.toString()))?.let(::JSONObject) ?: return null
            check(response.optInt("status", -1) == 200)
            val entries = objects(response.optJSONArray("candidates"))
            val remaining = entries.map { item -> InstrumentSongMatcher.Candidate(item.optString("id"),
                item.optString("song"), listOf(item.optString("singer")), "", item.optLong("duration", -1)) }
                .filter { it.id.isNotBlank() }.toMutableList()
            repeat(3) {
                val selected = matcher.select(song, remaining) ?: return null
                val key = entries.firstOrNull { it.optString("id") == selected.id }?.optString("accesskey").orEmpty()
                if (key.isNotBlank()) {
                    val downloaded = query("https://lyrics.kugou.com/download", mapOf("ver" to "1", "client" to "pc",
                        "id" to selected.id, "accesskey" to key, "fmt" to "lrc", "charset" to "utf8"))?.let(::JSONObject)
                    if (downloaded != null) {
                        check(downloaded.optInt("status", -1) == 200)
                        val raw = runCatching { String(android.util.Base64.decode(downloaded.optString("content"),
                            android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
                        if (parse(raw).any { it.text.isNotBlank() }) return raw
                    }
                }
                remaining.removeAll { it.id == selected.id }
            }
            return null
        }
        fun safely(provider: () -> String?): String? = try {
            // Reserve time for later sources when an earlier provider is slow or unavailable.
            providerDeadline = minOf(deadline, android.os.SystemClock.elapsedRealtime() + 7000)
            provider()
        }
            catch (_: Exception) { networkFailure = true; null }
        return try {
            var source = "netease"
            val raw = (safely(::netease)
                ?: run { source = "qqmusic"; safely(::qqmusic) }
                ?: run { source = "kugou"; safely(::kugou) }
                ?: run { source = "lrclib"; safely(::lrclib) }).orEmpty()
            val lines = parse(raw)
            if (ticket != generation) return InstrumentLyricResult(emptyList(), "cancelled")
            if (lines.none { it.text.isNotBlank() }) {
                if (!networkFailure) runCatching { synchronized(this) {
                    if (ticket == generation) { directory.mkdirs(); miss.writeText("not_found"); trimCache() }
                } }
                InstrumentLyricResult(emptyList(), if (networkFailure) "network_error" else "not_found",
                    retryAfterMillis = if (networkFailure) 0 else MISS_TTL)
            }
            else {
                runCatching { synchronized(this) {
                    if (ticket == generation) {
                        directory.mkdirs()
                        miss.delete()
                        val temp = File(directory, song.key + ".tmp")
                        temp.writeText(raw)
                        if (!temp.renameTo(file)) temp.delete()
                        val metadata = File(directory, song.key + ".json.tmp")
                        metadata.writeText(JSONObject().put("source", source).toString())
                        if (!metadata.renameTo(File(directory, song.key + ".json"))) metadata.delete()
                        trimCache()
                    }
                } }
                InstrumentLyricResult(lines, "matched", source)
            }
        } catch (_: Exception) { InstrumentLyricResult(emptyList(), "network_error") }
    }

    private fun trimCache() {
        val now = System.currentTimeMillis()
        directory.listFiles()?.filter { it.extension == "miss" && now - it.lastModified() > MISS_TTL }?.forEach { it.delete() }
        var files = directory.listFiles()?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        for (oldest in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_BYTES && files.size <= MAX_CACHE_FILES) break
            val group = files.filter { it.name.substringBefore('.') == oldest.name.substringBefore('.') }
            for (file in group) file.delete()
            files = files.filter { it.exists() }
            total = files.sumOf { it.length() }
        }
    }

    companion object {
        private const val MISS_TTL = 6L * 60 * 60 * 1000
        private const val MAX_BYTES = 1_048_576
        private const val MAX_CACHE_FILES = 2000
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
            }.sortedBy { it.time }.groupBy { it.time }.map { (time, entries) ->
                // Sources expose original text first; never let a duplicate translation or blank overwrite it.
                InstrumentLyricLine(time, entries.firstOrNull { it.text.isNotBlank() }?.text.orEmpty())
            }.toList()
        }
    }
}
