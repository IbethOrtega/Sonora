package com.example.data.network

import android.util.Log
import com.example.model.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class StreamResolver(
    private val httpClient: OkHttpClient
) {
    private val fastMirrorClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val lastSourceByVideo = ConcurrentHashMap<String, String>()
    private val failedSourcesByVideo = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile
    private var cachedVisitorData: String? = null

    @Volatile
    private var sessionCookies: String? = null

    fun setSessionCookies(cookies: String?) {
        sessionCookies = cookies?.takeIf { it.isNotBlank() }
        cache.clear()
        failedSourcesByVideo.clear()
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
        val lastSource = lastSourceByVideo[videoId]
        if (lastSource != null) {
            failedSourcesByVideo.getOrPut(videoId) { ConcurrentHashMap.newKeySet() }.add(lastSource)
        }
    }

    private data class ClientProfile(
        val url: String,
        val origin: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val clientIdHeader: String,
        val sendAuthCookies: Boolean = false,
        val extraClientFields: JSONObject.() -> Unit,
        val extraBodyFields: JSONObject.() -> Unit = {}
    )

    private val profiles = listOf(
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientIdHeader = "28",
            extraClientFields = {
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("osName", "Android")
                put("osVersion", "12L")
                put("androidSdkVersion", 32)
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "ANDROID_EMBEDDED_PLAYER",
            clientVersion = "19.44.38",
            userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip",
            clientIdHeader = "55",
            extraClientFields = {
                put("osName", "Android")
                put("osVersion", "14")
                put("androidSdkVersion", 34)
                put("hl", "en")
                put("gl", "US")
            },
            extraBodyFields = {
                put(
                    "playbackContext",
                    JSONObject().put(
                        "contentPlaybackContext",
                        JSONObject()
                            .put("html5Preference", "HTML5_PREF_WANTS")
                            .put("referer", "https://www.youtube.com/")
                    )
                )
            }
        ),
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "IOS",
            clientVersion = "20.03.02",
            userAgent = "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X)",
            clientIdHeader = "5",
            extraClientFields = {
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "18.2.1.22C161")
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 TV Safari/537.36",
            clientIdHeader = "85",
            sendAuthCookies = true,
            extraClientFields = {
                put("hl", "en")
                put("gl", "US")
            },
            extraBodyFields = {
                put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
            }
        ),
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20250129.01.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            clientIdHeader = "56",
            sendAuthCookies = true,
            extraClientFields = {
                put("hl", "en")
                put("gl", "US")
            },
            extraBodyFields = {
                put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
            }
        ),
        ClientProfile(
            url = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://music.youtube.com",
            clientName = "ANDROID_MUSIC",
            clientVersion = "8.05.51",
            userAgent = "com.google.android.apps.youtube.music/8.05.51 (Linux; U; Android 14) gzip",
            clientIdHeader = "21",
            extraClientFields = {
                put("osName", "Android")
                put("osVersion", "14")
                put("androidSdkVersion", 34)
                put("hl", "en")
                put("gl", "US")
            }
        )
    )

    private val invidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://invidious.jing.rocks",
        "https://yt.artemislena.eu"
    )

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee"
    )

    suspend fun resolveAudioStream(videoId: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[videoId]?.let { cached ->
            if (cached.expiresAtMs > now + 60_000L) {
                return@withContext Result.success(cached)
            }
        }

        val failedSources = failedSourcesByVideo[videoId].orEmpty()
        val visitorData = ensureVisitorData()

        for (profile in profiles) {
            val sourceId = "yt:${profile.clientName}"
            if (sourceId in failedSources) continue
            val resolved = tryInnerTubeProfile(videoId, profile, visitorData)
            if (resolved != null) {
                cache[videoId] = resolved
                lastSourceByVideo[videoId] = sourceId
                return@withContext Result.success(resolved)
            }
        }

        for (instance in invidiousInstances) {
            val sourceId = "inv:$instance"
            if (sourceId in failedSources) continue
            val resolved = tryInvidiousInstance(instance, videoId)
            if (resolved != null) {
                cache[videoId] = resolved
                lastSourceByVideo[videoId] = sourceId
                return@withContext Result.success(resolved)
            }
        }

        for (instance in pipedInstances) {
            val sourceId = "piped:$instance"
            if (sourceId in failedSources) continue
            val resolved = tryPipedInstance(instance, videoId)
            if (resolved != null) {
                cache[videoId] = resolved
                lastSourceByVideo[videoId] = sourceId
                return@withContext Result.success(resolved)
            }
        }

        // If all sources were exhausted on retry, clear blacklist once so next manual attempt starts fresh
        if (failedSources.isNotEmpty()) {
            failedSourcesByVideo.remove(videoId)
        }

        Result.failure(IllegalStateException("No se pudo obtener una fuente de audio compatible para este tema."))
    }

    private fun ensureVisitorData(): String? {
        cachedVisitorData?.let { return it }
        val fromSw = runCatching {
            val req = Request.Builder()
                .url("https://music.youtube.com/sw.js_data")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                Regex("\"(Cg[a-zA-Z0-9_%-]{15,})\"").find(raw)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()

        val fetched = fromSw ?: runCatching {
            val body = JSONObject().put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", "2.20250129.01.00")
                )
            )
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) null
                else JSONObject(raw).optJSONObject("responseContext")?.optString("visitorData")
            }
        }.getOrNull()

        if (!fetched.isNullOrBlank()) {
            cachedVisitorData = fetched
        }
        return cachedVisitorData
    }

    private fun tryInnerTubeProfile(
        videoId: String,
        profile: ClientProfile,
        visitorData: String?
    ): ResolvedStream? {
        return runCatching {
            val clientObj = JSONObject()
                .put("clientName", profile.clientName)
                .put("clientVersion", profile.clientVersion)
            if (!visitorData.isNullOrBlank()) {
                clientObj.put("visitorData", visitorData)
            }
            profile.extraClientFields(clientObj)

            val payload = JSONObject()
                .put("context", JSONObject().put("client", clientObj))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
            profile.extraBodyFields(payload)

            val builder = Request.Builder()
                .url(profile.url)
                .header("User-Agent", profile.userAgent)
                .header("Origin", profile.origin)
                .header("Referer", "${profile.origin}/")
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", profile.clientIdHeader)
                .header("X-YouTube-Client-Version", profile.clientVersion)

            if (!visitorData.isNullOrBlank()) {
                builder.header("X-Goog-Visitor-Id", visitorData)
            }

            if (profile.sendAuthCookies) {
                val cookies = sessionCookies
                if (!cookies.isNullOrBlank()) {
                    builder.header("Cookie", cookies)
                    val sapisid = extractCookie(cookies, "__Secure-3PAPISID")
                        ?: extractCookie(cookies, "SAPISID")
                    if (sapisid != null) {
                        builder.header("Authorization", computeSapisidHash(sapisid, profile.origin))
                        builder.header("X-Origin", profile.origin)
                        builder.header("X-Goog-AuthUser", "0")
                    }
                }
            }

            val req = builder
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("StreamResolver", "${profile.clientName} HTTP ${resp.code}")
                    return@runCatching null
                }
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@runCatching null
                val json = JSONObject(raw)
                val playability = json.optJSONObject("playabilityStatus")
                val status = playability?.optString("status").orEmpty()
                if (status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                    Log.w("StreamResolver", "${profile.clientName} status=$status reason=${playability?.optString("reason")}")
                    return@runCatching null
                }

                val streamingData = json.optJSONObject("streamingData") ?: return@runCatching null
                val expiresInSec = streamingData.optString("expiresInSeconds").toLongOrNull() ?: 3600L
                val expiresAt = System.currentTimeMillis() + expiresInSec * 1000L

                // 1. Prefer adaptive audio-only formats with direct URL
                val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                var bestAudio = pickBestAudioFormat(adaptive, profile.userAgent, expiresAt)

                // 2. Fallback to progressive formats (video+audio container; ExoPlayer plays audio-only)
                if (bestAudio == null) {
                    val progressive = streamingData.optJSONArray("formats") ?: JSONArray()
                    bestAudio = pickBestProgressiveFormat(progressive, profile.userAgent, expiresAt)
                }

                // 3. Fallback to HLS manifest
                if (bestAudio == null) {
                    val hlsUrl = streamingData.optString("hlsManifestUrl")
                    if (hlsUrl.isNotBlank()) {
                        bestAudio = ResolvedStream(
                            url = hlsUrl,
                            mimeType = "application/x-mpegURL",
                            bitrate = 128_000,
                            userAgent = profile.userAgent,
                            expiresAtMs = expiresAt,
                            codecLabel = "HLS Audio"
                        )
                    }
                }
                if (bestAudio == null) {
                    Log.w("StreamResolver", "${profile.clientName} returned OK but no direct URLs")
                }
                bestAudio
            }
        }.getOrNull()
    }

    private fun pickBestAudioFormat(
        formats: JSONArray,
        userAgent: String,
        expiresAt: Long
    ): ResolvedStream? {
        var bestAudio: ResolvedStream? = null
        for (i in 0 until formats.length()) {
            val fmt = formats.optJSONObject(i) ?: continue
            val mime = fmt.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            val directUrl = fmt.optString("url")
            if (directUrl.isBlank()) continue
            val bitrate = fmt.optInt("bitrate", 0)
            val isOpus = mime.contains("opus", ignoreCase = true)
            val score = bitrate + if (isOpus) 15_000 else 0
            val currentScore = (bestAudio?.bitrate ?: -1) +
                if (bestAudio?.mimeType?.contains("opus", true) == true) 15_000 else 0

            if (score > currentScore) {
                val codec = if (isOpus) "OPUS ${bitrate / 1000} kbps" else "AAC ${bitrate / 1000} kbps"
                bestAudio = ResolvedStream(
                    url = directUrl,
                    mimeType = mime,
                    bitrate = bitrate,
                    userAgent = userAgent,
                    expiresAtMs = expiresAt,
                    codecLabel = codec
                )
            }
        }
        return bestAudio
    }

    private fun pickBestProgressiveFormat(
        formats: JSONArray,
        userAgent: String,
        expiresAt: Long
    ): ResolvedStream? {
        var best: ResolvedStream? = null
        for (i in 0 until formats.length()) {
            val fmt = formats.optJSONObject(i) ?: continue
            val directUrl = fmt.optString("url")
            if (directUrl.isBlank()) continue
            val mime = fmt.optString("mimeType", "video/mp4")
            val bitrate = fmt.optInt("bitrate", 128_000)
            if (best == null || bitrate > best.bitrate) {
                best = ResolvedStream(
                    url = directUrl,
                    mimeType = mime,
                    bitrate = bitrate,
                    userAgent = userAgent,
                    expiresAtMs = expiresAt,
                    codecLabel = "AAC Stream"
                )
            }
        }
        return best
    }

    private fun tryInvidiousInstance(baseUrl: String, videoId: String): ResolvedStream? {
        return runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/v1/videos/$videoId?local=true")
                .header("User-Agent", "Mozilla/5.0 (Android 14) Sonora/1.0")
                .get()
                .build()
            fastMirrorClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@runCatching null
                parseInvidiousJson(baseUrl, JSONObject(raw))
            }
        }.getOrNull()
    }

    internal fun parseInvidiousJson(baseUrl: String, json: JSONObject): ResolvedStream? {
        val adaptive = json.optJSONArray("adaptiveFormats") ?: JSONArray()
        var best: ResolvedStream? = null
        for (i in 0 until adaptive.length()) {
            val fmt = adaptive.optJSONObject(i) ?: continue
            val type = fmt.optString("type")
            if (!type.startsWith("audio/")) continue
            val rawUrl = fmt.optString("url")
            if (rawUrl.isBlank()) continue
            val fullUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
            val bitrate = fmt.optString("bitrate").toIntOrNull() ?: fmt.optInt("bitrate", 128_000)
            if (best == null || bitrate > best.bitrate) {
                best = ResolvedStream(
                    url = fullUrl,
                    mimeType = type,
                    bitrate = bitrate,
                    userAgent = "Mozilla/5.0 (Android 14) Sonora/1.0",
                    expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                    codecLabel = "Audio ${bitrate / 1000} kbps"
                )
            }
        }
        if (best != null) return best

        val formatStreams = json.optJSONArray("formatStreams") ?: return null
        for (i in 0 until formatStreams.length()) {
            val fmt = formatStreams.optJSONObject(i) ?: continue
            val rawUrl = fmt.optString("url")
            if (rawUrl.isBlank()) continue
            val fullUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
            return ResolvedStream(
                url = fullUrl,
                mimeType = fmt.optString("type", "video/mp4"),
                bitrate = 128_000,
                userAgent = "Mozilla/5.0 (Android 14) Sonora/1.0",
                expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                codecLabel = "AAC Stream"
            )
        }
        return null
    }

    private fun tryPipedInstance(baseUrl: String, videoId: String): ResolvedStream? {
        return runCatching {
            val req = Request.Builder()
                .url("$baseUrl/streams/$videoId")
                .header("User-Agent", "SonoraMusic/1.0")
                .get()
                .build()
            fastMirrorClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@runCatching null
                parsePipedJson(JSONObject(raw))
            }
        }.getOrNull()
    }

    internal fun parsePipedJson(json: JSONObject): ResolvedStream? {
        val audioStreams = json.optJSONArray("audioStreams") ?: JSONArray()
        var best: ResolvedStream? = null
        for (i in 0 until audioStreams.length()) {
            val item = audioStreams.optJSONObject(i) ?: continue
            val streamUrl = item.optString("url")
            if (streamUrl.isBlank()) continue
            val bitrate = item.optInt("bitrate", 0)
            val mime = item.optString("mimeType", "audio/mp4")
            if (best == null || bitrate > best.bitrate) {
                best = ResolvedStream(
                    url = streamUrl,
                    mimeType = mime,
                    bitrate = bitrate,
                    userAgent = "SonoraMusic/1.0",
                    expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                    codecLabel = "${item.optString("codec", "Audio").uppercase()} ${bitrate / 1000} kbps"
                )
            }
        }
        if (best != null) return best

        val hls = json.optString("hls")
        if (hls.isNotBlank()) {
            return ResolvedStream(
                url = hls,
                mimeType = "application/x-mpegURL",
                bitrate = 128_000,
                userAgent = "SonoraMusic/1.0",
                expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                codecLabel = "HLS Audio"
            )
        }
        return null
    }

    private fun extractCookie(cookies: String, key: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun computeSapisidHash(sapisid: String, origin: String): String {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val input = "$timestamp $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hex"
    }
}
