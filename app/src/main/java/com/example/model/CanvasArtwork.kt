package com.example.model

import java.text.Normalizer

data class CanvasArtwork(
    val videoUrl: String,
    val fallbackImageUrl: String? = null,
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val sourceLabel: String = "Motion"
) {
    fun matches(trackTitle: String, trackArtist: String): Boolean {
        if (videoUrl.isBlank()) return false
        val expectedTitle = normalizeCanvasToken(trackTitle)
        val expectedArtist = normalizePrimaryArtist(trackArtist)
        val candidateTitle = normalizeCanvasToken(matchedTitle)
        val candidateArtist = normalizePrimaryArtist(matchedArtist)

        if (expectedTitle.isBlank() || expectedArtist.isBlank()) return false
        if (candidateTitle.isBlank() || candidateArtist.isBlank()) return false

        val titleOk = candidateTitle == expectedTitle ||
            (expectedTitle.length >= 4 && candidateTitle.contains(expectedTitle)) ||
            (candidateTitle.length >= 4 && expectedTitle.contains(candidateTitle)) ||
            tokenOverlapRatio(expectedTitle, candidateTitle) >= 0.70f

        val artistOk = candidateArtist == expectedArtist ||
            candidateArtist.contains(expectedArtist) ||
            expectedArtist.contains(candidateArtist) ||
            tokenOverlapRatio(expectedArtist, candidateArtist) >= 0.60f

        return titleOk && artistOk
    }

    companion object {
        private val BRACKETED_SUFFIX_REGEX = Regex("""(\s*[\(\[].*?[\)\]]\s*|\s+-\s+(remaster|live|acoustic|deluxe|version|mono|stereo|edit|radio|explicit).*$)""", RegexOption.IGNORE_CASE)
        private val FEAT_REGEX = Regex("""\s+(feat\.?|ft\.?|featuring|with|x|&|,)\s+.*$""", RegexOption.IGNORE_CASE)
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

        fun normalizePrimaryArtist(raw: String): String {
            val primary = FEAT_REGEX.replace(raw, "")
            return normalizeCanvasToken(primary)
        }

        private fun tokenOverlapRatio(a: String, b: String): Float {
            val tokensA = a.split(' ').filter { it.length > 1 }.toSet()
            val tokensB = b.split(' ').filter { it.length > 1 }.toSet()
            if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
            val shared = tokensA.intersect(tokensB).size
            val denom = maxOf(tokensA.size, tokensB.size)
            return shared.toFloat() / denom.toFloat()
        }
    }
}
