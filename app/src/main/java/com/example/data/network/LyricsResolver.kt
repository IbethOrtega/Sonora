package com.example.data.network

import com.example.model.LyricLine
import com.example.model.Track
import com.example.model.TrackLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Resolves time-synced LRC lyrics from LRCLIB and blends with YouTube Music plain lyrics
 * as a clean timed fallback so Apple Music-style line tracking always animates smoothly.
 */
class LyricsResolver(
    httpClient: OkHttpClient
) {
    private val client = httpClient.newBuilder()
        .build()

    private val cache = ConcurrentHashMap<String, TrackLyrics>()

    suspend fun resolveLyrics(
        track: Track,
        ytPlainLyricsFetcher: suspend () -> String?
    ): TrackLyrics? {
        cache[track.videoId]?.let { return it }

        val resolved = withContext(Dispatchers.IO) {
            coroutineScope {
                val syncedDeferred = async {
                    withTimeoutOrNull(4_500L) { fetchFromLrcLib(track) }
                }
                val ytDeferred = async {
                    withTimeoutOrNull(4_000L) { runCatching { ytPlainLyricsFetcher() }.getOrNull() }
                }

                val lrcResult = syncedDeferred.await()
                if (lrcResult != null && lrcResult.isSynced) {
                    return@coroutineScope lrcResult
                }

                val ytPlain = ytDeferred.await()
                if (!ytPlain.isNullOrBlank()) {
                    val lines = distributePlainLyrics(ytPlain, track.durationMs)
                    if (lines.isNotEmpty()) {
                        return@coroutineScope TrackLyrics(
                            lines = lines,
                            isSynced = false,
                            sourceLabel = "YouTube Music"
                        )
                    }
                }

                if (lrcResult != null && lrcResult.lines.isNotEmpty()) {
                    return@coroutineScope lrcResult
                }

                null
            }
        }

        if (resolved != null) {
            cache[track.videoId] = resolved
        }
        return resolved
    }

    private suspend fun fetchFromLrcLib(track: Track): TrackLyrics? {
        val cleanTitle = CatalogAudioFallback.stripDecorations(track.title).ifBlank { track.title }.trim()
        val primaryArtist = track.artist
            .replace(" - Topic", "", ignoreCase = true)
            .split(",", "&", " feat.", " ft.", " x ")
            .firstOrNull()
            ?.trim()
            .orEmpty()
        if (cleanTitle.isBlank()) return null

        val durSec = (track.durationMs / 1000L).toInt()
        val exactParams = buildString {
            append("track_name=").append(URLEncoder.encode(cleanTitle, "UTF-8"))
            if (primaryArtist.isNotBlank() && !primaryArtist.equals("YouTube Music", ignoreCase = true)) {
                append("&artist_name=").append(URLEncoder.encode(primaryArtist, "UTF-8"))
            }
            if (!track.album.isNullOrBlank()) {
                append("&album_name=").append(URLEncoder.encode(track.album, "UTF-8"))
            }
            if (durSec in 30..1200) {
                append("&duration=").append(durSec)
            }
        }

        getJson("https://lrclib.net/api/get?$exactParams")?.let { json ->
            parseLrcLibObject(json, track.durationMs)?.let { return it }
        }

        val searchQuery = listOf(cleanTitle, primaryArtist)
            .filter { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
            .joinToString(" ")
        val encodedSearch = URLEncoder.encode(searchQuery, "UTF-8")
        val arr = getJsonArray("https://lrclib.net/api/search?q=$encodedSearch") ?: return null

        var bestObj: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until arr.length().coerceAtMost(8)) {
            val obj = arr.optJSONObject(i) ?: continue
            val synced = obj.optString("syncedLyrics").takeIf { !obj.isNull("syncedLyrics") }.orEmpty()
            val plain = obj.optString("plainLyrics").takeIf { !obj.isNull("plainLyrics") }.orEmpty()
            if (synced.isBlank() && plain.isBlank()) continue

            var score = if (synced.isNotBlank()) 100 else 20
            val cDur = obj.optDouble("duration", 0.0).toInt()
            if (durSec > 0 && cDur > 0) {
                val diff = abs(cDur - durSec)
                score -= diff.coerceAtMost(60)
            }
            val cTitle = CatalogAudioFallback.normalizeForMatch(obj.optString("trackName"))
            val tTitle = CatalogAudioFallback.normalizeForMatch(cleanTitle)
            if (cTitle == tTitle) score += 50
            val cArtist = CatalogAudioFallback.normalizeForMatch(obj.optString("artistName"))
            val tArtist = CatalogAudioFallback.normalizeForMatch(primaryArtist)
            if (tArtist.isNotBlank() && (cArtist.contains(tArtist) || tArtist.contains(cArtist))) {
                score += 40
            }

            if (score > bestScore) {
                bestScore = score
                bestObj = obj
            }
        }
        return bestObj?.let { parseLrcLibObject(it, track.durationMs) }
    }

    private fun parseLrcLibObject(obj: JSONObject, durationMs: Long): TrackLyrics? {
        val syncedRaw = if (obj.isNull("syncedLyrics")) "" else obj.optString("syncedLyrics").trim()
        if (syncedRaw.isNotBlank()) {
            val parsed = parseLrcLyrics(syncedRaw)
            if (parsed.isNotEmpty()) {
                return TrackLyrics(
                    lines = parsed,
                    isSynced = true,
                    sourceLabel = "Letras sincronizadas"
                )
            }
        }
        val plainRaw = if (obj.isNull("plainLyrics")) "" else obj.optString("plainLyrics").trim()
        if (plainRaw.isNotBlank()) {
            val distributed = distributePlainLyrics(plainRaw, durationMs)
            if (distributed.isNotEmpty()) {
                return TrackLyrics(
                    lines = distributed,
                    isSynced = false,
                    sourceLabel = "Desplazamiento inteligente"
                )
            }
        }
        return null
    }

    private suspend fun getJson(url: String): JSONObject? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "SonoraMusicAndroid/1.0")
            .header("Accept", "application/json")
            .get()
            .build()
        client.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val raw = resp.body?.string().orEmpty().trim()
            if (!raw.startsWith("{")) return@runCatching null
            JSONObject(raw)
        }
    }.getOrNull()

    private suspend fun getJsonArray(url: String): JSONArray? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "SonoraMusicAndroid/1.0")
            .header("Accept", "application/json")
            .get()
            .build()
        client.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val raw = resp.body?.string().orEmpty().trim()
            if (!raw.startsWith("[")) return@runCatching null
            JSONArray(raw)
        }
    }.getOrNull()

    private suspend fun OkHttpClient.executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                    } else {
                        cont.resume(response)
                    }
                }
            })
        }

    companion object {
        private val LRC_TAG_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?]")

        internal fun parseLrcLyrics(lrc: String): List<LyricLine> {
            val result = mutableListOf<LyricLine>()
            lrc.lineSequence().forEach { rawLine ->
                val matches = LRC_TAG_REGEX.findAll(rawLine).toList()
                if (matches.isEmpty()) return@forEach
                val text = rawLine.replace(LRC_TAG_REGEX, "").trim()
                if (text.isBlank()) return@forEach
                matches.forEach { m ->
                    val min = m.groupValues[1].toLongOrNull() ?: 0L
                    val sec = m.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = m.groupValues[3]
                    val msPart = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    val totalMs = min * 60_000L + sec * 1_000L + msPart
                    result.add(LyricLine(timeMs = totalMs, text = text))
                }
            }
            return result.sortedBy { it.timeMs }
        }

        internal fun distributePlainLyrics(plainText: String, durationMs: Long): List<LyricLine> {
            val lines = plainText.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            if (lines.isEmpty()) return emptyList()
            val usableDuration = if (durationMs > 20_000L) durationMs else 200_000L
            val introOffset = (usableDuration * 0.06f).toLong().coerceAtMost(12_000L)
            val outroMargin = (usableDuration * 0.06f).toLong().coerceAtMost(14_000L)
            val span = (usableDuration - introOffset - outroMargin).coerceAtLeast(10_000L)
            val step = span / lines.size.coerceAtLeast(1)
            return lines.mapIndexed { idx, text ->
                LyricLine(
                    timeMs = introOffset + idx * step,
                    text = text
                )
            }
        }
    }
}
