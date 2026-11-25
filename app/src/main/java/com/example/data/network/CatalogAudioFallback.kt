package com.example.data.network

import android.util.Log
import com.example.model.AudioQuality
import com.example.model.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
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
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
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
                val rawTitle = json.optString("title").trim()
                val rawAuthor = json.optString("author_name")
                    .replace(" - Topic", "", ignoreCase = true)
                    .trim()
                if (rawTitle.isBlank()) return@runCatching null
                val dashIdx = rawTitle.indexOf(" - ")
                if (dashIdx > 1 && dashIdx < rawTitle.length - 4) {
                    val left = rawTitle.substring(0, dashIdx).trim()
                    val right = rawTitle.substring(dashIdx + 3).trim()
                    if (left.isNotBlank() && right.isNotBlank() && left.length <= 45) {
                        return@runCatching TrackMetadataHint(title = right, artist = left)
                    }
                }
                TrackMetadataHint(title = rawTitle, artist = rawAuthor)
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
            val jobs = buildList {
                if ("soundcloud" !in failedSources) {
                    add(async(Dispatchers.IO) {
                        resolveFromSoundCloud(hint, quality, probePlayable)?.let { "soundcloud" to it }
                    })
                }
                if ("saavn" !in failedSources) {
                    add(async(Dispatchers.IO) {
                        resolveFromJioSaavn(hint, quality, probePlayable)?.let { "saavn" to it }
                    })
                }
            }.toMutableList()

            var winner: Pair<String, ResolvedStream>? = null
            while (jobs.isNotEmpty() && winner == null) {
                val selected = select {
                    jobs.forEach { deferred ->
                        deferred.onAwait { res -> deferred to res }
                    }
                }
                jobs.remove(selected.first)
                if (selected.second != null) {
                    winner = selected.second
                }
            }
            jobs.forEach { it.cancel() }
            winner
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
                val urlsToTry = (listOf(cand.url) + cand.fallbackUrls).distinct()
                for (tryUrl in urlsToTry) {
                    val kbps = when {
                        tryUrl.contains("_320") -> 320
                        tryUrl.contains("_160") -> 160
                        tryUrl.contains("_96") -> 96
                        else -> (cand.bitrateBps / 1000).coerceAtLeast(96)
                    }
                    val stream = ResolvedStream(
                        url = tryUrl,
                        mimeType = cand.mimeType,
                        bitrate = kbps * 1000,
                        userAgent = browserUserAgent,
                        expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60_000L,
                        codecLabel = "AAC $kbps kbps"
                    )
                    if (probePlayable(stream)) {
                        return stream
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
        val codecLabel: String,
        val fallbackUrls: List<String> = emptyList()
    )

    private suspend fun fetchJioSaavnOfficial(
        encodedQuery: String,
        hint: TrackMetadataHint,
        quality: AudioQuality
    ): List<ScoredCandidate> {
        val v1Url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&includeMetaTags=1&n=10&q=$encodedQuery"
        val v4Url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=10&q=$encodedQuery"
        val json = getJsonObject(v1Url, referer = "https://www.jiosaavn.com/")
            ?.takeIf { (it.optJSONArray("results")?.length() ?: 0) > 0 }
            ?: getJsonObject(v4Url, referer = "https://www.jiosaavn.com/")
            ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<ScoredCandidate>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val moreInfo = item.optJSONObject("more_info")
            val candTitle = item.optString("song").ifBlank { item.optString("title") }
            val candArtist = item.optString("primary_artists")
                .ifBlank { item.optString("singers") }
                .ifBlank {
                    val primaryArr = moreInfo?.optJSONObject("artistMap")?.optJSONArray("primary_artists")
                    buildString {
                        if (primaryArr != null) {
                            for (j in 0 until primaryArr.length()) {
                                val n = primaryArr.optJSONObject(j)?.optString("name").orEmpty()
                                if (n.isNotBlank()) {
                                    if (isNotEmpty()) append(", ")
                                    append(n)
                                }
                            }
                        }
                    }
                }
                .ifBlank {
                    moreInfo?.optJSONObject("artistMap")?.optJSONArray("artists")
                        ?.optJSONObject(0)?.optString("name").orEmpty()
                }
            val durationSec = item.optString("duration").toIntOrNull()
                ?: moreInfo?.optString("duration")?.toIntOrNull()
                ?: item.optInt("duration", 0)
            val encrypted = item.optString("encrypted_media_url")
                .ifBlank { moreInfo?.optString("encrypted_media_url").orEmpty() }
            if (candTitle.isBlank() || encrypted.isBlank()) continue

            val candLanguage = item.optString("language").ifBlank { moreInfo?.optString("language").orEmpty() }
            val score = scoreCatalogCandidate(
                targetTitle = hint.title,
                targetArtist = hint.artist,
                targetDurationMs = hint.durationMs,
                candTitle = decodeHtmlEntities(candTitle),
                candArtist = decodeHtmlEntities(candArtist),
                candDurationSec = durationSec,
                isVerified = true,
                candLanguage = candLanguage
            )
            if (score == Int.MIN_VALUE) continue

            val mediaUrl = decryptJioSaavnUrl(encrypted, quality) ?: continue
            val fallbackQualities = buildList {
                if (quality == AudioQuality.MAX) {
                    decryptJioSaavnUrl(encrypted, AudioQuality.HIGH)?.let(::add)
                }
                decryptJioSaavnUrl(encrypted, AudioQuality.DATA_SAVER)?.let(::add)
            }.distinct().filter { it != mediaUrl }
            val kbps = when (quality) {
                AudioQuality.MAX -> if (mediaUrl.contains("_320")) 320 else 160
                AudioQuality.AUTO, AudioQuality.HIGH -> 160
                AudioQuality.DATA_SAVER -> 96
            }
            list.add(
                ScoredCandidate(
                    score = score,
                    url = mediaUrl,
                    bitrateBps = kbps * 1000,
                    mimeType = "audio/mp4",
                    codecLabel = "AAC $kbps kbps",
                    fallbackUrls = fallbackQualities
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
            val candLanguage = item.optString("language")
            val downloads = item.optJSONArray("downloadUrl") ?: continue

            val rankedDownloads = rankSaavnDownloadEntries(downloads, quality)
            val pickedDownload = rankedDownloads.firstOrNull() ?: continue
            val score = scoreCatalogCandidate(
                targetTitle = hint.title,
                targetArtist = hint.artist,
                targetDurationMs = hint.durationMs,
                candTitle = candTitle,
                candArtist = candArtist,
                candDurationSec = durationSec,
                isVerified = true,
                candLanguage = candLanguage
            )
            if (score == Int.MIN_VALUE) continue

            val kbps = pickedDownload.first
            list.add(
                ScoredCandidate(
                    score = score,
                    url = pickedDownload.second,
                    bitrateBps = kbps * 1000,
                    mimeType = "audio/mp4",
                    codecLabel = "AAC $kbps kbps",
                    fallbackUrls = rankedDownloads.drop(1).map { it.second }
                )
            )
        }
        return list
    }

    private fun rankSaavnDownloadEntries(downloads: JSONArray, quality: AudioQuality): List<Pair<Int, String>> {
        val entries = mutableListOf<Pair<Int, String>>()
        for (i in 0 until downloads.length()) {
            val entry = downloads.optJSONObject(i) ?: continue
            val rawUrl = entry.optString("url").trim().replace("http://", "https://")
            if (!rawUrl.startsWith("https://")) continue
            val kbps = entry.optString("quality").filter(Char::isDigit).toIntOrNull() ?: 160
            entries.add(kbps to rawUrl)
        }
        if (entries.isEmpty()) return emptyList()
        return when (quality) {
            AudioQuality.MAX -> entries.sortedByDescending { it.first }
            AudioQuality.AUTO, AudioQuality.HIGH -> {
                val under = entries.filter { it.first <= 192 }.sortedByDescending { it.first }
                val over = entries.filter { it.first > 192 }.sortedBy { it.first }
                (under + over).distinctBy { it.second }
            }
            AudioQuality.DATA_SAVER -> entries.filter { it.first >= 48 }
                .ifEmpty { entries }
                .sortedBy { it.first }
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

            for (cand in candidates.take(3)) {
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
                .takeLast(4)
                .reversed()
            if (scriptUrls.isEmpty()) return@runCatching null

            val foundCid = coroutineScope {
                val jobs = scriptUrls.map { jsUrl ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val jsReq = Request.Builder()
                                .url(jsUrl)
                                .header("User-Agent", browserUserAgent)
                                .get()
                                .build()
                            client.executeCancellable(jsReq).use { resp ->
                                if (!resp.isSuccessful) return@runCatching null
                                val jsBody = resp.body?.string().orEmpty()
                                SOUNDCLOUD_CLIENT_ID_REGEX.find(jsBody)?.groupValues?.getOrNull(1)
                            }
                        }.getOrNull()
                    }
                }.toMutableList()
                var winner: String? = null
                while (jobs.isNotEmpty() && winner == null) {
                    val selected = select {
                        jobs.forEach { deferred ->
                            deferred.onAwait { res -> deferred to res }
                        }
                    }
                    jobs.remove(selected.first)
                    if (!selected.second.isNullOrBlank()) {
                        winner = selected.second
                    }
                }
                jobs.forEach { it.cancel() }
                winner
            }
            if (!foundCid.isNullOrBlank()) {
                cachedSoundCloudClientId = now to foundCid
            }
            foundCid
        }.onFailure { err ->
            Log.w("CatalogAudioFallback", "SoundCloud client_id extraction failed: ${err.message}")
        }.getOrNull()
    }

    private suspend fun getJsonObject(url: String, referer: String? = null): JSONObject? {
        return runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", browserUserAgent)
                .header("Accept", "application/json")
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", referer)
            }
            client.executeCancellable(reqBuilder.get().build()).use { resp ->
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
        private const val SAAVN_MIN_ACCEPT_SCORE = 150
        private const val SOUNDCLOUD_MIN_ACCEPT_SCORE = 130
        private val SOUNDCLOUD_ASSET_REGEX = Regex("https://a-v2\\.sndcdn\\.com/assets/[a-zA-Z0-9._-]+\\.js")
        private val SOUNDCLOUD_CLIENT_ID_REGEX = Regex("client_id\\s*:\\s*\"([a-zA-Z0-9]{20,40})\"")
        private val PAREN_BRACKET_REGEX = Regex("\\s*[(\\[]([^)\\]]*)[)\\]]")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]+")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val NON_LATIN_SCRIPT_REGEX =
            Regex("[\\u0600-\\u06FF\\u0900-\\u0D7F\\u0E00-\\u0E7F\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF\\u0400-\\u04FF]")

        private val INDIAN_LANGUAGES = setOf(
            "hindi", "punjabi", "tamil", "telugu", "malayalam", "kannada",
            "bengali", "marathi", "gujarati", "bhojpuri", "haryanvi",
            "urdu", "rajasthani", "odia", "assamese"
        )

        private val HARD_DISQUALIFY_UPLOADER_TOKENS = listOf(
            "karaoke", "nightcore", "8d", "slowed", "sped up", "speed up", "spedup",
            "originally performed", "made famous", "tribute", "tributo", "homenaje", "soundalike",
            "ringtone", "tutorial", "parodia", "parody", "bootleg", "mashup",
            "cover", "covers", "ai cover", "ia cover", "voice cover", "fanmade", "fan made"
        )

        private val HARD_DISQUALIFY_TITLE_TOKENS = listOf(
            "karaoke", "nightcore", "8d", "slowed", "sped up", "speed up", "spedup", "reverb",
            "originally performed", "made famous", "tribute", "tributo", "homenaje", "soundalike",
            "ringtone", "piano", "guitar", "guitarra", "ukulele", "ukelele", "violin", "sax",
            "saxofon", "flauta", "cello", "bateria", "drum", "techno", "afro house",
            "hardstyle", "lofi", "lo-fi", "instrumental", "pista", "tutorial",
            "interview", "entrevista", "reaction", "reaccion", "snippet", "teaser", "preview",
            "parodia", "parody", "acapella", "a capella", "bass boosted", "bassboost",
            "bootleg", "mashup", "megamix", "cover", "covers", "ai cover", "ia cover",
            "voice cover", "mi version", "cantando", "canta", "interpreta",
            "fanmade", "fan made", "rework", "remake", "flip", "tiktok", "chipmunk",
            "la voz", "the voice", "got talent", "x factor", "demo", "ensayo",
            "version banda", "cumbia", "salsa", "bachata", "merengue", "guaracha",
            "banda", "nortena", "mariachi", "ranchera", "champeta", "vallenato", "phonk"
        )

        private val VERSION_FAMILY_TAGS: List<Pair<String, List<String>>> = listOf(
            "remix" to listOf("remix", "rmx"),
            "live" to listOf("live", "en vivo", "directo", "unplugged"),
            "acoustic" to listOf("acoustic", "acustico", "acustica"),
            "instrumental" to listOf("instrumental", "pista", "karaoke"),
            "slowed" to listOf("slowed", "reverb"),
            "sped_up" to listOf("sped up", "speed up", "nightcore"),
            "cover" to listOf("cover", "tribute", "tributo", "homenaje")
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
                    AudioQuality.AUTO, AudioQuality.HIGH -> "_160.mp4"
                    AudioQuality.DATA_SAVER -> "_96.mp4"
                }
                return decrypted.replace(Regex("_(96|160|320)\\.mp4$"), suffix)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        internal fun extractVersionFamilies(normalizedText: String): Set<String> {
            val padded = " $normalizedText "
            val matched = mutableSetOf<String>()
            for ((family, keywords) in VERSION_FAMILY_TAGS) {
                if (keywords.any { kw -> padded.contains(" $kw ") }) {
                    matched.add(family)
                }
            }
            return matched
        }

        internal fun scoreCatalogCandidate(
            targetTitle: String,
            targetArtist: String,
            targetDurationMs: Long,
            candTitle: String,
            candArtist: String,
            candDurationSec: Int,
            isVerified: Boolean = false,
            candLanguage: String = ""
        ): Int {
            // Recover artist from "Artist - Title" video titles when channel metadata is missing
            var effectiveTitle = targetTitle
            var targetArtists = extractAllArtists(targetArtist)
            val dashSplitIdx = targetTitle.indexOf(" - ").takeIf { it > 1 }
                ?: targetTitle.indexOf(" – ").takeIf { it > 1 }
            if (dashSplitIdx != null && dashSplitIdx < targetTitle.length - 4) {
                val left = targetTitle.substring(0, dashSplitIdx).trim()
                val right = targetTitle.substring(dashSplitIdx + 3).trim()
                val leftNorm = normalizeForMatch(left)
                val leftMatchesKnownArtist = targetArtists.any { a ->
                    val ac = a.replace(" ", "")
                    val lc = leftNorm.replace(" ", "")
                    leftNorm.contains(a) || a.contains(leftNorm) || (ac.length >= 4 && lc.contains(ac))
                }
                if (targetArtists.isEmpty() && left.length <= 45) {
                    targetArtists = extractAllArtists(left)
                    if (targetArtists.isNotEmpty()) {
                        effectiveTitle = right
                    }
                } else if (leftMatchesKnownArtist && right.isNotBlank()) {
                    effectiveTitle = right
                }
            }

            // Never allow title-only fuzzy catalog matching without a real target artist
            if (targetArtists.isEmpty()) return Int.MIN_VALUE

            // Reject regional Indian catalog tracks unless the target itself has Indian hints/script
            val targetCombinedRaw = "$effectiveTitle $targetArtist"
            val candCombinedRaw = "$candTitle $candArtist"
            val targetHasNonLatin = NON_LATIN_SCRIPT_REGEX.containsMatchIn(targetCombinedRaw)
            val candHasNonLatin = NON_LATIN_SCRIPT_REGEX.containsMatchIn(candCombinedRaw)
            if (!targetHasNonLatin && candHasNonLatin) {
                return Int.MIN_VALUE
            }
            val normLang = candLanguage.trim().lowercase()
            if (normLang in INDIAN_LANGUAGES && !targetHasNonLatin) {
                val targetLower = targetCombinedRaw.lowercase()
                val explicitlyIndian = INDIAN_LANGUAGES.any { targetLower.contains(it) } ||
                    targetLower.contains("bollywood") || targetLower.contains("desi")
                if (!explicitlyIndian) {
                    return Int.MIN_VALUE
                }
            }

            val rawTargetCombined = normalizeForMatch("$effectiveTitle $targetArtist")
            val candTitleFullNorm = normalizeForMatch(candTitle)
            val candArtistNorm = normalizeForMatch(candArtist)
            val targetTitleFullNorm = normalizeForMatch(effectiveTitle)

            // Reject covers/tributes/karaoke in title or uploader without blocking genre artist names (e.g., Banda MS)
            for (badToken in HARD_DISQUALIFY_TITLE_TOKENS) {
                val spacedBad = " $badToken "
                if (" $candTitleFullNorm ".contains(spacedBad) && !" $rawTargetCombined ".contains(spacedBad)) {
                    return Int.MIN_VALUE
                }
            }
            for (badUploader in HARD_DISQUALIFY_UPLOADER_TOKENS) {
                val spacedBad = " $badUploader "
                if (" $candArtistNorm ".contains(spacedBad) && !" $rawTargetCombined ".contains(spacedBad)) {
                    return Int.MIN_VALUE
                }
            }

            // Enforce symmetric version parity (studio <-> remix/live/acoustic/cover never cross-match)
            val targetVersions = extractVersionFamilies(targetTitleFullNorm)
            val candVersions = extractVersionFamilies(candTitleFullNorm)
            if (targetVersions != candVersions) {
                if ((candVersions - targetVersions).isNotEmpty() || (targetVersions - candVersions).isNotEmpty()) {
                    return Int.MIN_VALUE
                }
            }

            // Enforce duration proximity (allow music-video intros/outros while blocking 30s previews)
            val targetDurSec = (targetDurationMs / 1000L).toInt()
            if (targetDurSec >= 45 && candDurationSec > 0) {
                val maxAllowedDiff = (targetDurSec * 0.30).toInt().coerceIn(25, 75)
                if (candDurationSec < 45 || abs(candDurationSec - targetDurSec) > maxAllowedDiff) {
                    return Int.MIN_VALUE
                }
            }

            val baseTargetTitle = normalizeForMatch(stripDecorations(effectiveTitle))
            val baseCandTitle = normalizeForMatch(stripDecorations(candTitle))
            if (baseTargetTitle.isBlank() || baseCandTitle.isBlank()) return Int.MIN_VALUE

            var score = 0

            // Bidirectional title match scoring so short single-word titles don't match long unrelated titles
            val targetTokens = baseTargetTitle.split(' ').filter { it.length >= 2 }
            val candTokens = baseCandTitle.split(' ').filter { it.length >= 2 }
            when {
                baseCandTitle == baseTargetTitle -> score += 110
                targetTokens.isNotEmpty() && candTokens.isNotEmpty() -> {
                    val targetInCand = targetTokens.count { tok ->
                        " $candTitleFullNorm ".contains(" $tok ")
                    }.toFloat() / targetTokens.size.toFloat()
                    val candInTarget = candTokens.count { tok ->
                        " $targetTitleFullNorm ".contains(" $tok ")
                    }.toFloat() / candTokens.size.toFloat()
                    if (targetInCand < 0.78f || candInTarget < 0.50f) {
                        return Int.MIN_VALUE
                    }
                    score += ((targetInCand * 55f) + (candInTarget * 35f)).toInt()
                }
                else -> return Int.MIN_VALUE
            }

            // Strict artist verification: must match candArtist directly (prevents SoundCloud fan covers
            // that put the real artist name in the upload title while uploaded by a random user)
            val candArtistCollapsed = candArtistNorm.replace(" ", "")
            val artistFieldMatches = targetArtists.count { artistPart ->
                val tokens = artistPart.split(' ').filter { it.length >= 2 }
                val artistCollapsed = artistPart.replace(" ", "")
                val exactHit = " $candArtistNorm ".contains(" $artistPart ") || candArtistNorm == artistPart
                val tokenHit = tokens.isNotEmpty() && tokens.all { tok ->
                    " $candArtistNorm ".contains(" $tok ")
                }
                val collapsedHit = artistCollapsed.length >= 4 &&
                    candArtistCollapsed.contains(artistCollapsed)
                exactHit || tokenHit || collapsedHit
            }
            if (artistFieldMatches == 0) {
                // Allow verified official label channels only if artist appears cleanly in title too
                val isOfficialLabel = isVerified && listOf(
                    "vevo", "official", "oficial", "records", "music", "entertainment", "topic"
                ).any { " $candArtistNorm ".contains(" $it ") }
                val titleArtistHit = targetArtists.any { artistPart ->
                    " $candTitleFullNorm ".contains(" $artistPart ")
                }
                if (!isOfficialLabel || !titleArtistHit) {
                    return Int.MIN_VALUE
                }
                score += 42
            } else {
                score += if (artistFieldMatches == targetArtists.size) 70 else 52
            }

            // Duration proximity bonus
            if (targetDurSec > 0 && candDurationSec > 0) {
                val diff = abs(candDurationSec - targetDurSec)
                score += when {
                    diff <= 6 -> 45
                    diff <= 18 -> 25
                    diff <= 32 -> 5
                    else -> -20
                }
            }

            if (isVerified) score += 20
            return score
        }

        internal fun buildCleanQuery(title: String, artist: String): String {
            var cleanTitle = stripDecorations(title).trim()
            val cleanArtist = extractRawArtists(artist).firstOrNull().orEmpty()
            if (cleanArtist.isNotBlank()) {
                val dashIdx = cleanTitle.indexOf(" - ").takeIf { it > 1 }
                    ?: cleanTitle.indexOf(" – ").takeIf { it > 1 }
                if (dashIdx != null && dashIdx < cleanTitle.length - 3) {
                    val leftNorm = normalizeForMatch(cleanTitle.substring(0, dashIdx))
                    val artNorm = normalizeForMatch(cleanArtist)
                    if (artNorm.isNotBlank() && (leftNorm.contains(artNorm) || artNorm.contains(leftNorm))) {
                        cleanTitle = cleanTitle.substring(dashIdx + 3).trim()
                    }
                }
            }
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
            // Only fall back to a title-only query if no artist metadata exists
            if (rawArtists.isEmpty() && cleanTitle.split(' ').count { it.length >= 2 } >= 2) {
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
            val cleanedSegments = artist
                .split("•", "·")
                .map { it.trim() }
                .filter { seg ->
                    val low = seg.lowercase()
                    seg.isNotBlank() &&
                        low !in setOf("song", "canción", "cancion", "video", "album", "álbum", "playlist", "single", "sencillo", "ep") &&
                        !low.contains("views") && !low.contains("vistas") && !low.contains("plays") &&
                        !Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$").matches(seg)
                }
            val effectiveArtist = cleanedSegments.firstOrNull() ?: artist
            return effectiveArtist
                .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("vevo$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(official|oficial)$", RegexOption.IGNORE_CASE), "")
                .split(",", "&", " feat.", " feat ", " ft.", " ft ", " x ", " X ", ";", "/")
                .map { it.trim() }
                .filter { it.isNotBlank() && it.lowercase() !in IGNORED_ARTISTS }
        }

        internal fun stripDecorations(title: String): String {
            var cleaned = title
            repeat(4) {
                val next = PAREN_BRACKET_REGEX.replace(cleaned) { match ->
                    val inner = match.groupValues[1].lowercase()
                    val keepAsVersion = listOf(
                        "remix", "rmx", "acoustic", "acústic", "acustic", "live", "en vivo",
                        "instrumental", "slowed", "sped up", "speed up", "unplugged", "cover"
                    ).any { inner.contains(it) }
                    if (keepAsVersion) " ${match.groupValues[1]}" else ""
                }
                if (next == cleaned) return@repeat
                cleaned = next
            }
            return cleaned
                .replace(
                    Regex("\\s+[-–]\\s+(taylor'?s version|album version|radio edit|single version|remaster(ed)?(\\s+\\d{4})?|from\\s+.*|original.*)$", RegexOption.IGNORE_CASE),
                    ""
                )
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
