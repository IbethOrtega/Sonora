package com.example.data.network

import android.net.Uri
import android.util.Log
import com.example.model.CanvasArtwork
import com.example.model.CanvasSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CanvasRepository {
    private const val TAG = "SonoraCanvas"
    private const val MAX_CACHE_SIZE = 64
    private const val NEGATIVE_TTL_MS = 20 * 60_000L

    private val queryMutex = Mutex()

    @Volatile
    private var configuredClient: OkHttpClient? = null

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3200, TimeUnit.MILLISECONDS)
            .readTimeout(4200, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val positiveCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CanvasArtwork>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CanvasArtwork>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    private val negativeCache = ConcurrentHashMap<String, Long>()

    fun configure(okHttpClient: OkHttpClient) {
        configuredClient = okHttpClient.newBuilder()
            .connectTimeout(3200, TimeUnit.MILLISECONDS)
            .readTimeout(4200, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getCached(videoId: String): CanvasArtwork? {
        if (videoId.isBlank()) return null
        return positiveCache[videoId]
    }

    fun clearCache() {
        positiveCache.clear()
        negativeCache.clear()
    }

    suspend fun fetchCanvas(
        videoId: String,
        title: String,
        artist: String,
        album: String? = null
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || videoId.startsWith("local_") || title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        positiveCache[videoId]?.let { cached ->
            if (cached.matches(title, artist, album)) return@withContext cached
        }

        if (!album.isNullOrBlank()) {
            val negExpiresAt = negativeCache[videoId]
            if (negExpiresAt != null && System.currentTimeMillis() < negExpiresAt) {
                return@withContext null
            }
        }

        queryMutex.withLock {
            positiveCache[videoId]?.let { cached ->
                if (cached.matches(title, artist, album)) return@withLock cached
            }

            val resolved = resolveFromProvidersInOrder(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album
            )

            if (resolved != null && resolved.matches(title, artist, album)) {
                positiveCache[videoId] = resolved
                negativeCache.remove(videoId)
                resolved
            } else {
                // Only cache negative misses when album metadata was known
                if (!album.isNullOrBlank()) {
                    negativeCache[videoId] = System.currentTimeMillis() + NEGATIVE_TTL_MS
                }
                null
            }
        }
    }

    private fun resolveFromProvidersInOrder(
        videoId: String,
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        val providers = listOf<Pair<String, () -> CanvasArtwork?>>(
            "AppleMusic" to { queryAppleMusicCatalogCanvas(title, artist, album) },
            "Tidal" to { queryTidalVideoCoverCanvas(title, artist, album) },
            "Community" to { queryCommunityCanvasIndex(videoId, title, artist, album) },
            "iTunesMusicVideo" to { queryItunesMusicVideo(title, artist, album) }
        )

        for ((name, call) in providers) {
            val candidate = runCatching { call() }
                .onFailure { err ->
                    Log.w(TAG, "Provider $name failed for '$title': ${err.message}")
                }
                .getOrNull()
            if (candidate != null && candidate.matches(title, artist, album)) {
                return candidate
            }
        }
        return null
    }

    private fun queryAppleMusicCatalogCanvas(
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        val client = configuredClient ?: defaultClient
        val cleanArtist = CanvasArtwork.normalizePrimaryArtist(artist).ifBlank { artist }
        val cleanTitle = CanvasArtwork.normalizeCanvasToken(title).ifBlank { title }
        val term = Uri.encode("$cleanTitle $cleanArtist")

        val searchUrl = "https://itunes.apple.com/search?term=$term&entity=song&limit=6"
        val searchBody = executeGet(client, searchUrl) ?: return null
        val results = JSONObject(searchBody).optJSONArray("results") ?: return null

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val candTitle = item.optString("trackName").trim()
            val candArtist = item.optString("artistName").trim()
            val candAlbum = item.optString("collectionName").trim().ifBlank { null }

            val probe = CanvasArtwork(
                videoUrl = "probe",
                matchedTitle = candTitle,
                matchedArtist = candArtist,
                album = candAlbum,
                source = CanvasSource.APPLE
            )
            if (!probe.matches(title, artist, album)) continue

            val collectionId = item.optLong("collectionId", 0L)
            val trackViewUrl = item.optString("collectionViewUrl")
                .ifBlank { item.optString("trackViewUrl") }
                .trim()
            val fallbackImg = item.optString("artworkUrl100")
                .takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "1000x1000bb")

            // 1) Try iTunes lookup with extend=editorialVideo
            if (collectionId > 0L) {
                val lookupBody = executeGet(
                    client,
                    "https://itunes.apple.com/lookup?id=$collectionId&entity=album&extend=editorialVideo"
                )
                if (!lookupBody.isNullOrBlank()) {
                    extractAppleEditorialVideo(lookupBody)?.let { (tallUrl, squareUrl) ->
                        val primary = squareUrl ?: tallUrl ?: ""
                        if (primary.isNotBlank() || !tallUrl.isNullOrBlank()) {
                            return CanvasArtwork(
                                videoUrl = primary,
                                fallbackUrl = squareUrl?.takeIf { it != primary },
                                portraitUrl = tallUrl,
                                fallbackImageUrl = fallbackImg,
                                matchedTitle = candTitle,
                                matchedArtist = candArtist,
                                album = candAlbum,
                                source = CanvasSource.APPLE,
                                sourceLabel = "Apple Music"
                            )
                        }
                    }
                }
            }

            // 2) Try Apple Music web serialized JSON metadata for editorialVideo
            if (trackViewUrl.startsWith("https://music.apple.com/")) {
                val html = executeGet(
                    client = client,
                    url = trackViewUrl,
                    accept = "text/html,application/xhtml+xml"
                )
                if (!html.isNullOrBlank()) {
                    extractAppleEditorialVideo(html)?.let { (tallUrl, squareUrl) ->
                        val primary = squareUrl ?: tallUrl ?: ""
                        if (primary.isNotBlank() || !tallUrl.isNullOrBlank()) {
                            return CanvasArtwork(
                                videoUrl = primary,
                                fallbackUrl = squareUrl?.takeIf { it != primary },
                                portraitUrl = tallUrl,
                                fallbackImageUrl = fallbackImg,
                                matchedTitle = candTitle,
                                matchedArtist = candArtist,
                                album = candAlbum,
                                source = CanvasSource.APPLE,
                                sourceLabel = "Apple Music"
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractAppleEditorialVideo(payload: String): Pair<String?, String?>? {
        if (!payload.contains("editorialVideo", ignoreCase = true) &&
            !payload.contains("motionDetail", ignoreCase = true)
        ) {
            return null
        }
        val tallRegex = Regex(
            """"motionDetailTall"\s*:\s*\{[^}]*?"video"\s*:\s*"(https:[^"]+)"""",
            RegexOption.IGNORE_CASE
        )
        val squareRegex = Regex(
            """"motionDetailSquare"\s*:\s*\{[^}]*?"video"\s*:\s*"(https:[^"]+)"""",
            RegexOption.IGNORE_CASE
        )
        val genericVideoRegex = Regex(
            """"editorialVideo"\s*:\s*\{.*?"video"\s*:\s*"(https:[^"]+)"""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val tallUrl = tallRegex.find(payload)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()
        val squareUrl = squareRegex.find(payload)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()
            ?: genericVideoRegex.find(payload)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()

        return if (!tallUrl.isNullOrBlank() || !squareUrl.isNullOrBlank()) {
            tallUrl to squareUrl
        } else {
            null
        }
    }

    private fun queryTidalVideoCoverCanvas(
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        val client = configuredClient ?: defaultClient
        val cleanArtist = CanvasArtwork.normalizePrimaryArtist(artist).ifBlank { artist }
        val cleanTitle = CanvasArtwork.normalizeCanvasToken(title).ifBlank { title }
        val encoded = Uri.encode("$cleanTitle $cleanArtist")

        val endpoints = listOf(
            "https://api.tidal.com/v1/search/tracks?query=$encoded&limit=6&countryCode=US",
            "https://tidal.401658.xyz/search/?s=$encoded"
        )

        for (url in endpoints) {
            val body = executeGet(
                client = client,
                url = url,
                headers = mapOf("x-tidal-token" to "CzET4vdadNUFQ5JU")
            ) ?: continue

            val items = parseTidalItems(body) ?: continue
            for (i in 0 until items.length()) {
                val trackObj = items.optJSONObject(i) ?: continue
                val candTitle = trackObj.optString("title").trim()
                val candArtist = trackObj.optJSONArray("artists")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    ?.trim()
                    ?: trackObj.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
                val albumObj = trackObj.optJSONObject("album")
                val candAlbum = albumObj?.optString("title")?.trim()?.ifBlank { null }
                val videoCover = albumObj?.optString("videoCover")?.trim().orEmpty()
                if (videoCover.length < 8) continue

                val uuidPath = videoCover.replace("-", "/")
                val mp4Url = "https://resources.tidal.com/videos/$uuidPath/1280x1280.mp4"
                val coverUuid = albumObj?.optString("cover")?.trim().orEmpty()
                val coverImg = if (coverUuid.isNotBlank()) {
                    "https://resources.tidal.com/images/${coverUuid.replace("-", "/")}/1280x1280.jpg"
                } else null

                val candidate = CanvasArtwork(
                    videoUrl = mp4Url,
                    fallbackUrl = null,
                    portraitUrl = null,
                    fallbackImageUrl = coverImg,
                    matchedTitle = candTitle,
                    matchedArtist = candArtist,
                    album = candAlbum,
                    source = CanvasSource.TIDAL,
                    sourceLabel = "Tidal Motion"
                )
                if (candidate.matches(title, artist, album)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun parseTidalItems(body: String): JSONArray? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return root.optJSONArray("items")
            ?: root.optJSONObject("data")?.optJSONArray("items")
            ?: root.optJSONObject("tracks")?.optJSONArray("items")
    }

    private fun queryCommunityCanvasIndex(
        videoId: String,
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        val client = configuredClient ?: defaultClient
        val encodedTitle = Uri.encode(CanvasArtwork.normalizeCanvasToken(title))
        val encodedArtist = Uri.encode(CanvasArtwork.normalizePrimaryArtist(artist))
        val url = "https://canvas.paxsenix.org/api/canvas?title=$encodedTitle&artist=$encodedArtist&yt=$videoId"
        val body = executeGet(client, url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val canvasUrl = json.optString("canvasUrl")
            .ifBlank { json.optString("url") }
            .ifBlank { json.optString("videoUrl") }
            .trim()
        if (!canvasUrl.startsWith("https://")) return null

        val candTitle = json.optString("title").ifBlank { title }
        val candArtist = json.optString("artist").ifBlank { artist }
        val candAlbum = json.optString("album").trim().ifBlank { null }
        val candidate = CanvasArtwork(
            videoUrl = canvasUrl,
            portraitUrl = canvasUrl,
            matchedTitle = candTitle,
            matchedArtist = candArtist,
            album = candAlbum,
            source = CanvasSource.OTHER,
            sourceLabel = "Community Canvas"
        )
        return candidate.takeIf { it.matches(title, artist, album) }
    }

    private fun queryItunesMusicVideo(
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        val client = configuredClient ?: defaultClient
        val cleanArtist = CanvasArtwork.normalizePrimaryArtist(artist).ifBlank { artist }
        val cleanTitle = CanvasArtwork.normalizeCanvasToken(title).ifBlank { title }
        val term = Uri.encode("$cleanTitle $cleanArtist")

        val url = "https://itunes.apple.com/search?term=$term&entity=musicVideo&limit=5"
        val body = executeGet(client, url) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val previewUrl = item.optString("previewUrl").trim()
            if (!previewUrl.startsWith("https://") ||
                (!previewUrl.contains(".m4v", ignoreCase = true) && !previewUrl.contains(".mp4", ignoreCase = true))
            ) {
                continue
            }
            val candidateTitle = item.optString("trackName").trim()
            val candidateArtist = item.optString("artistName").trim()
            val fallbackImg = item.optString("artworkUrl100")
                .takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "600x600bb")
            val candidate = CanvasArtwork(
                videoUrl = previewUrl,
                fallbackUrl = null,
                portraitUrl = null,
                fallbackImageUrl = fallbackImg,
                matchedTitle = candidateTitle,
                matchedArtist = candidateArtist,
                album = null,
                source = CanvasSource.OTHER,
                sourceLabel = "Motion Canvas"
            )
            if (candidate.matches(title, artist, album)) {
                return candidate
            }
        }
        return null
    }

    private fun executeGet(
        client: OkHttpClient,
        url: String,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): String? {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun String.unescapeJsonUrl(): String {
        return replace("\\/", "/").replace("\\u0026", "&")
    }
}
