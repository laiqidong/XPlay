package com.shilapi.xcertplay

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Wire formats only; provider payloads never run code or resolve external XML entities. */
internal object InstrumentTimedLyrics {
    private const val MAX_BYTES = 1_048_576
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun neteaseBody(id: String): String {
        val path = "/api/song/lyric/v1"
        val json = JSONObject().put("id", id.toLong()).put("lv", -1).put("tv", -1)
            .put("rv", -1).put("yv", -1).put("e_r", false).toString()
        val signature = hex(MessageDigest.getInstance("MD5").digest("nobody${path}use${json}md5forencrypt".toByteArray()))
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        return "params=" + hex(cipher.doFinal("$path-36cd479b6b5-$json-36cd479b6b5-$signature".toByteArray())).uppercase()
    }
    fun decodeQrc(value: String): String {
        require(value.length in 16..MAX_BYTES && value.length % 16 == 0)
        val bytes = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val compressed = InstrumentQrcCipher.decrypt(bytes)
        return java.util.zip.InflaterInputStream(compressed.inputStream()).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }
    fun parse(raw: String): List<InstrumentLyricLine> = runCatching {
        if (raw.contains("<tt") || raw.contains(":tt")) ttml(raw) else syllables(raw)
    }.getOrDefault(emptyList())

    private fun syllables(raw: String): List<InstrumentLyricLine> {
        val qrc = Regex("LyricContent=\"(.*?)\"", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)
        val content = qrc?.replace("&#10;", "\n")?.replace("&#xA;", "\n")
            ?.replace("&quot;", "\"")?.replace("&apos;", "'")?.replace("&lt;", "<")
            ?.replace("&gt;", ">")?.replace("&amp;", "&") ?: raw
        val linePattern = Regex("^\\[(\\d{1,9}),(\\d{1,9})](.*)$")
        val yrc = Regex("\\((\\d{1,9}),(\\d{1,9}),\\d+\\)(.*?)(?=\\(\\d+,\\d+,\\d+\\)|$)")
        val qrcWord = Regex("(.*?)\\((\\d{1,9}),(\\d{1,9})\\)")
        return content.lineSequence().take(10000).mapNotNull { row ->
            val line = linePattern.matchEntire(row.trim()) ?: return@mapNotNull null
            val body = line.groupValues[3]
            val isYrc = yrc.containsMatchIn(body)
            val matches = (if (isYrc) yrc else qrcWord).findAll(body).take(500)
            val text = StringBuilder()
            val words = matches.map { match ->
                val start = match.groupValues[if (isYrc) 1 else 2].toLong()
                val duration = match.groupValues[if (isYrc) 2 else 3].toLong()
                val from = text.length
                text.append(match.groupValues[if (isYrc) 3 else 1])
                InstrumentLyricWord(start, start + duration, from, text.length)
            }.toList()
            if (words.isEmpty() || text.length > 500) null
            else InstrumentLyricLine(line.groupValues[1].toLong(), text.toString(), words)
        }.sortedBy { it.time }.distinctBy { it.time }.toList()
    }
    private fun time(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val seconds = when {
            value.endsWith("ms") -> return value.dropLast(2).toDoubleOrNull()?.toLong()
            value.endsWith("s") -> value.dropLast(1).toDoubleOrNull()
            else -> value.split(':').fold(0.0) { total, part -> total * 60 + (part.toDoubleOrNull() ?: return null) }
        } ?: return null
        return (seconds * 1000).toLong().takeIf { seconds.isFinite() && it in 0..86400000L }
    }
    private data class Span(val depth: Int, val from: Int, val start: Long, val end: Long, val wordCount: Int)
    private fun ttml(raw: String): List<InstrumentLyricLine> {
        require(!raw.contains("<!DOCTYPE", true) && !raw.contains("<!ENTITY", true))
        val parser = Xml.newPullParser().apply { setInput(raw.reader()) }
        fun attr(name: String): String? = (0 until parser.attributeCount).firstOrNull {
            parser.getAttributeName(it).substringAfter(':') == name
        }?.let { parser.getAttributeValue(it) }
        val lines = mutableListOf<InstrumentLyricLine>()
        var paragraph = -1
        var ignored = -1
        var start = 0L
        val text = StringBuilder()
        val words = mutableListOf<InstrumentLyricWord>()
        val spans = mutableListOf<Span>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            require(lines.size <= 10000 && text.length <= 500 && parser.depth <= 32)
            val name = parser.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (ignored < 0 && attr("role") in listOf("x-bg", "x-translation", "x-roman")) ignored = parser.depth
                    if (ignored < 0 && name == "p") {
                        paragraph = parser.depth; start = time(attr("begin")) ?: 0L
                        text.clear(); words.clear(); spans.clear()
                    } else if (ignored < 0 && paragraph >= 0 && name == "span") {
                        val begin = time(attr("begin"))
                        val end = time(attr("end"))
                        if (begin != null && end != null && end >= begin)
                            spans.add(Span(parser.depth, text.length, begin, end, words.size))
                    }
                }
                XmlPullParser.TEXT -> if (paragraph >= 0 && ignored < 0) {
                    val value = parser.text.orEmpty()
                    if (spans.isNotEmpty() || value.isNotBlank() || !value.contains('\n')) text.append(value)
                }
                XmlPullParser.END_TAG -> {
                    if (ignored == parser.depth) ignored = -1
                    else if (ignored < 0 && paragraph >= 0) {
                        if (name == "span" && spans.lastOrNull()?.depth == parser.depth) {
                            val span = spans.removeAt(spans.lastIndex)
                            if (span.from < text.length && span.wordCount == words.size)
                                words.add(InstrumentLyricWord(span.start, span.end, span.from, text.length))
                        }
                        if (name == "p" && parser.depth == paragraph) {
                            if (words.isNotEmpty()) lines.add(InstrumentLyricLine(start, text.toString(), words.toList()))
                            paragraph = -1
                        }
                    }
                }
            }
            event = parser.next()
        }
        return lines.sortedBy { it.time }.distinctBy { it.time }
    }
}
