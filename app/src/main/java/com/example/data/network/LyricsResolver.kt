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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Resolves word-synced / time-synced lyrics from local .lrc sidecars, BetterLyrics (TTML),
 * PaxSenix, LRCLIB, KuGou, lrc.red, and YouTube Music plain lyrics as a fallback.
 * If any external provider fails or times out, resolution continues silently to the next source.
 */
class LyricsResolver(
    httpClient: OkHttpClient,
    private val context: Context? = null
) {
    private val client = httpClient.newBuilder()
        .build()

    private val cache = ConcurrentHashMap<String, TrackLyrics>()
    private val providerCache = ConcurrentHashMap<String, TrackLyrics>()

    fun invalidate(videoId: String) {
        cache.remove(videoId)
        val prefix = "$videoId|"
        providerCache.keys.filter { it.startsWith(prefix) }.forEach { providerCache.remove(it) }
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
                val betterDeferred = async {
                    withTimeoutOrNull(3_600L) { fetchFromBetterLyrics(track) }
                }
                val paxDeferred = async {
                    withTimeoutOrNull(3_600L) { fetchFromPaxSenix(track) }
                }
                val lrcLibDeferred = async {
                    withTimeoutOrNull(4_200L) { fetchFromLrcLib(track) }
                }
                val kuGouDeferred = async {
                    withTimeoutOrNull(4_000L) { fetchFromKuGou(track) }
                }
                val lrcRedDeferred = async {
                    withTimeoutOrNull(3_400L) { fetchFromLrcRed(track) }
                }
                val ytDeferred = async {
                    withTimeoutOrNull(3_800L) { runCatching { ytPlainLyricsFetcher() }.getOrNull() }
                }

                fun cancelPending() {
                    betterDeferred.cancel()
                    paxDeferred.cancel()
                    lrcLibDeferred.cancel()
                    kuGouDeferred.cancel()
                    lrcRedDeferred.cancel()
                    ytDeferred.cancel()
                }

                // Prefer word-by-word synced sources first
                val betterResult = betterDeferred.await()?.also {
                    providerCache["${track.videoId}|${it.sourceLabel}"] = it
                }
                if (betterResult != null && betterResult.isWordSynced()) {
                    cancelPending()
                    return@coroutineScope betterResult
                }

                val paxResult = paxDeferred.await()?.also {
                    providerCache["${track.videoId}|${it.sourceLabel}"] = it
                }
                if (paxResult != null && paxResult.isWordSynced()) {
                    cancelPending()
                    return@coroutineScope paxResult
                }

                val lrcResult = lrcLibDeferred.await()?.also {
                    providerCache["${track.videoId}|${it.sourceLabel}"] = it
                }
                if (lrcResult != null && lrcResult.isWordSynced()) {
                    cancelPending()
                    return@coroutineScope lrcResult
                }

                val kuGouResult = kuGouDeferred.await()?.also {
                    providerCache["${track.videoId}|${it.sourceLabel}"] = it
                }
                if (kuGouResult != null && kuGouResult.isWordSynced()) {
                    cancelPending()
                    return@coroutineScope kuGouResult
                }

                val lrcRedResult = lrcRedDeferred.await()?.also {
                    providerCache["${track.videoId}|${it.sourceLabel}"] = it
                }
                if (lrcRedResult != null && lrcRedResult.isWordSynced()) {
                    cancelPending()
                    return@coroutineScope lrcRedResult
                }

                // Otherwise prefer any line-synced source in order
                val syncedCandidate = listOfNotNull(
                    betterResult,
                    paxResult,
                    lrcResult,
                    kuGouResult,
                    lrcRedResult
                ).firstOrNull { it.isSynced && it.lines.isNotEmpty() }

                if (syncedCandidate != null) {
                    ytDeferred.cancel()
                    return@coroutineScope syncedCandidate
                }

                // Plain lyrics fallback: YouTube Music -> LRCLIB/others
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

                listOfNotNull(betterResult, paxResult, lrcResult, kuGouResult, lrcRedResult)
                    .firstOrNull { it.lines.isNotEmpty() }
            }
        }

        if (resolved != null) {
            cache[track.videoId] = resolved
            providerCache["${track.videoId}|${resolved.sourceLabel}"] = resolved
        }
        return resolved
    }

    private fun TrackLyrics.isWordSynced(): Boolean =
        isSynced && lines.any { it.words.isNotEmpty() }

    private fun loadLocalLrcFile(track: Track): TrackLyrics? {
        val lrcUriStr = track.localLrcUri ?: return null
        val ctx = context ?: return null
        return runCatching {
            val raw = ctx.contentResolver.openInputStream(Uri.parse(lrcUriStr))?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }.orEmpty()
            if (raw.isBlank()) return@runCatching null
            val ttmlParsed = if (raw.contains("<tt", ignoreCase = true) || raw.contains("<p ", ignoreCase = true)) {
                parseTtmlLyrics(raw)
            } else emptyList()
            if (ttmlParsed.isNotEmpty()) {
                return@runCatching TrackLyrics(
                    lines = ttmlParsed,
                    isSynced = true,
                    sourceLabel = "Archivo .ttml"
                )
            }
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

    private suspend fun fetchFromBetterLyrics(track: Track): TrackLyrics? {
        val cleanTitle = cleanTitle(track.title)
        val primaryArtist = extractPrimaryArtist(track.artist)
        if (cleanTitle.isBlank() || primaryArtist.isBlank()) return null
        val durSec = (track.durationMs / 1000L).toInt()
        val url = buildString {
            append("https://lyrics-api.boidu.dev/getLyrics?s=")
            append(URLEncoder.encode(cleanTitle, "UTF-8"))
            append("&a=")
            append(URLEncoder.encode(primaryArtist, "UTF-8"))
            if (durSec in 20..1500) append("&d=").append(durSec)
        }
        val body = getText(url) ?: return null
        val trimmed = body.trim()
        if (trimmed.isBlank()) return null

        if (trimmed.startsWith("<")) {
            val lines = parseTtmlLyrics(trimmed)
            if (lines.isNotEmpty()) {
                return TrackLyrics(lines = lines, isSynced = true, sourceLabel = "BetterLyrics")
            }
        } else if (trimmed.startsWith("{")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            val ttml = json.optString("ttml").takeIf { !json.isNull("ttml") }.orEmpty()
            if (ttml.isNotBlank()) {
                val lines = parseTtmlLyrics(ttml)
                if (lines.isNotEmpty()) {
                    return TrackLyrics(lines = lines, isSynced = true, sourceLabel = "BetterLyrics")
                }
            }
            val rawLyrics = json.optString("lyrics").takeIf { !json.isNull("lyrics") }.orEmpty()
            if (rawLyrics.isNotBlank()) {
                val lines = if (rawLyrics.trimStart().startsWith("<")) parseTtmlLyrics(rawLyrics) else parseLrcLyrics(rawLyrics)
                if (lines.isNotEmpty()) {
                    return TrackLyrics(lines = lines, isSynced = true, sourceLabel = "BetterLyrics")
                }
            }
        }
        return null
    }

    private suspend fun fetchFromPaxSenix(track: Track): TrackLyrics? {
        val cleanTitle = cleanTitle(track.title)
        val primaryArtist = extractPrimaryArtist(track.artist)
        if (cleanTitle.isBlank()) return null

        val query = listOf(cleanTitle, primaryArtist)
            .filter { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
            .joinToString(" ")

        val urls = buildList {
            if (query.isNotBlank()) {
                add("https://lyrics.paxsenix.org/apple-music/lyrics?q=${URLEncoder.encode(query, "UTF-8")}")
            }
            if (track.videoId.isNotBlank() && !track.isLocal) {
                add("https://lyrics.paxsenix.org/ytm/lyrics?id=${URLEncoder.encode(track.videoId, "UTF-8")}")
            }
        }

        for (url in urls) {
            val raw = getText(url)?.trim() ?: continue
            if (raw.isBlank()) continue
            if (raw.startsWith("<")) {
                val ttml = parseTtmlLyrics(raw)
                if (ttml.isNotEmpty()) {
                    return TrackLyrics(lines = ttml, isSynced = true, sourceLabel = "PaxSenix")
                }
                continue
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            // Verify title/artist if metadata is provided
            val metaTitle = obj.optString("trackName").ifBlank { obj.optString("title") }
            val metaArtist = obj.optString("artistName").ifBlank { obj.optString("artist") }
            if (metaTitle.isNotBlank() && !titlesCompatible(cleanTitle, metaTitle)) continue
            if (metaArtist.isNotBlank() && primaryArtist.isNotBlank() && !artistsCompatible(primaryArtist, metaArtist)) continue

            val ttml = obj.optString("ttml").takeIf { !obj.isNull("ttml") }.orEmpty()
            if (ttml.isNotBlank()) {
                val parsed = parseTtmlLyrics(ttml)
                if (parsed.isNotEmpty()) {
                    return TrackLyrics(lines = parsed, isSynced = true, sourceLabel = "PaxSenix")
                }
            }

            val elrc = obj.optString("elrc").ifBlank { obj.optString("lrc") }
            if (elrc.isNotBlank()) {
                val parsed = parseLrcLyrics(elrc)
                if (parsed.isNotEmpty()) {
                    return TrackLyrics(lines = parsed, isSynced = true, sourceLabel = "PaxSenix")
                }
            }

            val jsonLines = obj.optJSONArray("lyrics") ?: obj.optJSONArray("content")
            if (jsonLines != null) {
                val parsed = parsePaxLineArray(jsonLines)
                if (parsed.isNotEmpty()) {
                    return TrackLyrics(lines = parsed, isSynced = true, sourceLabel = "PaxSenix")
                }
            }
        }
        return null
    }

    private fun parsePaxLineArray(arr: JSONArray): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val startMs = normalizeFlexTimestampMs(
                item.optDouble("timestamp", Double.NaN).takeIf { !it.isNaN() }
                    ?: item.optDouble("startTimeMs", Double.NaN).takeIf { !it.isNaN() }
                    ?: item.optDouble("start", Double.NaN).takeIf { !it.isNaN() }
                    ?: continue
            )
            val wordsArr = item.optJSONArray("text")
                ?: item.optJSONArray("syllabus")
                ?: item.optJSONArray("words")
            val words = mutableListOf<LyricWord>()
            val lineTextBuilder = StringBuilder()

            if (wordsArr != null) {
                for (w in 0 until wordsArr.length()) {
                    val wObj = wordsArr.optJSONObject(w) ?: continue
                    val wText = wObj.optString("text").ifBlank { wObj.optString("word") }
                    if (wText.isEmpty()) continue
                    val wStart = normalizeFlexTimestampMs(
                        wObj.optDouble("timestamp", Double.NaN).takeIf { !it.isNaN() }
                            ?: wObj.optDouble("startTimeMs", Double.NaN).takeIf { !it.isNaN() }
                            ?: wObj.optDouble("start", startMs.toDouble())
                    )
                    val wEnd = normalizeFlexTimestampMs(
                        wObj.optDouble("endtime", Double.NaN).takeIf { !it.isNaN() }
                            ?: wObj.optDouble("endTimeMs", Double.NaN).takeIf { !it.isNaN() }
                            ?: wObj.optDouble("end", (wStart + 450L).toDouble())
                    ).coerceAtLeast(wStart + 60L)
                    val needsSpace = w < wordsArr.length() - 1 && !wText.endsWith(" ") && !wObj.optBoolean("part", false)
                    val rendered = if (needsSpace) "$wText " else wText
                    words.add(LyricWord(startMs = wStart, endMs = wEnd, text = rendered))
                    lineTextBuilder.append(rendered)
                }
            }
            val text = lineTextBuilder.toString().trim().ifBlank {
                item.optString("text").ifBlank { item.optString("line") }.trim()
            }
            if (text.isNotBlank()) {
                result.add(LyricLine(timeMs = startMs, text = text, words = words))
            }
        }
        return result.sortedBy { it.timeMs }
    }

    private suspend fun fetchFromLrcRed(track: Track): TrackLyrics? {
        val cleanTitle = cleanTitle(track.title)
        val primaryArtist = extractPrimaryArtist(track.artist)
        if (cleanTitle.isBlank()) return null

        val url = buildString {
            append("https://lrc.red/api/lyrics?title=")
            append(URLEncoder.encode(cleanTitle, "UTF-8"))
            if (primaryArtist.isNotBlank() && !primaryArtist.equals("YouTube Music", ignoreCase = true)) {
                append("&artist=").append(URLEncoder.encode(primaryArtist, "UTF-8"))
            }
        }
        val raw = getText(url)?.trim() ?: return null
        if (raw.isBlank()) return null

        if (raw.startsWith("{")) {
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val candTitle = json.optString("title").ifBlank { json.optString("trackName") }
            val candArtist = json.optString("artist").ifBlank { json.optString("artistName") }
            if (candTitle.isNotBlank() && !titlesCompatible(cleanTitle, candTitle)) return null
            if (candArtist.isNotBlank() && primaryArtist.isNotBlank() && !artistsCompatible(primaryArtist, candArtist)) return null

            val syncedRaw = json.optString("syncedLyrics")
                .ifBlank { json.optString("elrc") }
                .ifBlank { json.optString("lrc") }
                .ifBlank { json.optString("lyrics") }
            if (syncedRaw.isNotBlank()) {
                val parsed = parseLrcLyrics(syncedRaw)
                if (parsed.isNotEmpty()) {
                    return TrackLyrics(lines = parsed, isSynced = true, sourceLabel = "lrc.red")
                }
            }
        } else if (raw.contains("[")) {
            val parsed = parseLrcLyrics(raw)
            if (parsed.isNotEmpty()) {
                return TrackLyrics(lines = parsed, isSynced = true, sourceLabel = "lrc.red")
            }
        }
        return null
    }

    private suspend fun fetchFromLrcLib(track: Track): TrackLyrics? {
        val cleanTitle = cleanTitle(track.title)
        val primaryArtist = extractPrimaryArtist(track.artist)
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
                    sourceLabel = "LRCLIB"
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
                    sourceLabel = "LRCLIB"
                )
            }
        }
        return null
    }

    private suspend fun fetchFromKuGou(track: Track): TrackLyrics? {
        val cleanTitle = cleanTitle(track.title)
        val primaryArtist = extractPrimaryArtist(track.artist)
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
                sourceLabel = "KuGou"
            )
        }
        return null
    }

    private fun cleanTitle(raw: String): String =
        CatalogAudioFallback.stripDecorations(raw).ifBlank { raw }.trim()

    private fun extractPrimaryArtist(raw: String): String =
        raw.replace(" - Topic", "", ignoreCase = true)
            .split(",", "&", " feat.", " ft.", " x ")
            .firstOrNull()
            ?.trim()
            .orEmpty()

    private fun titlesCompatible(target: String, candidate: String): Boolean {
        val t = CatalogAudioFallback.normalizeForMatch(CatalogAudioFallback.stripDecorations(target))
        val c = CatalogAudioFallback.normalizeForMatch(CatalogAudioFallback.stripDecorations(candidate))
        return t.isNotBlank() && c.isNotBlank() && (t == c || t.contains(c) || c.contains(t))
    }

    private fun artistsCompatible(target: String, candidate: String): Boolean {
        val t = CatalogAudioFallback.normalizeForMatch(target)
        val c = CatalogAudioFallback.normalizeForMatch(candidate)
        return t.isNotBlank() && c.isNotBlank() && (t == c || c.contains(t) || t.contains(c))
    }

    private fun normalizeFlexTimestampMs(raw: Double): Long {
        if (raw.isNaN() || raw < 0.0) return 0L
        // Treat small fractional values below 1500 as seconds unless already ms
        return if (raw < 1_500.0 && raw % 1.0 != 0.0) {
            (raw * 1000.0).toLong()
        } else {
            raw.toLong()
        }
    }

    private suspend fun getText(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "SonoraMusicAndroid/1.0")
            .header("Accept", "application/json, application/ttml+xml, text/xml, text/plain")
            .get()
            .build()
        client.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    private suspend fun getJson(url: String): JSONObject? = runCatching {
        val raw = getText(url)?.trim().orEmpty()
        if (!raw.startsWith("{")) return@runCatching null
        JSONObject(raw)
    }.getOrNull()

    private suspend fun getJsonArray(url: String): JSONArray? = runCatching {
        val raw = getText(url)?.trim().orEmpty()
        if (!raw.startsWith("[")) return@runCatching null
        JSONArray(raw)
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

        internal fun parseTtmlTimeMs(raw: String?): Long? {
            val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (v.endsWith("ms", ignoreCase = true)) {
                return v.dropLast(2).trim().toDoubleOrNull()?.toLong()
            }
            if (v.endsWith("s", ignoreCase = true)) {
                return v.dropLast(1).trim().toDoubleOrNull()?.let { (it * 1000.0).toLong() }
            }
            val parts = v.split(":")
            return when (parts.size) {
                1 -> parts[0].toDoubleOrNull()?.let { (it * 1000.0).toLong() }
                2 -> {
                    val m = parts[0].toLongOrNull() ?: return null
                    val s = parts[1].toDoubleOrNull() ?: return null
                    m * 60_000L + (s * 1000.0).toLong()
                }
                3 -> {
                    val h = parts[0].toLongOrNull() ?: return null
                    val m = parts[1].toLongOrNull() ?: return null
                    val s = parts[2].toDoubleOrNull() ?: return null
                    h * 3_600_000L + m * 60_000L + (s * 1000.0).toLong()
                }
                else -> null
            }
        }

        internal fun parseTtmlLyrics(ttml: String): List<LyricLine> {
            if (ttml.isBlank()) return emptyList()
            val lines = mutableListOf<LyricLine>()
            runCatching {
                val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
                val parser = factory.newPullParser()
                parser.setInput(StringReader(ttml))

                var insideP = false
                var pStartMs = 0L
                var pEndMs = 0L
                val pText = StringBuilder()
                val pWords = mutableListOf<LyricWord>()

                var insideSpan = false
                var spanStartMs: Long? = null
                var spanEndMs: Long? = null
                val spanText = StringBuilder()

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            val tag = parser.name.substringAfter(':').lowercase()
                            when (tag) {
                                "p" -> {
                                    insideP = true
                                    pStartMs = parseTtmlTimeMs(parser.getAttributeValue(null, "begin")) ?: 0L
                                    pEndMs = parseTtmlTimeMs(parser.getAttributeValue(null, "end")) ?: (pStartMs + 3_500L)
                                    pText.setLength(0)
                                    pWords.clear()
                                }
                                "span" -> if (insideP) {
                                    insideSpan = true
                                    spanStartMs = parseTtmlTimeMs(parser.getAttributeValue(null, "begin"))
                                    spanEndMs = parseTtmlTimeMs(parser.getAttributeValue(null, "end"))
                                    spanText.setLength(0)
                                }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val txt = parser.text.orEmpty()
                            if (insideSpan) {
                                spanText.append(txt)
                            } else if (insideP) {
                                if (txt.isNotEmpty()) {
                                    if (txt.isBlank() && pWords.isNotEmpty()) {
                                        val last = pWords.removeAt(pWords.lastIndex)
                                        pWords.add(last.copy(text = last.text + " "))
                                    }
                                    pText.append(txt)
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            val tag = parser.name.substringAfter(':').lowercase()
                            when (tag) {
                                "span" -> if (insideSpan) {
                                    val wTxt = spanText.toString()
                                    if (wTxt.isNotBlank()) {
                                        val sMs = spanStartMs ?: pStartMs
                                        val eMs = (spanEndMs ?: (sMs + 420L)).coerceAtLeast(sMs + 60L)
                                        pWords.add(LyricWord(startMs = sMs, endMs = eMs, text = wTxt))
                                        pText.append(wTxt)
                                    }
                                    insideSpan = false
                                    spanStartMs = null
                                    spanEndMs = null
                                    spanText.setLength(0)
                                }
                                "p" -> if (insideP) {
                                    val cleanLine = pText.toString().replace(Regex("\\s+"), " ").trim()
                                    if (cleanLine.isNotBlank()) {
                                        val resolvedStart = pWords.firstOrNull()?.startMs ?: pStartMs
                                        lines.add(
                                            LyricLine(
                                                timeMs = resolvedStart.coerceAtLeast(0L),
                                                text = cleanLine,
                                                words = pWords.toList()
                                            )
                                        )
                                    }
                                    insideP = false
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            return lines.sortedBy { it.timeMs }
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
