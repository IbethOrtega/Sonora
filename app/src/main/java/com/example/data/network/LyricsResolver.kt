package com.example.data.network

import android.content.Context
import android.net.Uri
import com.example.model.LyricLine
import com.example.model.LyricWord
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
 * Resolves word-synced / time-synced LRC lyrics from local .lrc sidecars, LRCLIB, KuGou,
 * and YouTube Music plain lyrics as a clean timed fallback.
 */
class LyricsResolver(
    httpClient: OkHttpClient,
    private val context: Context? = null
) {
    private val client = httpClient.newBuilder()
        .build()

    private val cache = ConcurrentHashMap<String, TrackLyrics>()

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    suspend fun resolveLyrics(
        track: Track,
        ytPlainLyricsFetcher: suspend () -> String?
    ): TrackLyrics? {
        cache[track.videoId]?.let { return it }

        val resolved = withContext(Dispatchers.IO) {
            // 1. Local .lrc sidecar file (for local SAF audio tracks)
            val localLrc = loadLocalLrcFile(track)
            if (localLrc != null && localLrc.lines.isNotEmpty()) {
                return@withContext localLrc
            }

            coroutineScope {
                val lrcLibDeferred = async {
                    withTimeoutOrNull(4_500L) { fetchFromLrcLib(track) }
                }
                val kuGouDeferred = async {
                    withTimeoutOrNull(4_200L) { fetchFromKuGou(track) }
                }
                val ytDeferred = async {
                    withTimeoutOrNull(4_000L) { runCatching { ytPlainLyricsFetcher() }.getOrNull() }
                }

                val lrcResult = lrcLibDeferred.await()
                val lrcHasWordSync = lrcResult?.lines?.any { it.words.isNotEmpty() } == true
                if (lrcResult != null && lrcResult.isSynced && lrcHasWordSync) {
                    kuGouDeferred.cancel()
                    ytDeferred.cancel()
                    return@coroutineScope lrcResult
                }

                val kuGouResult = kuGouDeferred.await()
                val kuGouHasWordSync = kuGouResult?.lines?.any { it.words.isNotEmpty() } == true
                if (kuGouResult != null && kuGouResult.isSynced && kuGouHasWordSync) {
                    ytDeferred.cancel()
                    return@coroutineScope kuGouResult
                }

                if (lrcResult != null && lrcResult.isSynced) {
                    ytDeferred.cancel()
                    return@coroutineScope lrcResult
                }

                if (kuGouResult != null && kuGouResult.isSynced) {
                    ytDeferred.cancel()
                    return@coroutineScope kuGouResult
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

                kuGouResult?.takeIf { it.lines.isNotEmpty() }
            }
        }

        if (resolved != null) {
            cache[track.videoId] = resolved
        }
        return resolved
    }

    private fun loadLocalLrcFile(track: Track): TrackLyrics? {
        val lrcUriStr = track.localLrcUri ?: return null
        val ctx = context ?: return null
        return runCatching {
            val raw = ctx.contentResolver.openInputStream(Uri.parse(lrcUriStr))?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }.orEmpty()
            if (raw.isBlank()) return@runCatching null
            val parsed = parseLrcLyrics(raw)
            if (parsed.isNotEmpty()) {
                TrackLyrics(
                    lines = parsed,
                    isSynced = true,
                    sourceLabel = "Archivo .lrc"
                )
            } else {
                val distributed = distributePlainLyrics(raw, track.durationMs)
                if (distributed.isNotEmpty()) {
                    TrackLyrics(
                        lines = distributed,
                        isSynced = false,
                        sourceLabel = "Archivo .lrc"
                    )
                } else null
            }
        }.getOrNull()
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
            if (isAcceptableLyricsCandidate(json, cleanTitle, primaryArtist, durSec)) {
                parseLrcLibObject(json, track.durationMs)?.let { return it }
            }
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

            val score = scoreLyricsCandidate(obj, cleanTitle, primaryArtist, durSec, hasSynced = synced.isNotBlank())
            if (score > bestScore) {
                bestScore = score
                bestObj = obj
            }
        }
        val minRequiredScore = if (primaryArtist.isNotBlank() && !primaryArtist.equals("YouTube Music", ignoreCase = true)) {
            135
        } else {
            100
        }
        if (bestScore < minRequiredScore) return null
        return bestObj?.let { parseLrcLibObject(it, track.durationMs) }
    }

    private fun isAcceptableLyricsCandidate(
        obj: JSONObject,
        targetTitle: String,
        targetArtist: String,
        targetDurSec: Int
    ): Boolean {
        return scoreLyricsCandidate(obj, targetTitle, targetArtist, targetDurSec, hasSynced = true) >= 120
    }

    private fun scoreLyricsCandidate(
        obj: JSONObject,
        targetTitle: String,
        targetArtist: String,
        targetDurSec: Int,
        hasSynced: Boolean
    ): Int {
        val rawCandTitle = obj.optString("trackName")
        val rawCandArtist = obj.optString("artistName")
        val candTitle = CatalogAudioFallback.normalizeForMatch(CatalogAudioFallback.stripDecorations(rawCandTitle))
        val tTitle = CatalogAudioFallback.normalizeForMatch(CatalogAudioFallback.stripDecorations(targetTitle))
        if (candTitle.isBlank() || tTitle.isBlank()) return Int.MIN_VALUE

        val targetVersions = CatalogAudioFallback.extractVersionFamilies(CatalogAudioFallback.normalizeForMatch(targetTitle))
        val candVersions = CatalogAudioFallback.extractVersionFamilies(CatalogAudioFallback.normalizeForMatch(rawCandTitle))
        if (targetVersions != candVersions) return Int.MIN_VALUE

        val cDur = obj.optDouble("duration", 0.0).toInt()
        if (targetDurSec >= 45 && cDur > 0 && abs(cDur - targetDurSec) > 20) {
            return Int.MIN_VALUE
        }

        val tTokens = tTitle.split(' ').filter { it.length >= 2 }
        val cTokens = candTitle.split(' ').filter { it.length >= 2 }
        val titleScore = when {
            candTitle == tTitle -> 80
            tTokens.isNotEmpty() && cTokens.isNotEmpty() -> {
                val tInC = tTokens.count { tok -> " $candTitle ".contains(" $tok ") }.toFloat() / tTokens.size.toFloat()
                val cInT = cTokens.count { tok -> " $tTitle ".contains(" $tok ") }.toFloat() / cTokens.size.toFloat()
                if (tInC < 0.75f || cInT < 0.55f) return Int.MIN_VALUE
                (tInC * 55f).toInt()
            }
            else -> return Int.MIN_VALUE
        }

        val tArtist = CatalogAudioFallback.normalizeForMatch(targetArtist)
        val cArtist = CatalogAudioFallback.normalizeForMatch(rawCandArtist)
        var artistScore = 0
        if (tArtist.isNotBlank() && tArtist != "youtube music" && tArtist != "artista desconocido") {
            val matched = cArtist.contains(tArtist) || tArtist.contains(cArtist)
            if (!matched) return Int.MIN_VALUE
            artistScore = 50
        }

        val syncBonus = if (hasSynced) 30 else 0
        val durBonus = if (targetDurSec > 0 && cDur > 0) (20 - abs(cDur - targetDurSec)).coerceAtLeast(0) else 0
        return titleScore + artistScore + syncBonus + durBonus
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

    private suspend fun fetchFromKuGou(track: Track): TrackLyrics? {
        val cleanTitle = CatalogAudioFallback.stripDecorations(track.title).ifBlank { track.title }.trim()
        val primaryArtist = track.artist
            .replace(" - Topic", "", ignoreCase = true)
            .split(",", "&", " feat.", " ft.", " x ")
            .firstOrNull()
            ?.trim()
            .orEmpty()
        if (cleanTitle.isBlank()) return null

        val keyword = listOf(cleanTitle, primaryArtist)
            .filter { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
            .joinToString(" - ")
        val durMsParam = if (track.durationMs > 10_000L) "&duration=${track.durationMs}" else ""
        val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" +
            URLEncoder.encode(keyword, "UTF-8") + durMsParam

        val searchJson = getJson(searchUrl) ?: return null
        val candidates = searchJson.optJSONArray("candidates") ?: return null
        val targetDurSec = (track.durationMs / 1000L).toInt()
        val tTitle = CatalogAudioFallback.normalizeForMatch(cleanTitle)
        val tArtist = CatalogAudioFallback.normalizeForMatch(primaryArtist)

        var chosenId: String? = null
        var chosenKey: String? = null
        var bestScore = Int.MIN_VALUE

        for (i in 0 until candidates.length().coerceAtMost(8)) {
            val cand = candidates.optJSONObject(i) ?: continue
            val id = cand.optString("id")
            val accessKey = cand.optString("accesskey")
            if (id.isBlank() || accessKey.isBlank()) continue

            val songName = cand.optString("song")
            val singer = cand.optString("singer")
            val durSec = (cand.optLong("duration", 0L) / 1000L).toInt()
            if (targetDurSec >= 45 && durSec > 0 && abs(durSec - targetDurSec) > 22) continue

            val cTitle = CatalogAudioFallback.normalizeForMatch(CatalogAudioFallback.stripDecorations(songName))
            val cArtist = CatalogAudioFallback.normalizeForMatch(singer)
            if (cTitle.isBlank()) continue

            val titleMatch = when {
                cTitle == tTitle -> 80
                cTitle.contains(tTitle) || tTitle.contains(cTitle) -> 55
                else -> -200
            }
            if (titleMatch < 0) continue

            val artistMatch = if (tArtist.isNotBlank() && tArtist != "youtube music" && tArtist != "artista desconocido") {
                if (cArtist.contains(tArtist) || tArtist.contains(cArtist)) 50 else -200
            } else {
                0
            }
            if (artistMatch < 0) continue

            val durBonus = if (targetDurSec > 0 && durSec > 0) (22 - abs(durSec - targetDurSec)).coerceAtLeast(0) else 0
            val score = titleMatch + artistMatch + durBonus
            if (score > bestScore) {
                bestScore = score
                chosenId = id
                chosenKey = accessKey
            }
        }

        if (chosenId == null || chosenKey == null) return null

        val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$chosenId&accesskey=$chosenKey&fmt=lrc&charset=utf8"
        val dlJson = getJson(dlUrl) ?: return null
        val b64Content = dlJson.optString("content").trim()
        if (b64Content.isBlank()) return null

        val lrcText = decodeBase64Utf8(b64Content)
        if (lrcText.isBlank()) return null

        val parsed = parseLrcLyrics(lrcText)
        if (parsed.isNotEmpty()) {
            return TrackLyrics(
                lines = parsed,
                isSynced = true,
                sourceLabel = "KuGou Sync"
            )
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
        private val WORD_TAG_REGEX = Regex("<(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?>")

        private fun parseTimestampMs(minStr: String, secStr: String, fracStr: String): Long {
            val min = minStr.toLongOrNull() ?: 0L
            val sec = secStr.toLongOrNull() ?: 0L
            val msPart = when (fracStr.length) {
                0 -> 0L
                1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                else -> fracStr.take(3).toLongOrNull() ?: 0L
            }
            return min * 60_000L + sec * 1_000L + msPart
        }

        private fun extractWordTimings(rawContent: String): List<LyricWord> {
            val wordMatches = WORD_TAG_REGEX.findAll(rawContent).toList()
            if (wordMatches.isEmpty()) return emptyList()

            val words = mutableListOf<LyricWord>()
            val prefix = rawContent.substring(0, wordMatches.first().range.first)
            val firstStart = parseTimestampMs(
                wordMatches.first().groupValues[1],
                wordMatches.first().groupValues[2],
                wordMatches.first().groupValues[3]
            )
            if (prefix.isNotEmpty() && prefix.isNotBlank()) {
                words.add(LyricWord(startMs = (firstStart - 300L).coerceAtLeast(0L), endMs = firstStart, text = prefix))
            }

            for (i in wordMatches.indices) {
                val m = wordMatches[i]
                val startMs = parseTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                val textStart = m.range.last + 1
                val textEnd = wordMatches.getOrNull(i + 1)?.range?.first ?: rawContent.length
                val nextStartMs = wordMatches.getOrNull(i + 1)?.let { next ->
                    parseTimestampMs(next.groupValues[1], next.groupValues[2], next.groupValues[3])
                } ?: (startMs + 650L)

                val segment = rawContent.substring(textStart, textEnd)
                if (segment.isNotEmpty()) {
                    words.add(
                        LyricWord(
                            startMs = startMs,
                            endMs = nextStartMs.coerceAtLeast(startMs + 60L),
                            text = segment
                        )
                    )
                }
            }
            return words
        }

        internal fun parseLrcLyrics(lrc: String): List<LyricLine> {
            val result = mutableListOf<LyricLine>()
            lrc.lineSequence().forEach { rawLine ->
                val matches = LRC_TAG_REGEX.findAll(rawLine).toList()
                if (matches.isEmpty()) return@forEach
                val withoutLineTags = rawLine.replace(LRC_TAG_REGEX, "").trim()
                if (withoutLineTags.isBlank()) return@forEach

                val words = if (matches.size == 1) extractWordTimings(withoutLineTags) else emptyList()
                val cleanText = withoutLineTags.replace(WORD_TAG_REGEX, "").trim()
                if (cleanText.isBlank()) return@forEach

                matches.forEach { m ->
                    val totalMs = parseTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    result.add(LyricLine(timeMs = totalMs, text = cleanText, words = words))
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

        internal fun decodeBase64Utf8(base64Input: String): String {
            val clean = base64Input.filterNot { it.isWhitespace() }
            if (clean.isEmpty()) return ""
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val lookup = IntArray(256) { -1 }
            alphabet.forEachIndexed { idx, c -> lookup[c.code] = idx }
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bitsCollected = 0
            for (ch in clean) {
                if (ch == '=') break
                val code = ch.code
                val value = if (code < 256) lookup[code] else -1
                if (value < 0) continue
                buffer = (buffer shl 6) or value
                bitsCollected += 6
                if (bitsCollected >= 8) {
                    bitsCollected -= 8
                    out.write((buffer shr bitsCollected) and 0xFF)
                }
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
