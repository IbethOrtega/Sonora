package com.example.data.network

import android.net.Uri
import com.example.model.CanvasArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CanvasRepository {
    private const val MAX_CACHE_SIZE = 64
    private const val NEGATIVE_TTL_MS = 20 * 60_000L

    @Volatile
    private var configuredClient: OkHttpClient? = null

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
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
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
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
        artist: String
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || videoId.startsWith("local_") || title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        positiveCache[videoId]?.let { cached ->
            if (cached.matches(title, artist)) return@withContext cached
        }

        val negExpiresAt = negativeCache[videoId]
        if (negExpiresAt != null && System.currentTimeMillis() < negExpiresAt) {
            return@withContext null
        }

        val resolved = queryAnimatedEditorialCanvas(title = title, artist = artist)
        if (resolved != null && resolved.matches(title, artist)) {
            positiveCache[videoId] = resolved
            negativeCache.remove(videoId)
            resolved
        } else {
            negativeCache[videoId] = System.currentTimeMillis() + NEGATIVE_TTL_MS
            null
        }
    }

    private fun queryAnimatedEditorialCanvas(title: String, artist: String): CanvasArtwork? {
        val client = configuredClient ?: defaultClient
        val cleanArtist = CanvasArtwork.normalizePrimaryArtist(artist).ifBlank { artist }
        val cleanTitle = CanvasArtwork.normalizeCanvasToken(title).ifBlank { title }
        val term = Uri.encode("$cleanTitle $cleanArtist")

        // Query musicVideo first for official vertical/square motion preview loops, then album/song metadata
        val url = "https://itunes.apple.com/search?term=$term&entity=musicVideo&limit=5"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                val results = JSONObject(body).optJSONArray("results") ?: return@use null
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val previewUrl = item.optString("previewUrl").trim()
                    if (!previewUrl.startsWith("https://") || !previewUrl.contains(".m4v", ignoreCase = true) && !previewUrl.contains(".mp4", ignoreCase = true)) {
                        continue
                    }
                    val candidateTitle = item.optString("trackName").trim()
                    val candidateArtist = item.optString("artistName").trim()
                    val fallbackImg = item.optString("artworkUrl100")
                        .takeIf { it.isNotBlank() }
                        ?.replace("100x100bb", "600x600bb")
                    val candidate = CanvasArtwork(
                        videoUrl = previewUrl,
                        fallbackImageUrl = fallbackImg,
                        matchedTitle = candidateTitle,
                        matchedArtist = candidateArtist,
                        sourceLabel = "Motion Canvas"
                    )
                    if (candidate.matches(title, artist)) {
                        return@use candidate
                    }
                }
                null
            }
        }.getOrNull()
    }
}
