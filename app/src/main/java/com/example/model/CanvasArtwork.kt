package com.example.model

import java.text.Normalizer

enum class CanvasSource {
    APPLE,
    TIDAL,
    OTHER
}

data class CanvasArtwork(
    val videoUrl: String,
    val fallbackUrl: String? = null,
    val portraitUrl: String? = null,
    val fallbackImageUrl: String? = null,
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val album: String? = null,
    val source: CanvasSource = CanvasSource.OTHER,
    val sourceLabel: String = "Motion"
) {
    fun primaryStreamUrl(preferPortrait: Boolean): String? {
        return if (preferPortrait && !portraitUrl.isNullOrBlank()) {
            portraitUrl
        } else {
            videoUrl.takeIf { it.isNotBlank() } ?: fallbackUrl ?: portraitUrl
        }
    }

    fun matches(trackTitle: String, trackArtist: String, trackAlbum: String? = null): Boolean {
        val hasAnyStream = videoUrl.isNotBlank() || !portraitUrl.isNullOrBlank() || !fallbackUrl.isNullOrBlank()
        if (!hasAnyStream) return false

        val expectedTitle = normalizeCanvasToken(trackTitle)
        val candidateTitle = normalizeCanvasToken(matchedTitle)
        if (expectedTitle.isBlank() || candidateTitle.isBlank()) return false
        if (candidateTitle != expectedTitle) return false

        val expectedArtists = splitNormalizedArtists(trackArtist)
        val candidateArtists = splitNormalizedArtists(matchedArtist)
        if (expectedArtists.isEmpty() || candidateArtists.isEmpty()) return false

        val artistsMatch = expectedArtists == candidateArtists ||
            expectedArtists.first() == candidateArtists.first() ||
            (expectedArtists.size > 1 && candidateArtists.containsAll(expectedArtists)) ||
            (candidateArtists.size > 1 && expectedArtists.containsAll(candidateArtists))
        if (!artistsMatch) return false

        val normalizedExpectedAlbum = trackAlbum?.let { normalizeCanvasToken(it) }.orEmpty()
        val normalizedCandidateAlbum = album?.let { normalizeCanvasToken(it) }.orEmpty()
        if (normalizedExpectedAlbum.isNotBlank() && normalizedCandidateAlbum.isNotBlank()) {
            if (normalizedExpectedAlbum != normalizedCandidateAlbum) return false
        }

        return true
    }

    companion object {
        private val BRACKETED_SUFFIX_REGEX = Regex(
            """(\s*[\(\[].*?[\)\]]\s*|\s+-\s+(remaster(ed)?|live|acoustic|deluxe|version|mono|stereo|edit|radio|explicit|single|ep).*$)""",
            RegexOption.IGNORE_CASE
        )
        private val ARTIST_SEPARATOR_REGEX = Regex(
            """\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|\bx\b|×)\s*""",
            RegexOption.IGNORE_CASE
        )
        private val FEAT_TAIL_REGEX = Regex(
            """\s+(?:feat\.?|ft\.?|featuring|with)\s+.*$""",
            RegexOption.IGNORE_CASE
        )
        private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
        private val NON_ALNUM_REGEX = Regex("""[^a-z0-9\s]""")

        fun normalizeCanvasToken(raw: String): String {
            val withoutBrackets = BRACKETED_SUFFIX_REGEX.replace(raw, " ")
            val decomposed = Normalizer.normalize(withoutBrackets, Normalizer.Form.NFD)
            val ascii = DIACRITICS_REGEX.replace(decomposed, "").lowercase()
            return NON_ALNUM_REGEX.replace(ascii, " ")
                .trim()
                .replace(Regex("""\s+"""), " ")
        }

        fun splitNormalizedArtists(raw: String): List<String> {
            return raw.split(ARTIST_SEPARATOR_REGEX)
                .map { normalizeCanvasToken(it) }
                .filter { it.isNotBlank() }
                .distinct()
        }

        fun normalizePrimaryArtist(raw: String): String {
            val stripped = FEAT_TAIL_REGEX.replace(raw, "")
            return splitNormalizedArtists(stripped).firstOrNull() ?: normalizeCanvasToken(stripped)
        }
    }
}
