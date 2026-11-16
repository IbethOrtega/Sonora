package com.example.ui.components

import androidx.compose.ui.text.AnnotatedString

private val GOOGLE_SIZE_DIM_REGEX = Regex("=w\\d+-h\\d+[^\"&]*")
private val GOOGLE_SQUARE_DIM_REGEX = Regex("=s\\d+[^\"&]*")

/**
 * Upgrades Google / YouTube / Music artwork URLs to crisp 1200x1200 high-resolution
 * square assets so album covers look razor-sharp on high-DPI displays without pixelation.
 */
fun upgradeArtworkUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return ""
    val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed

    val isGoogleHosted = normalized.contains("googleusercontent.com", ignoreCase = true) ||
        normalized.contains("ggpht.com", ignoreCase = true)

    if (isGoogleHosted) {
        return when {
            GOOGLE_SIZE_DIM_REGEX.containsMatchIn(normalized) ->
                normalized.replace(GOOGLE_SIZE_DIM_REGEX, "=w1200-h1200-l95-rj")
            GOOGLE_SQUARE_DIM_REGEX.containsMatchIn(normalized) ->
                normalized.replace(GOOGLE_SQUARE_DIM_REGEX, "=s1200-l95-rj")
            else -> normalized
        }
    }

    if (normalized.contains("i.ytimg.com/vi/", ignoreCase = true)) {
        return when {
            normalized.endsWith("/default.jpg") -> normalized.replace("/default.jpg", "/hqdefault.jpg")
            normalized.endsWith("/mqdefault.jpg") -> normalized.replace("/mqdefault.jpg", "/hqdefault.jpg")
            else -> normalized
        }
    }

    return normalized
}
