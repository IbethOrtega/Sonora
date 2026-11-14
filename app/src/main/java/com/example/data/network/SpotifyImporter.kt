package com.example.data.network

import com.example.model.SpotifyParsedPlaylist
import com.example.model.SpotifyParsedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class SpotifyImporter(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    suspend fun fetchSpotifyPlaylist(rawInput: String): Result<SpotifyParsedPlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedUrl = resolveRedirects(rawInput.trim())
            val (kind, id) = extractSpotifyResource(resolvedUrl)
                ?: throw IllegalArgumentException(
                    "Enlace de Spotify no válido. Pega una URL de playlist o álbum de open.spotify.com."
                )

            val embedUrl = "https://open.spotify.com/embed/$kind/$id"
            val embedHtml = fetchString(embedUrl)

            parseFromEmbedNextData(embedHtml)?.let { return@runCatching it }

            // Fallback: fetch regular web page and parse title/meta + tracks if available
            val pageUrl = "https://open.spotify.com/$kind/$id"
            val pageHtml = fetchString(pageUrl)
            parseFromWebPageFallback(pageHtml, pageUrl)
                ?: throw IllegalStateException(
                    "No se pudieron leer las canciones de esta playlist. Asegúrate de que sea pública o usa la pestaña 'Lista de texto'."
                )
        }
    }

    fun parsePlainTextTracklist(title: String, rawText: String): SpotifyParsedPlaylist {
        val tracks = rawText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                // Strip leading numbering like "1. " or "01 - "
                val cleaned = line.replace(Regex("^\\d+[.)\\s]+"), "").trim()
                if (cleaned.isBlank()) return@mapNotNull null

                when {
                    cleaned.contains(" - ") -> {
                        val parts = cleaned.split(" - ", limit = 2)
                        SpotifyParsedTrack(title = parts[0].trim(), artist = parts[1].trim())
                    }
                    cleaned.contains(" — ") -> {
                        val parts = cleaned.split(" — ", limit = 2)
                        SpotifyParsedTrack(title = parts[0].trim(), artist = parts[1].trim())
                    }
                    cleaned.contains(",") -> {
                        val parts = cleaned.split(",").map { it.trim().trim('"') }
                        if (parts.size >= 2 && parts[0].lowercase() != "track name" && parts[0].lowercase() != "title") {
                            SpotifyParsedTrack(title = parts[0], artist = parts[1])
                        } else null
                    }
                    else -> SpotifyParsedTrack(title = cleaned, artist = "")
                }
            }
            .toList()

        return SpotifyParsedPlaylist(
            title = title.ifBlank { "Playlist importada" },
            subtitle = "Importada a Sonora",
            tracks = tracks
        )
    }

    private fun resolveRedirects(input: String): String {
        val uriMatch = Regex("(https?://[^\\s]+)").find(input)?.value ?: input
        if (uriMatch.startsWith("spotify:")) return uriMatch
        return runCatching {
            val req = Request.Builder()
                .url(uriMatch)
                .header("User-Agent", BROWSER_UA)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        }.getOrDefault(uriMatch)
    }

    private fun extractSpotifyResource(input: String): Pair<String, String>? {
        val uriRegex = Regex("spotify:(playlist|album):([A-Za-z0-9]+)")
        uriRegex.find(input)?.let { m ->
            return m.groupValues[1] to m.groupValues[2]
        }
        val urlRegex = Regex("open\\.spotify\\.com/(?:intl-[a-z-]+/)?(playlist|album)/([A-Za-z0-9]+)")
        urlRegex.find(input)?.let { m ->
            return m.groupValues[1] to m.groupValues[2]
        }
        return null
    }

    private fun fetchString(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Spotify HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun parseFromEmbedNextData(html: String): SpotifyParsedPlaylist? {
        val regex = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val jsonRaw = regex.find(html)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            val root = JSONObject(jsonRaw)
            val entity = root.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity") ?: return null

            val title = entity.optString("name")
                .ifBlank { entity.optString("title") }
                .ifBlank { "Playlist de Spotify" }
            val subtitle = entity.optString("subtitle")
            val coverUrl = extractEmbedCover(entity)

            val trackList = entity.optJSONArray("trackList") ?: return null
            val parsedTracks = mutableListOf<SpotifyParsedTrack>()
            for (i in 0 until trackList.length()) {
                val item = trackList.optJSONObject(i) ?: continue
                val tTitle = item.optString("title").trim()
                val tArtist = item.optString("subtitle").trim()
                val duration = item.optLong("duration", 0L)
                if (tTitle.isNotBlank()) {
                    parsedTracks.add(
                        SpotifyParsedTrack(
                            title = tTitle,
                            artist = tArtist,
                            durationMs = duration
                        )
                    )
                }
            }
            if (parsedTracks.isEmpty()) return null
            SpotifyParsedPlaylist(
                title = title,
                subtitle = subtitle,
                coverUrl = coverUrl,
                tracks = parsedTracks
            )
        }.getOrNull()
    }

    private fun extractEmbedCover(entity: JSONObject): String {
        val visualSources = entity.optJSONObject("visualIdentity")
            ?.optJSONArray("image")
        if (visualSources != null && visualSources.length() > 0) {
            val url = visualSources.optJSONObject(0)?.optString("url").orEmpty()
            if (url.isNotBlank()) return url
        }
        val coverSources = entity.optJSONObject("coverArt")?.optJSONArray("sources")
        if (coverSources != null && coverSources.length() > 0) {
            return coverSources.optJSONObject(0)?.optString("url").orEmpty()
        }
        return ""
    }

    private fun parseFromWebPageFallback(html: String, pageUrl: String): SpotifyParsedPlaylist? {
        val titleMatch = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)
        val imageMatch = Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(html)
        val trackMatches = Regex("<meta name=\"music:song\" content=\"https://open\\.spotify\\.com/track/([A-Za-z0-9]+)\"").findAll(html).toList()

        if (trackMatches.isEmpty()) return null
        val parsedTracks = mutableListOf<SpotifyParsedTrack>()
        for (match in trackMatches.take(50)) {
            val trackId = match.groupValues[1]
            val oembed = runCatching {
                val json = JSONObject(fetchString("https://open.spotify.com/oembed?url=https://open.spotify.com/track/$trackId"))
                json.optString("title")
            }.getOrNull()
            if (!oembed.isNullOrBlank()) {
                parsedTracks.add(SpotifyParsedTrack(title = oembed, artist = ""))
            }
        }
        if (parsedTracks.isEmpty()) return null
        val title = titleMatch?.groupValues?.getOrNull(1)
            ?.replace(" - playlist by ", " • ")
            ?.ifBlank { "Playlist de Spotify" }
            ?: "Playlist de Spotify"

        return SpotifyParsedPlaylist(
            title = title,
            subtitle = pageUrl,
            coverUrl = imageMatch?.groupValues?.getOrNull(1).orEmpty(),
            tracks = parsedTracks
        )
    }
}
