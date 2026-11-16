package com.example

import com.example.data.network.StreamResolver
import com.example.model.AudioQuality
import com.example.model.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamResolverTest {

    @Test
    fun `parseInvidiousJson resolves relative local proxy urls`() {
        val resolver = StreamResolver(OkHttpClient())
        val json = JSONObject().put(
            "adaptiveFormats",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "video/mp4")
                        .put("url", "/latest_version?id=abc&itag=18&local=true")
                        .put("bitrate", "500000")
                )
                .put(
                    JSONObject()
                        .put("type", "audio/webm; codecs=\"opus\"")
                        .put("url", "/latest_version?id=abc&itag=251&local=true")
                        .put("bitrate", "160000")
                )
        )

        val stream = resolver.parseInvidiousJson("https://inv.nadeko.net", json)
        assertNotNull(stream)
        assertEquals(
            "https://inv.nadeko.net/latest_version?id=abc&itag=251&local=true",
            stream!!.url
        )
        assertEquals(160000, stream.bitrate)
    }

    @Test
    fun `parsePipedJson falls back to hls when audioStreams is empty`() {
        val resolver = StreamResolver(OkHttpClient())
        val json = JSONObject()
            .put("audioStreams", JSONArray())
            .put("hls", "https://pipedproxy.example/manifest.m3u8")

        val stream = resolver.parsePipedJson(json)
        assertNotNull(stream)
        assertEquals("https://pipedproxy.example/manifest.m3u8", stream!!.url)
        assertEquals("application/x-mpegURL", stream.mimeType)
    }

    @Test
    fun `parsePipedJson and parseInvidiousJson reject JSON null literal URLs`() {
        val resolver = StreamResolver(OkHttpClient())
        val pipedJson = JSONObject()
            .put(
                "audioStreams",
                JSONArray().put(
                    JSONObject()
                        .put("url", JSONObject.NULL)
                        .put("bitrate", 128000)
                )
            )
            .put("hls", JSONObject.NULL)
        assertNull(resolver.parsePipedJson(pipedJson))

        val invJson = JSONObject()
            .put(
                "adaptiveFormats",
                JSONArray().put(
                    JSONObject()
                        .put("type", "audio/mp4")
                        .put("url", "null")
                        .put("bitrate", "128000")
                )
            )
        assertNull(resolver.parseInvidiousJson("https://inv.nadeko.net", invJson))
    }

    @Test
    fun `selectStreamByQuality respects MAX HIGH and DATA_SAVER bitrates`() {
        val resolver = StreamResolver(OkHttpClient())
        val candidates = listOf(
            ResolvedStream("https://a.example/64", "audio/mp4", 64_000, "ua", 1L, "AAC 64 kbps"),
            ResolvedStream("https://a.example/128", "audio/mp4", 128_000, "ua", 1L, "AAC 128 kbps"),
            ResolvedStream("https://a.example/256", "audio/webm; codecs=\"opus\"", 256_000, "ua", 1L, "OPUS 256 kbps")
        )

        resolver.setPreferredQuality(AudioQuality.MAX)
        assertEquals(256_000, resolver.selectStreamByQuality(candidates)?.bitrate)

        resolver.setPreferredQuality(AudioQuality.HIGH)
        assertEquals(128_000, resolver.selectStreamByQuality(candidates)?.bitrate)

        resolver.setPreferredQuality(AudioQuality.DATA_SAVER)
        assertEquals(64_000, resolver.selectStreamByQuality(candidates)?.bitrate)
    }

    @Test
    fun `resolveAudioStream rotates to next profile after invalidate and maps host UA`() = runBlocking {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                if (path.contains("sw.js_data")) {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(")]}'\n[[\"Cgt2aXNpdG9yMTIzNDU2Nzg5MA\"]]"
                            .toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                if (!path.contains("youtubei/v1/player")) {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                val bodyStr = req.body?.let { body ->
                    val buf = okio.Buffer()
                    body.writeTo(buf)
                    buf.readUtf8()
                }.orEmpty()
                val bodyJson = JSONObject(bodyStr)
                val clientName = bodyJson
                    .optJSONObject("context")
                    ?.optJSONObject("client")
                    ?.optString("clientName")
                    .orEmpty()
                if (clientName.isBlank()) {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                calls.add(clientName)

                val respJson = JSONObject()
                    .put("playabilityStatus", JSONObject().put("status", "OK"))
                    .put(
                        "streamingData",
                        JSONObject()
                            .put("expiresInSeconds", "3600")
                            .put(
                                "adaptiveFormats",
                                JSONArray().put(
                                    JSONObject()
                                        .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                                        .put("bitrate", 128000)
                                        .put("url", "https://audio.example/$clientName")
                                )
                            )
                    )
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respJson.toString().toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val resolver = StreamResolver(client)
        val first = resolver.resolveAudioStream("vid123")
        assertTrue(first.isSuccess)
        assertEquals("https://audio.example/ANDROID_VR", first.getOrThrow().url)
        assertEquals(first.getOrThrow().userAgent, resolver.getUserAgentForHost("audio.example"))

        resolver.invalidate("vid123")
        val second = resolver.resolveAudioStream("vid123")
        assertTrue(second.isSuccess)
        assertEquals("https://audio.example/IOS", second.getOrThrow().url)
        assertEquals(listOf("ANDROID_VR", "IOS"), calls)
    }

    @Test
    fun `concurrent resolveAudioStream deduplicates requests and maps user agent by url`() = runBlocking {
        val playerCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                if (path.contains("sw.js_data")) {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(")]}'\n[[\"Cgt2aXNpdG9yMTIzNDU2Nzg5MA\"]]"
                            .toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                if (path.contains("youtubei/v1/player")) {
                    playerCalls.incrementAndGet()
                    Thread.sleep(150)
                    val respJson = JSONObject()
                        .put("playabilityStatus", JSONObject().put("status", "OK"))
                        .put(
                            "streamingData",
                            JSONObject()
                                .put("expiresInSeconds", "3600")
                                .put(
                                    "adaptiveFormats",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                                            .put("bitrate", 128000)
                                            .put("url", "https://audio.example/dedup_stream")
                                    )
                                )
                        )
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(respJson.toString().toResponseBody("application/json".toMediaType()))
                        .build()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val resolver = StreamResolver(client)
        val a = async(Dispatchers.IO) {
            resolver.resolveAudioStream("same_vid")
        }
        val b = async(Dispatchers.IO) {
            resolver.resolveAudioStream("same_vid")
        }
        val resA = a.await()
        val resB = b.await()
        assertTrue(resA.isSuccess)
        assertTrue(resB.isSuccess)
        assertEquals(resA.getOrThrow().url, resB.getOrThrow().url)
        assertEquals(1, playerCalls.get())
        assertEquals(
            resA.getOrThrow().userAgent,
            resolver.getUserAgentForUrl("https://audio.example/dedup_stream")
        )
    }

    @Test
    fun `decryptJioSaavnUrl decrypts DES ECB payload and upgrades bitrate and https`() {
        // Encrypt "http://aac.saavncdn.com/123/track_96.mp4" with JioSaavn key "38346591"
        val plain = "http://aac.saavncdn.com/123/track_96.mp4"
        val cipher = javax.crypto.Cipher.getInstance("DES/ECB/PKCS5Padding")
        val keySpec = javax.crypto.spec.SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))

        assertEquals(
            "https://aac.saavncdn.com/123/track_320.mp4",
            com.example.data.network.CatalogAudioFallback.decryptJioSaavnUrl(encrypted, AudioQuality.MAX)
        )
        assertEquals(
            "https://aac.saavncdn.com/123/track_160.mp4",
            com.example.data.network.CatalogAudioFallback.decryptJioSaavnUrl(encrypted, AudioQuality.HIGH)
        )
        assertEquals(
            "https://aac.saavncdn.com/123/track_96.mp4",
            com.example.data.network.CatalogAudioFallback.decryptJioSaavnUrl(encrypted, AudioQuality.DATA_SAVER)
        )
    }

    @Test
    fun `scoreCatalogCandidate rejects nightcore karaoke techno and accepts diacritic match`() {
        val rejectedNightcore = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Blinding Lights",
            targetArtist = "The Weeknd",
            targetDurationMs = 200_000L,
            candTitle = "Blinding Lights (Nightcore)",
            candArtist = "DJ Bootleg",
            candDurationSec = 175
        )
        assertEquals(Int.MIN_VALUE, rejectedNightcore)

        val rejectedTechno = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Tití Me Preguntó",
            targetArtist = "Bad Bunny",
            targetDurationMs = 243_000L,
            candTitle = "Titi Me Pregunto Techno Remix",
            candArtist = "Club Producer",
            candDurationSec = 240
        )
        assertEquals(Int.MIN_VALUE, rejectedTechno)

        val cleanScore = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Tití Me Preguntó",
            targetArtist = "Bad Bunny",
            targetDurationMs = 243_000L,
            candTitle = "Titi Me Pregunto",
            candArtist = "Bad Bunny",
            candDurationSec = 244,
            isVerified = true
        )
        assertTrue("Expected high score for clean normalized studio match, was $cleanScore", cleanScore >= 180)
    }

    @Test
    fun `extractUnencryptedSoundCloudTranscodings rejects DRM cbc-encrypted-hls and ctr-encrypted-hls`() {
        val transcodings = JSONArray()
            .put(
                JSONObject()
                    .put("url", "https://api-v2.soundcloud.com/media/sc:1/drm/cbc-hls")
                    .put("snipped", false)
                    .put("format", JSONObject().put("protocol", "cbc-encrypted-hls").put("mime_type", "audio/mp4"))
            )
            .put(
                JSONObject()
                    .put("url", "https://api-v2.soundcloud.com/media/sc:1/drm/ctr-hls")
                    .put("snipped", false)
                    .put("format", JSONObject().put("protocol", "ctr-encrypted-hls").put("mime_type", "audio/mp4"))
            )
            .put(
                JSONObject()
                    .put("url", "https://api-v2.soundcloud.com/media/sc:1/preview/prog")
                    .put("snipped", true)
                    .put("format", JSONObject().put("protocol", "progressive").put("mime_type", "audio/mpeg"))
            )
            .put(
                JSONObject()
                    .put("url", "https://api-v2.soundcloud.com/media/sc:1/open/hls")
                    .put("snipped", false)
                    .put("format", JSONObject().put("protocol", "hls").put("mime_type", "audio/mp4; codecs=\"mp4a.40.2\""))
            )

        val picked = com.example.data.network.CatalogAudioFallback.extractUnencryptedSoundCloudTranscodings(
            transcodings,
            AudioQuality.MAX
        )
        assertEquals(1, picked.size)
        assertEquals("https://api-v2.soundcloud.com/media/sc:1/open/hls", picked.first().first)
        assertEquals("hls", picked.first().second)
    }

    @Test
    fun `resolveAudioStream falls back to catalog audio when YouTube returns LOGIN_REQUIRED`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath
                when {
                    host.contains("youtube.com") && path.contains("youtubei/v1/player") -> {
                        val blocked = JSONObject().put(
                            "playabilityStatus",
                            JSONObject()
                                .put("status", "LOGIN_REQUIRED")
                                .put("reason", "Sign in to confirm you're not a bot")
                        )
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(blocked.toString().toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    host.contains("saavn.sumit.co") -> {
                        val saavnJson = JSONObject().put(
                            "data",
                            JSONObject().put(
                                "results",
                                JSONArray().put(
                                    JSONObject()
                                        .put("name", "Choosin' Texas")
                                        .put("duration", 232)
                                        .put(
                                            "artists",
                                            JSONObject().put(
                                                "primary",
                                                JSONArray().put(JSONObject().put("name", "Ella Langley"))
                                            )
                                        )
                                        .put(
                                            "downloadUrl",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("quality", "320kbps")
                                                    .put("url", "https://aac.saavncdn.com/mock/choosin_320.mp4")
                                            )
                                        )
                                )
                            )
                        )
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(saavnJson.toString().toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    host == "aac.saavncdn.com" -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .body("ftyp".toResponseBody("audio/mp4".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        val resolver = StreamResolver(client)
        val track = com.example.model.Track(
            videoId = "xukbqwRuN5w",
            title = "Choosin' Texas",
            artist = "Ella Langley",
            durationText = "3:52",
            durationMs = 232_000L
        )
        val result = resolver.resolveAudioStream(track)
        assertTrue(result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("https://aac.saavncdn.com/mock/choosin_320.mp4", stream.url)
        assertEquals("audio/mp4", stream.mimeType)
        assertEquals(StreamResolver.BROWSER_USER_AGENT, resolver.getUserAgentForUrl(stream.url))
    }
}


