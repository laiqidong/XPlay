package com.shilapi.xcertplay

import kotlin.math.abs

/** Conservative metadata matching across catalogues, with recording versions kept distinct. */
internal class InstrumentSongMatcher(private val chinese: InstrumentChineseText) {
    data class Candidate(val id: String, val title: String, val artists: List<String>, val album: String,
        val duration: Long, val lyrics: String? = null)
    fun normalized(value: String): String = chinese.simplified(value).lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
    private val featuring = Regex("\\s*(?:\\(|（)?(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    fun searchTitle(value: String): String = chinese.simplified(value).replace(featuring, "").trim()
    private fun versions(value: String): Set<String> {
        val text = chinese.simplified(value).lowercase(java.util.Locale.ROOT)
        return listOf("live" to Regex("\\blive\\b|现场|演唱会"),
            "instrumental" to Regex("\\binstrumental\\b|伴奏|纯音乐"),
            "remix" to Regex("\\bremix\\b|混音"), "acoustic" to Regex("\\bacoustic\\b|不插电"),
            "cover" to Regex("\\bcover\\b|翻唱"), "demo" to Regex("\\bdemo\\b|小样"))
            .filter { it.second.containsMatchIn(text) }.map { it.first }.toSet()
    }
    private fun artists(value: String) = chinese.simplified(value)
        .split(Regex("\\s*(?:、|&|/|;|；|,|，|\\bfeat\\.?\\s+|\\bft\\.?\\s+|\\bfeaturing\\s+)\\s*", RegexOption.IGNORE_CASE))
        .map(::normalized).filter(String::isNotBlank).toSet()
    fun select(song: InstrumentSong, candidates: List<Candidate>): Candidate? {
        val targetTitle = normalized(searchTitle(song.title))
        val targetArtists = artists(song.artist)
        if (targetTitle.isBlank() || targetArtists.isEmpty()) return null
        val ranked = candidates.distinctBy { it.id }.mapNotNull { candidate ->
            val delta = abs(candidate.duration - song.duration)
            val foundArtists = candidate.artists.flatMap { artists(it) }.toSet()
            if (delta > 5000 || normalized(searchTitle(candidate.title)) != targetTitle ||
                versions(candidate.title) != versions(song.title) || targetArtists.intersect(foundArtists).isEmpty()) null
            else {
                val albumMatch = song.album.isNotBlank() && normalized(song.album) == normalized(candidate.album)
                val artistMatch = targetArtists == foundArtists
                val score = 80 + (if (artistMatch) 10 else 0) + (if (albumMatch) 15 else 0) +
                    ((5000 - delta) / 500).toInt()
                candidate to score
            }
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        // Equivalent catalogue duplicates are harmless; ambiguous different recordings are skipped.
        return best.first.takeIf { second == null || best.second - second.second >= 4 ||
            (abs(best.first.duration - second.first.duration) <= 1000 &&
                normalized(best.first.album) == normalized(second.first.album)) }
    }
}
