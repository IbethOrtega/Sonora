package com.example.data.network

import android.util.Log
import com.example.model.AudioQuality
import com.example.model.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

data class TrackMetadataHint(
    val title: String,
    val artist: String,
    val durationMs: Long = 0L
)

/**
 * Fallback catalog audio resolver for environments where YouTube InnerTube or public mirrors
 * are rate-limited or bot-blocked (e.g., datacenter / emulator IPs).
 * Resolves clean ad-free progressive AAC / MP3 or unencrypted HLS streams via JioSaavn & SoundCloud.
 */
class CatalogAudioFallback(
    httpClient: OkHttpClient,
    private val browserUserAgent: String
) {
    private val client: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var cachedSoundCloudClientId: Pair<Long, String>? = null

    suspend fun warmup() {
        withTimeoutOrNull(3_000L) { ensureSoundCloudClientId() }
    }

    suspend fun fetchYouTubeOEmbedHint(videoId: String): TrackMetadataHint? = withTimeoutOrNull(2_500L) {
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
                .header("User-Agent", browserUserAgent)
                .get()
                .build()
            client.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val json = JSONObject(resp.body?.string().orEmpty())
                val title = json.optString("title").trim()
                val author = json.optString("author_name")
                    .replace(" - Topic", "", ignoreCase = true)
                    .trim()
                if (title.isBlank()) null else TrackMetadataHint(title = title, artist = author)
            }
        }.getOrNull()
    }

    suspend fun resolveStudioFallback(
        hint: TrackMetadataHint,
        quality: AudioQuality,
        failedSources: Set<String>,
        probePlayable: suspend (ResolvedStream) -> Boolean
    ): Pair<String, ResolvedStream>? = withTimeoutOrNull(8_500L) {
        coroutineScope {
            val saavnDeferred = async(Dispatchers.IO) {
                if ("saavn" in failedSources) null
                else resolveFromJioSaavn(hint, quality, probePlayable)
            }
            val scDeferred = async(Dispatchers.IO) {
                if ("soundcloud" in failedSources) null
                else resolveFromSoundCloud(hint, quality, probePlayable)
            }

            // Prefer studio progressive MP4 from JioSaavn when it matches cleanly; otherwise use SoundCloud
            val saavnStream = saavnDeferred.await()
            if (saavnStream != null) {
                scDeferred.cancel()
                return@coroutineScope "saavn" to saavnStream
            }

            val scStream = scDeferred.await()
            if (scStream != null) {
                return@coroutineScope "soundcloud" to scStream
            }
            null
        }
    }

    private suspend fun resolveFromJioSaavn(
        hint: TrackMetadataHint,
        quality: AudioQuality,
        probePlayable: suspend (ResolvedStream) -> Boolean
    ): ResolvedStream? {
        val queries = buildCandidateQueries(hint.title, hint.artist)
        for (query in queries) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val candidates = coroutineScope {
                val officialDeferred = async(Dispatchers.IO) {
                    fetchJioSaavnOfficial(encoded, hint, quality)
                }
                val mirrorDeferred = async(Dispatchers.IO) {
                    fetchJioSaavnMirror(encoded, hint, quality)
                }
                (officialDeferred.await() + mirrorDeferred.await())
                    .distinctBy { it.url }
                    .sortedByDescending { it.score }
            }

            for (cand in candidates.take(3)) {
                if (cand.score < SAAVN_MIN_ACCEPT_SCORE) break
                val stream = ResolvedStream(
                    url = cand.url,
                    mimeType = cand.mimeType,
                    bitrate = cand.bitrateBps,
                    userAgent = browserUserAgent,
                    expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60_000L,
                    codecLabel = cand.codecLabel
                )
                if (probePlayable(stream)) {
                    return stream
                }
                if (cand.url.endsWith("_320.mp4")) {
                    val downgraded = stream.copy(
                        url = cand.url.replace("_320.mp4", "_160.mp4"),
                        bitrate = 160_000,
                        codecLabel = "AAC 160 kbps"
                    )
                    if (probePlayable(downgraded)) {
                        return downgraded
                    }
                }
            }
        }
        return null
    }

    private data class ScoredCandidate(
        val score: Int,
        val url: String,
        val bitrateBps: Int,
        val mimeType: String,
        val codecLabel: String
    )

    private suspend fun fetchJioSaavnOfficial(
        encodedQuery: String,
        hint: TrackMetadataHint,
        quality: AudioQuality
    ): List<ScoredCandidate> {
        val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&includeMetaTags=1&n=10&q=$encodedQuery"
        val json = getJsonObject(url) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<ScoredCandidate>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val candTitle = item.optString("song").ifBlank { item.optString("title") }
            val candArtist = item.optString("primary_artists")
                .ifBlank { item.optString("singers") }
                .ifBlank {
                    item.optJSONObject("more_info")
                        ?.optJSONObject("artistMap")
                        ?.optJSONArray("primary_artists")
                        ?.optJSONObject(0)
                        ?.optString("name")
                        .orEmpty()
                }
            val durationSec = item.optString("duration").toIntOrNull()
                ?: item.optJSONObject("more_info")?.optString("duration")?.toIntOrNull()
                ?: item.optInt("duration", 0)
            val encrypted = item.optString("encrypted_media_url")
                .ifBlank { item.optJSONObject("more_info")?.optString("encrypted_media_url").orEmpty() }
            if (candTitle.isBlank() || encrypted.isBlank()) continue

            val score = scoreCatalogCandidate(
                targetTitle = hint.title,
                targetArtist = hint.artist,
                targetDurationMs = hint.durationMs,
                candTitle = decodeHtmlEntities(candTitle),
                candArtist = decodeHtmlEntities(candArtist),
                candDurationSec = durationSec,
                isVerified = true
            )
            if (score == Int.MIN_VALUE) continue

            val mediaUrl = decryptJioSaavnUrl(encrypted, quality) ?: continue
            val kbps = when (quality) {
                AudioQuality.MAX -> if (mediaUrl.contains("_320")) 320 else 160
                AudioQuality.HIGH -> 160
                AudioQuality.DATA_SAVER -> 96
            }
            list.add(
                ScoredCandidate(
                    score = score,
                    url = mediaUrl,
                    bitrateBps = kbps * 1000,
                    mimeType = "audio/mp4",
                    codecLabel = "AAC $kbps kbps"
                )
            )
        }
        return list
    }

    private suspend fun fetchJioSaavnMirror(
        encodedQuery: String,
        hint: TrackMetadataHint,
        quality: AudioQuality
    ): List<ScoredCandidate> {
        val url = "https://saavn.sumit.co/api/search/songs?query=$encodedQuery&limit=10"
        val json = getJsonObject(url) ?: return emptyList()
        val results = json.optJSONObject("data")?.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<ScoredCandidate>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val candTitle = decodeHtmlEntities(item.optString("name"))
            val primaryArtists = item.optJSONObject("artists")?.optJSONArray("primary")
            val candArtist = buildString {
                if (primaryArtists != null) {
                    for (j in 0 until primaryArtists.length()) {
                        val n = primaryArtists.optJSONObject(j)?.optString("name").orEmpty()
                        if (n.isNotBlank()) {
                            if (isNotEmpty()) append(", ")
                            append(n)
                        }
                    }
                }
            }
            val durationSec = item.optInt("duration", 0)
            val downloads = item.optJSONArray("downloadUrl") ?: continue

            val pickedDownload = pickSaavnDownloadEntry(downloads, quality) ?: continue
            val score = scoreCatalogCandidate(
                targetTitle = hint.title,
                targetArtist = hint.artist,
                targetDurationMs = hint.durationMs,
                candTitle = candTitle,
                candArtist = candArtist,
                candDurationSec = durationSec,
                isVerified = true
            )
            if (score == Int.MIN_VALUE) continue

            val kbps = pickedDownload.first
            list.add(
                ScoredCandidate(
                    score = score,
                    url = pickedDownload.second,
                    bitrateBps = kbps * 1000,
                    mimeType = "audio/mp4",
                    codecLabel = "AAC $kbps kbps"
                )
            )
        }
        return list
    }

    private fun pickSaavnDownloadEntry(downloads: JSONArray, quality: AudioQuality): Pair<Int, String>? {
        val entries = mutableListOf<Pair<Int, String>>()
        for (i in 0 until downloads.length()) {
            val entry = downloads.optJSONObject(i) ?: continue
            val rawUrl = entry.optString("url").trim().replace("http://", "https://")
            if (!rawUrl.startsWith("https://")) continue
            val kbps = entry.optString("quality").filter(Char::isDigit).toIntOrNull() ?: 160
            entries.add(kbps to rawUrl)
        }
        if (entries.isEmpty()) return null
        return when (quality) {
            AudioQuality.MAX -> entries.maxByOrNull { it.first }
            AudioQuality.HIGH -> entries.filter { it.first <= 192 }.maxByOrNull { it.first } ?: entries.minByOrNull { it.first }
            AudioQuality.DATA_SAVER -> entries.minByOrNull { it.first }
        }
    }

    private suspend fun resolveFromSoundCloud(
        hint: TrackMetadataHint,
        quality: AudioQuality,
        probePlayable: suspend (ResolvedStream) -> Boolean
    ): ResolvedStream? {
        ensureSoundCloudClientId() ?: return null
        val queries = buildCandidateQueries(hint.title, hint.artist)

        data class ScCandidate(
            val score: Int,
            val durationMs: Long,
            val transcodings: List<Pair<String, String>>
        )

        for (query in queries) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val activeCid = cachedSoundCloudClientId?.second ?: ensureSoundCloudClientId() ?: return null
            val searchUrl = "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$activeCid&limit=15"
            var searchJson = getJsonObject(searchUrl)
            if (searchJson == null) {
                cachedSoundCloudClientId = null
                val freshCid = ensureSoundCloudClientId(forceRefresh = true) ?: continue
                searchJson = getJsonObject(
                    "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$freshCid&limit=15"
                ) ?: continue
            }

            val resolvedCid = cachedSoundCloudClientId?.second ?: continue
            val collection = searchJson.optJSONArray("collection") ?: continue

            val candidates = mutableListOf<ScCandidate>()
            for (i in 0 until collection.length()) {
                val item = collection.optJSONObject(i) ?: continue
                val durMs = item.optLong("duration", 0L)
                if (durMs < 45_000L && hint.durationMs !in 1L..44_999L) continue

                val transArr = item.optJSONObject("media")?.optJSONArray("transcodings") ?: continue
                val playableTranscodings = extractUnencryptedSoundCloudTranscodings(transArr, quality)
                if (playableTranscodings.isEmpty()) continue

                val candTitle = item.optString("title")
                val userObj = item.optJSONObject("user")
                val uploader = userObj?.optString("username").orEmpty()
                val artistMeta = item.optJSONObject("publisher_metadata")?.optString("artist").orEmpty()
                val combinedArtist = listOf(artistMeta, uploader).filter { it.isNotBlank() }.joinToString(", ")
                val isVerified = userObj?.optBoolean("verified", false) == true

                val score = scoreCatalogCandidate(
                    targetTitle = hint.title,
                    targetArtist = hint.artist,
                    targetDurationMs = hint.durationMs,
                    candTitle = candTitle,
                    candArtist = combinedArtist,
                    candDurationSec = (durMs / 1000L).toInt(),
                    isVerified = isVerified
                )
                if (score >= SOUNDCLOUD_MIN_ACCEPT_SCORE) {
                    val hasProgressive = playableTranscodings.any { it.second == "progressive" }
                    candidates.add(ScCandidate(score + if (hasProgressive) 8 else 0, durMs, playableTranscodings))
                }
            }

            candidates.sortByDescending { it.score }

            for (cand in candidates.take(4)) {
                for ((transUrl, protocol) in cand.transcodings) {
                    val sep = if ('?' in transUrl) '&' else '?'
                    val resolvedObj = getJsonObject("$transUrl${sep}client_id=$resolvedCid") ?: continue
                    val streamUrl = resolvedObj.optString("url").trim().replace("http://", "https://")
                    if (!streamUrl.startsWith("https://")) continue

                    val isHls = protocol == "hls"
                    val stream = ResolvedStream(
                        url = streamUrl,
                        mimeType = if (isHls) "application/x-mpegURL" else "audio/mpeg",
                        bitrate = if (isHls) 160_000 else 128_000,
                        userAgent = browserUserAgent,
                        expiresAtMs = System.currentTimeMillis() + 25 * 60_000L,
                        codecLabel = if (isHls) "AAC 160 kbps" else "MP3 128 kbps"
                    )
                    if (probePlayable(stream)) {
                        return stream
                    }
                }
            }
        }
        return null
    }

    private suspend fun ensureSoundCloudClientId(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedSoundCloudClientId?.let { (ts, id) ->
                if (now - ts < 12 * 60 * 60_000L) return id
            }
        }
        return runCatching {
            val homeReq = Request.Builder()
                .url("https://soundcloud.com/")
                .header("User-Agent", browserUserAgent)
                .get()
                .build()
            val html = client.executeCancellable(homeReq).use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string().orEmpty()
            }
            val scriptUrls = SOUNDCLOUD_ASSET_REGEX.findAll(html).map { it.value }.toList()
            for (jsUrl in scriptUrls.takeLast(6).reversed()) {
                val jsReq = Request.Builder()
                    .url(jsUrl)
                    .header("User-Agent", browserUserAgent)
                    .get()
                    .build()
                val jsBody = client.executeCancellable(jsReq).use { resp ->
                    if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                }
                val cid = SOUNDCLOUD_CLIENT_ID_REGEX.find(jsBody)?.groupValues?.getOrNull(1)
                if (!cid.isNullOrBlank()) {
                    cachedSoundCloudClientId = now to cid
                    return@runCatching cid
                }
            }
            null
        }.onFailure { err ->
            Log.w("CatalogAudioFallback", "SoundCloud client_id extraction failed: ${err.message}")
        }.getOrNull()
    }

    private suspend fun getJsonObject(url: String): JSONObject? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", browserUserAgent)
                .header("Accept", "application/json")
                .get()
                .build()
            client.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank() || !raw.trimStart().startsWith("{")) return@runCatching null
                JSONObject(raw)
            }
        }.getOrNull()
    }

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
        private const val SAAVN_MIN_ACCEPT_SCORE = 90
        private const val SOUNDCLOUD_MIN_ACCEPT_SCORE = 45
        private val SOUNDCLOUD_ASSET_REGEX = Regex("https://a-v2\\.sndcdn\\.com/assets/[a-zA-Z0-9._-]+\\.js")
        private val SOUNDCLOUD_CLIENT_ID_REGEX = Regex("client_id\\s*:\\s*\"([a-zA-Z0-9]{20,40})\"")
        private val PAREN_BRACKET_REGEX = Regex("\\s*[(\\[]([^)\\]]*)[)\\]]")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]+")
        private val MULTI_SPACE_REGEX = Regex("\\s+")

        private val HARD_DISQUALIFY_TOKENS = listOf(
            "karaoke", "nightcore", "8d", "slowed", "sped up", "speed up",
            "originally performed", "made famous", "tribute", "ringtone",
            "piano version", "guitar cover", "techno", "afro house", "hardstyle",
            "lofi", "lo-fi", "instrumental", "tutorial", "interview", "reaction",
            "snippet", "teaser", "ringtone"
        )

        private val SOFT_PENALTY_TOKENS = listOf(
            "remix", "rmx", "edit", "bootleg", "cover", "live", "acoustic", "mashup", "megamix"
        )

        private val IGNORED_ARTISTS = setOf("youtube music", "artista desconocido", "various artists", "topic")

        internal fun extractUnencryptedSoundCloudTranscodings(
            transcodings: JSONArray,
            quality: AudioQuality
        ): List<Pair<String, String>> {
            data class RawTranscoding(val url: String, val protocol: String, val mime: String, val rank: Int)

            val valid = mutableListOf<RawTranscoding>()
            for (i in 0 until transcodings.length()) {
                val tr = transcodings.optJSONObject(i) ?: continue
                if (tr.optBoolean("snipped", false)) continue
                val transUrl = tr.optString("url").trim()
                if (!transUrl.startsWith("https://")) continue
                val format = tr.optJSONObject("format") ?: continue
                val proto = format.optString("protocol").trim().lowercase()
                val mime = format.optString("mime_type").trim().lowercase()
                // Strictly reject encrypted DRM streams (Apple FairPlay cbc-encrypted-hls, Widevine ctr-encrypted-hls)
                if (proto != "progressive" && proto != "hls") continue

                val rank = when {
                    proto == "progressive" && quality == AudioQuality.DATA_SAVER -> 40
                    proto == "hls" && mime.contains("mp4") -> 35 // AAC 160kbps fMP4 HLS
                    proto == "progressive" -> 30 // MP3 progressive (128kbps, direct seek)
                    proto == "hls" -> 20 // HLS MP3/Opus
                    else -> 10
                }
                valid.add(RawTranscoding(transUrl, proto, mime, rank))
            }
            return valid.sortedByDescending { it.rank }.map { it.url to it.protocol }
        }

        internal fun decryptJioSaavnUrl(encryptedMediaUrl: String, targetQuality: AudioQuality): String? {
            if (encryptedMediaUrl.isBlank()) return null
            return try {
                val decoded = Base64.getDecoder().decode(encryptedMediaUrl.trim())
                val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
                val keySpec = SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES")
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
                val decrypted = String(cipher.doFinal(decoded), Charsets.UTF_8)
                    .trim()
                    .replace("http://", "https://")
                if (!decrypted.startsWith("https://")) return null

                val suffix = when (targetQuality) {
                    AudioQuality.MAX -> "_320.mp4"
                    AudioQuality.HIGH -> "_160.mp4"
                    AudioQuality.DATA_SAVER -> "_96.mp4"
                }
                return decrypted.replace(Regex("_(96|160|320)\\.mp4$"), suffix)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        internal fun scoreCatalogCandidate(
            targetTitle: String,
            targetArtist: String,
            targetDurationMs: Long,
            candTitle: String,
            candArtist: String,
            candDurationSec: Int,
            isVerified: Boolean = false
        ): Int {
            val rawTargetCombined = normalizeForMatch("$targetTitle $targetArtist")
            val rawCandCombined = normalizeForMatch("$candTitle $candArtist")

            // Immediately reject knockoffs (Karaoke, Nightcore, Techno, Slowed, etc.) unless user asked for it
            for (badToken in HARD_DISQUALIFY_TOKENS) {
                val spacedBad = " $badToken "
                if (" $rawCandCombined ".contains(spacedBad) && !" $rawTargetCombined ".contains(spacedBad)) {
                    return Int.MIN_VALUE
                }
            }

            // Reject extreme duration mismatches (>2.2x target or <45s previews when target is >=45s)
            val targetDurSec = (targetDurationMs / 1000L).toInt()
            if (targetDurSec >= 45 && candDurationSec > 0) {
                if (candDurationSec < 45 || candDurationSec > (targetDurSec * 2.2).toInt()) {
                    return Int.MIN_VALUE
                }
            }

            val baseTargetTitle = normalizeForMatch(stripDecorations(targetTitle))
            val baseCandTitle = normalizeForMatch(stripDecorations(candTitle))
            if (baseTargetTitle.isBlank() || baseCandTitle.isBlank()) return Int.MIN_VALUE

            var score = 0

            // Title match scoring
            val targetTokens = baseTargetTitle.split(' ').filter { it.length >= 2 }
            val candTitleFullNorm = normalizeForMatch(candTitle)
            when {
                baseCandTitle == baseTargetTitle -> score += 110
                candTitleFullNorm.contains(baseTargetTitle) -> score += 85
                targetTokens.isNotEmpty() -> {
                    val hits = targetTokens.count { tok ->
                        " $candTitleFullNorm ".contains(" $tok ") || " $rawCandCombined ".contains(" $tok ")
                    }
                    val ratio = hits.toFloat() / targetTokens.size.toFloat()
                    if (ratio < 0.6f) {
                        return Int.MIN_VALUE
                    }
                    score += (ratio * 75f).toInt()
                }
            }

            // Multi-artist matching (handles collaborations like "KAROL G, rusowsky")
            val targetArtists = extractAllArtists(targetArtist)
            if (targetArtists.isNotEmpty()) {
                val candArtistNorm = normalizeForMatch(candArtist)
                val matchCount = targetArtists.count { artistPart ->
                    val tokens = artistPart.split(' ').filter { it.length >= 2 }
                    val exactHit = candArtistNorm.contains(artistPart) || candTitleFullNorm.contains(artistPart)
                    val tokenHit = tokens.isNotEmpty() && tokens.all { tok ->
                        candArtistNorm.contains(tok) || candTitleFullNorm.contains(tok)
                    }
                    exactHit || tokenHit
                }
                score += when {
                    matchCount == targetArtists.size -> 60
                    matchCount > 0 -> 45
                    else -> -25
                }
            }

            // Soft penalty for remix / edit / cover / acoustic unless requested
            for (softToken in SOFT_PENALTY_TOKENS) {
                val spaced = " $softToken "
                if (" $rawCandCombined ".contains(spaced) && !" $rawTargetCombined ".contains(spaced)) {
                    score -= 75
                }
            }

            // Duration proximity
            if (targetDurSec > 0 && candDurationSec > 0) {
                val diff = abs(candDurationSec - targetDurSec)
                score += when {
                    diff <= 6 -> 45
                    diff <= 18 -> 25
                    diff <= 45 -> 0
                    else -> -55
                }
            }

            if (isVerified) score += 20
            return score
        }

        internal fun buildCleanQuery(title: String, artist: String): String {
            val cleanTitle = stripDecorations(title).trim()
            val cleanArtist = extractRawArtists(artist).firstOrNull().orEmpty()
            return listOf(cleanTitle, cleanArtist)
                .filter { it.isNotBlank() && it.lowercase() !in IGNORED_ARTISTS }
                .joinToString(" ")
        }

        internal fun buildCandidateQueries(title: String, artist: String): List<String> {
            val cleanTitle = stripDecorations(title).trim()
            if (cleanTitle.isBlank()) return emptyList()
            val rawArtists = extractRawArtists(artist)
            val queries = linkedSetOf<String>()
            val primary = buildCleanQuery(title, artist)
            if (primary.isNotBlank()) queries.add(primary)
            val coArtist = rawArtists.getOrNull(1)
            if (!coArtist.isNullOrBlank()) {
                queries.add("$cleanTitle $coArtist")
            }
            if (cleanTitle.split(' ').count { it.length >= 2 } >= 2) {
                queries.add(cleanTitle)
            }
            return queries.toList()
        }

        private fun extractAllArtists(artist: String): List<String> {
            return extractRawArtists(artist)
                .map { normalizeForMatch(it) }
                .filter { it.isNotBlank() && it !in IGNORED_ARTISTS }
                .distinct()
        }

        private fun extractRawArtists(artist: String): List<String> {
            return artist
                .replace(" - Topic", "", ignoreCase = true)
                .split(",", "&", " feat.", " feat ", " ft.", " ft ", " x ", " X ", ";", "/")
                .map { it.trim() }
                .filter { it.isNotBlank() && it.lowercase() !in IGNORED_ARTISTS }
        }

        internal fun stripDecorations(title: String): String {
            var cleaned = title
            cleaned = PAREN_BRACKET_REGEX.replace(cleaned) { match ->
                val inner = match.groupValues[1].lowercase()
                val keepAsVersion = listOf("remix", "acoustic", "live", "instrumental", "slowed", "sped up")
                    .any { inner.contains(it) }
                if (keepAsVersion) " ${match.groupValues[1]}" else ""
            }
            return cleaned
                .replace(Regex("\\s+(feat\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE), "")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        internal fun normalizeForMatch(raw: String): String {
            val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
            val noDiacritics = Regex("\\p{InCombiningDiacriticalMarks}+").replace(nfd, "")
            return noDiacritics
                .lowercase()
                .replace(NON_ALNUM_REGEX, " ")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        private fun decodeHtmlEntities(text: String): String {
            return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
        }
    }
}
