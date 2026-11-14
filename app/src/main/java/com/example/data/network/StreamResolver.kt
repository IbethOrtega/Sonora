package com.example.data.network

import com.example.model.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class StreamResolver(
    private val httpClient: OkHttpClient
) {
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    private data class ClientProfile(
        val url: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val clientIdHeader: String,
        val extraClientFields: JSONObject.() -> Unit
    )

    private val profiles = listOf(
        ClientProfile(
            url = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
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
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X)",
            clientIdHeader = "5",
            extraClientFields = {
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "18.1.0.22B83")
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            url = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName = "ANDROID_MUSIC",
            clientVersion = "7.29.52",
            userAgent = "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14) gzip",
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

        for (profile in profiles) {
            val resolved = tryInnerTubeProfile(videoId, profile)
            if (resolved != null) {
                cache[videoId] = resolved
                return@withContext Result.success(resolved)
            }
        }

        for (instance in pipedInstances) {
            val resolved = tryPipedInstance(instance, videoId)
            if (resolved != null) {
                cache[videoId] = resolved
                return@withContext Result.success(resolved)
            }
        }

        Result.failure(IllegalStateException("No se pudo obtener una fuente de audio compatible para este tema."))
    }

    private fun tryInnerTubeProfile(videoId: String, profile: ClientProfile): ResolvedStream? {
        return runCatching {
            val clientObj = JSONObject()
                .put("clientName", profile.clientName)
                .put("clientVersion", profile.clientVersion)
            profile.extraClientFields(clientObj)

            val payload = JSONObject()
                .put("context", JSONObject().put("client", clientObj))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)

            val req = Request.Builder()
                .url(profile.url)
                .header("User-Agent", profile.userAgent)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", profile.clientIdHeader)
                .header("X-YouTube-Client-Version", profile.clientVersion)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@runCatching null
                val json = JSONObject(raw)
                val status = json.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                if (status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                    return@runCatching null
                }
                val streamingData = json.optJSONObject("streamingData") ?: return@runCatching null
                val expiresInSec = streamingData.optString("expiresInSeconds").toLongOrNull() ?: 3600L
                val expiresAt = System.currentTimeMillis() + expiresInSec * 1000L

                val formats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
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
                            userAgent = profile.userAgent,
                            expiresAtMs = expiresAt,
                            codecLabel = codec
                        )
                    }
                }

                // Fallback to HLS manifest if IOS returns hlsManifestUrl
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
                bestAudio
            }
        }.getOrNull()
    }

    private fun tryPipedInstance(baseUrl: String, videoId: String): ResolvedStream? {
        return runCatching {
            val req = Request.Builder()
                .url("$baseUrl/streams/$videoId")
                .header("User-Agent", "SonoraMusic/1.0")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                val json = JSONObject(raw)
                val audioStreams = json.optJSONArray("audioStreams") ?: return@runCatching null
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
                best
            }
        }.getOrNull()
    }
}
