package com.example.model

import java.text.Normalizer

enum class CanvasSource {
    APPLE,
    SPOTIFY,
    TIDAL,
    OTHER
}

enum class CanvasVariant {
    APPLE_VERTICAL,
    APPLE_SQUARE,
    APPLE_RAW,
    SPOTIFY_CANVAS,
    TIDAL_SQUARE,
    OTHER
}

data class CanvasStatusInfo(
    val videoId: String,
    val label: String,
    val reason: String? = null,
    val source: CanvasSource? = null,
    val variant: CanvasVariant? = null
) {
    fun displaySummary(): String = if (reason.isNullOrBlank()) label else "$label — $reason"
}

data class CanvasDiagnosticCandidate(
    val title: String,
    val artist: String,
    val album: String?,
    val appleAlbumId: String?,
    val score: Int,
    val rejectedReason: String? = null,
    val editorialKeys: List<String> = emptyList()
)

data class CanvasDiagnosticReport(
    val queryTitle: String,
    val queryArtist: String,
    val queryAlbum: String?,
    val storefront: String,
    val searchTerm: String,
    val tokenStatus: String,
    val tokenExpiresAtEpochSec: Long?,
    val candidates: List<CanvasDiagnosticCandidate>,
    val selectedAlbumId: String?,
    val editorialKeysFound: List<String>,
    val resolvedArtwork: CanvasArtwork?,
    val sanitizedUrls: List<String>,
    val steps: List<String>,
    val failureReason: String? = null
)

data class CanvasArtwork(
    val videoUrl: String,
    val fallbackUrl: String? = null,
    val portraitUrl: String? = null,
    val fallbackImageUrl: String? = null,
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val album: String? = null,
    val appleAlbumId: String? = null,
    val source: CanvasSource = CanvasSource.OTHER,
    val variant: CanvasVariant = CanvasVariant.OTHER,
    val sourceLabel: String = "Apple Music"
) {
    fun primaryStreamUrl(preferPortrait: Boolean): String? {
        return if (preferPortrait && !portraitUrl.isNullOrBlank()) {
            portraitUrl
        } else {
            videoUrl.takeIf { it.isNotBlank() } ?: fallbackUrl ?: portraitUrl
        }
    }

    fun menuStatusLabel(preferPortrait: Boolean = true): String {
        val chosen = primaryStreamUrl(preferPortrait)
        return when {
            source == CanvasSource.APPLE && preferPortrait && !portraitUrl.isNullOrBlank() && chosen == portraitUrl ->
                "Apple Music (vertical)"
            source == CanvasSource.APPLE && (variant == CanvasVariant.APPLE_SQUARE || chosen != portraitUrl) ->
                "Apple Music (cuadrada)"
            source == CanvasSource.APPLE -> "Apple Music (vertical)"
            source == CanvasSource.SPOTIFY -> "Spotify Canvas"
            source == CanvasSource.TIDAL -> "Tidal Motion"
            else -> sourceLabel.ifBlank { "Portada animada" }
        }
    }

    fun matches(trackTitle: String, trackArtist: String, trackAlbum: String? = null): Boolean {
        val hasAnyStream = videoUrl.isNotBlank() || !portraitUrl.isNullOrBlank() || !fallbackUrl.isNullOrBlank()
        if (!hasAnyStream) return false
        if (isCompilationTitle(matchedTitle) || isCompilationTitle(album)) return false

        val expectedTitle = normalizeCanvasToken(trackTitle)
        val candidateTitle = normalizeCanvasToken(matchedTitle)
        if (expectedTitle.isBlank() || candidateTitle.isBlank()) return false
        if (candidateTitle != expectedTitle) return false

        val expectedArtists = splitNormalizedArtists(trackArtist)
        val candidateArtists = splitNormalizedArtists(matchedArtist)
        if (expectedArtists.isEmpty() || candidateArtists.isEmpty()) return false

        // All requested artists must be present in candidate artists
        val allRequestedPresent = expectedArtists.all { req ->
            candidateArtists.any { cand -> cand == req }
        }
        if (!allRequestedPresent) return false

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

        private val COMPILATION_KEYWORDS = listOf(
            "greatest hits",
            "best of",
            "essentials",
            "hits",
            "playlist",
            "radio"
        )

        fun isCompilationTitle(raw: String?): Boolean {
            val rawText = raw?.trim().orEmpty()
            if (rawText.isBlank()) return false
            val norm = normalizeStrictAscii(rawText)
            return COMPILATION_KEYWORDS.any { kw ->
                Regex("""\b${Regex.escape(kw)}\b""").containsMatchIn(norm)
            }
        }

        fun normalizeStrictAscii(raw: String): String {
            val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
            val ascii = DIACRITICS_REGEX.replace(decomposed, "").lowercase()
            return NON_ALNUM_REGEX.replace(ascii, " ")
                .trim()
                .replace(Regex("""\s+"""), " ")
        }

        fun normalizeCanvasToken(raw: String): String {
            val withoutBrackets = BRACKETED_SUFFIX_REGEX.replace(raw, " ")
            return normalizeStrictAscii(withoutBrackets)
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
