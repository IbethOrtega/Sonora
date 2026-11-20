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
                if (req.url.host == "audio.example") {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .body("ftyp".toResponseBody("audio/mp4".toMediaType()))
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
                if (req.url.host == "audio.example") {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .body("ftyp".toResponseBody("audio/mp4".toMediaType()))
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

    @Test
    fun `resolveAudioStream rejects HTTP 403 PoToken YouTube URLs and falls back to catalog audio`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath
                when {
                    host.contains("youtube.com") && path.contains("youtubei/v1/player") -> {
                        val okWith403Url = JSONObject()
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
                                                .put("url", "https://rr1---sn.googlevideo.com/videoplayback?id=blocked")
                                        )
                                    )
                            )
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(okWith403Url.toString().toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    host.contains("googlevideo.com") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    host.contains("saavn.sumit.co") -> {
                        val saavnJson = JSONObject().put(
                            "data",
                            JSONObject().put(
                                "results",
                                JSONArray().put(
                                    JSONObject()
                                        .put("name", "Blinding Lights")
                                        .put("duration", 200)
                                        .put(
                                            "artists",
                                            JSONObject().put(
                                                "primary",
                                                JSONArray().put(JSONObject().put("name", "The Weeknd"))
                                            )
                                        )
                                        .put(
                                            "downloadUrl",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("quality", "320kbps")
                                                    .put("url", "https://aac.saavncdn.com/mock/blinding_320.mp4")
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
            videoId = "4NRXx6U8ABQ",
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationText = "3:20",
            durationMs = 200_000L
        )
        val result = resolver.resolveAudioStream(track)
        assertTrue("Expected fallback to JioSaavn when YouTube returns 403 PoToken stream", result.isSuccess)
        assertEquals("https://aac.saavncdn.com/mock/blinding_320.mp4", result.getOrThrow().url)
    }

    @Test
    fun `YouTubeMusicClient buildAuthorizationHeader builds multi-scheme SAPISID header`() {
        val ytm = com.example.data.network.YouTubeMusicClient(OkHttpClient())
        val auth = ytm.buildAuthorizationHeader(
            cookies = "SAPISID=s1; __Secure-1PAPISID=p1; __Secure-3PAPISID=p3",
            origin = "https://music.youtube.com",
            timestampSec = 1700000000L
        )
        assertNotNull(auth)
        assertTrue(auth!!.contains("SAPISIDHASH 1700000000_"))
        assertTrue(auth.contains("SAPISID1PHASH 1700000000_"))
        assertTrue(auth.contains("SAPISID3PHASH 1700000000_"))
    }

    @Test
    fun `YouTubeMusicClient parseShelvesFromBrowse unwraps itemSectionRenderer gridRenderer and playlist tracks`() {
        val ytm = com.example.data.network.YouTubeMusicClient(OkHttpClient())
        val twoRowPlaylist = JSONObject()
            .put(
                "title",
                JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Mis Favoritas YTM")))
            )
            .put("subtitle", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Playlist • 42 canciones"))))
            .put(
                "navigationEndpoint",
                JSONObject().put("browseEndpoint", JSONObject().put("browseId", "VLPL123456"))
            )

        val trackItem = JSONObject().put(
            "musicResponsiveListItemRenderer",
            JSONObject()
                .put("playlistItemData", JSONObject().put("videoId", "vidLiked1"))
                .put(
                    "flexColumns",
                    JSONArray()
                        .put(
                            JSONObject().put(
                                "musicResponsiveListItemFlexColumnRenderer",
                                JSONObject().put(
                                    "text",
                                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Song Liked")))
                                )
                            )
                        )
                        .put(
                            JSONObject().put(
                                "musicResponsiveListItemFlexColumnRenderer",
                                JSONObject().put(
                                    "text",
                                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Artist X")))
                                )
                            )
                        )
                )
        )

        val browseJson = JSONObject().put(
            "contents",
            JSONObject().put(
                "singleColumnBrowseResultsRenderer",
                JSONObject().put(
                    "tabs",
                    JSONArray().put(
                        JSONObject().put(
                            "tabRenderer",
                            JSONObject().put(
                                "content",
                                JSONObject().put(
                                    "sectionListRenderer",
                                    JSONObject().put(
                                        "contents",
                                        JSONArray()
                                            .put(
                                                JSONObject().put(
                                                    "itemSectionRenderer",
                                                    JSONObject().put(
                                                        "contents",
                                                        JSONArray().put(
                                                            JSONObject().put(
                                                                "gridRenderer",
                                                                JSONObject().put(
                                                                    "items",
                                                                    JSONArray().put(
                                                                        JSONObject().put("musicTwoRowItemRenderer", twoRowPlaylist)
                                                                    )
                                                                )
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                            .put(
                                                JSONObject().put(
                                                    "musicPlaylistShelfRenderer",
                                                    JSONObject().put(
                                                        "contents",
                                                        JSONArray().put(trackItem)
                                                    )
                                                )
                                            )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val shelves = ytm.parseShelvesFromBrowse(browseJson)
        val cards = shelves.flatMap { it.items }
        val tracks = shelves.flatMap { it.tracks }
        assertEquals(1, cards.size)
        assertEquals("VLPL123456", cards.first().id)
        assertEquals("Mis Favoritas YTM", cards.first().title)
        assertEquals(1, tracks.size)
        assertEquals("vidLiked1", tracks.first().videoId)
    }

    @Test
    fun `invalidateYouTubeSources skips remaining InnerTube profiles straight to catalog fallback`() = runBlocking {
        val playerCalls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath
                when {
                    host.contains("youtube.com") && path.contains("youtubei/v1/player") -> {
                        val buf = okio.Buffer()
                        req.body?.writeTo(buf)
                        val clientName = JSONObject(buf.readUtf8())
                            .optJSONObject("context")?.optJSONObject("client")?.optString("clientName")
                            .orEmpty()
                        playerCalls.add(clientName)
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
                                                .put("url", "https://rr1---sn.googlevideo.com/videoplayback?id=$clientName")
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
                    host.contains("googlevideo.com") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .body("ftyp".toResponseBody("audio/mp4".toMediaType()))
                            .build()
                    }
                    host.contains("saavn.sumit.co") -> {
                        val saavnJson = JSONObject().put(
                            "data",
                            JSONObject().put(
                                "results",
                                JSONArray().put(
                                    JSONObject()
                                        .put("name", "BbY WOW")
                                        .put("duration", 226)
                                        .put(
                                            "artists",
                                            JSONObject().put(
                                                "primary",
                                                JSONArray().put(JSONObject().put("name", "KAROL G"))
                                            )
                                        )
                                        .put(
                                            "downloadUrl",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("quality", "320kbps")
                                                    .put("url", "https://aac.saavncdn.com/mock/bby_wow_320.mp4")
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
            videoId = "ljAH6jNaApU",
            title = "BbY WOW",
            artist = "KAROL G, rusowsky",
            durationText = "3:46",
            durationMs = 226_000L
        )
        val first = resolver.resolveAudioStream(track)
        assertTrue(first.isSuccess)
        assertTrue(first.getOrThrow().url.contains("googlevideo.com"))
        assertEquals(1, playerCalls.size)

        // Simulate runtime chunk-2 403 PoToken error during ExoPlayer playback
        resolver.invalidateYouTubeSources(track.videoId)
        val second = resolver.resolveAudioStream(track)
        assertTrue("Expected direct bypass to catalog fallback", second.isSuccess)
        assertEquals("https://aac.saavncdn.com/mock/bby_wow_320.mp4", second.getOrThrow().url)
        // Must NOT waste retries calling IOS / ANDROID / ANDROID_MUSIC / TVHTML5
        assertEquals(1, playerCalls.size)
    }

    @Test
    fun `scoreCatalogCandidate credits co-artist match in multi-artist collaborations`() {
        val collabScore = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "BbY WOW",
            targetArtist = "KAROL G, rusowsky",
            targetDurationMs = 226_000L,
            candTitle = "BbY WOW",
            candArtist = "rusowsky",
            candDurationSec = 226,
            isVerified = true
        )
        assertTrue("Expected co-artist collaboration score >= 180, was $collabScore", collabScore >= 180)
    }

    @Test
    fun `scoreCatalogCandidate rejects wrong artist covers remixes live and version mismatches`() {
        // Same title, completely different artist -> MUST be rejected
        assertEquals(
            Int.MIN_VALUE,
            com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
                targetTitle = "Luna",
                targetArtist = "Feid",
                targetDurationMs = 196_000L,
                candTitle = "Luna",
                candArtist = "Zoé",
                candDurationSec = 196,
                isVerified = true
            )
        )

        // Cover / En Vivo / Remix must NEVER match studio target
        assertEquals(
            Int.MIN_VALUE,
            com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
                targetTitle = "Despacito",
                targetArtist = "Luis Fonsi",
                targetDurationMs = 228_000L,
                candTitle = "Despacito (Cover)",
                candArtist = "Luis Fonsi",
                candDurationSec = 228,
                isVerified = true
            )
        )
        assertEquals(
            Int.MIN_VALUE,
            com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
                targetTitle = "Despacito",
                targetArtist = "Luis Fonsi",
                targetDurationMs = 228_000L,
                candTitle = "Despacito (En Vivo)",
                candArtist = "Luis Fonsi",
                candDurationSec = 230,
                isVerified = true
            )
        )

        // Studio track must NEVER replace explicit Remix request
        assertEquals(
            Int.MIN_VALUE,
            com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
                targetTitle = "Despacito (Remix)",
                targetArtist = "Luis Fonsi, Daddy Yankee, Justin Bieber",
                targetDurationMs = 229_000L,
                candTitle = "Despacito",
                candArtist = "Luis Fonsi, Daddy Yankee",
                candDurationSec = 228,
                isVerified = true
            )
        )
    }

    @Test
    fun `scoreCatalogCandidate rejects cover uploaders regional indian tracks and unknown target artist`() {
        // Unverified fan uploader whose title puts artist name in upload title while uploader differs
        assertEquals(
            Int.MIN_VALUE,
            com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
                targetTitle = "Tití Me Preguntó",
                targetArtist = "Bad Bunny",
                targetDurationMs = 243_000L,
                candTitle = "Bad Bunny - Titi Me Pregunto",
                candArtist = "Juanito Covers99",
                candDurationSec = 243,
                isVerified = false
            )
        )

        // Regional Indian catalog track must be rejected for non-Indian track
        assertEquals(
            Int.MIN_VALUE,
            com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
                targetTitle = "Luna",
                targetArtist = "Feid",
                targetDurationMs = 196_000L,
                candTitle = "Luna",
                candArtist = "Feid",
                candDurationSec = 196,
                isVerified = true,
                candLanguage = "hindi"
            )
        )

        // Non-Latin Devanagari track must be rejected when target is Latin script
        assertEquals(
            Int.MIN_VALUE,
            com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
                targetTitle = "Soltera",
                targetArtist = "Shakira",
                targetDurationMs = 180_000L,
                candTitle = "Soltera नमस्ते",
                candArtist = "Shakira",
                candDurationSec = 180,
                isVerified = true
            )
        )

        // Empty or generic placeholder artist must never allow title-only fuzzy match
        assertEquals(
            Int.MIN_VALUE,
            com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
                targetTitle = "Blinding Lights",
                targetArtist = "YouTube Music",
                targetDurationMs = 200_000L,
                candTitle = "Blinding Lights",
                candArtist = "The Weeknd",
                candDurationSec = 200,
                isVerified = true
            )
        )
    }

    @Test
    fun `scoreCatalogCandidate accepts Taylors Version VEVO Artist-Title genre artist names and MV duration variance`() {
        // 1. "(Taylor's Version)" + VEVO collapsed artist name must not be blocked by bare "version" or "VEVO"
        val taylorsVersionScore = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Style (Taylor's Version)",
            targetArtist = "TaylorSwiftVEVO",
            targetDurationMs = 231_000L,
            candTitle = "Style (Taylor's Version)",
            candArtist = "Taylor Swift",
            candDurationSec = 231,
            isVerified = true
        )
        assertTrue("Expected Taylor's Version + VEVO to score >= 150, was $taylorsVersionScore", taylorsVersionScore >= 150)

        // 2. "Artist - Title (Official Video)" recovers artist when metadata artist is generic
        val artistDashTitleScore = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Bad Bunny - Tití Me Preguntó (Official Video)",
            targetArtist = "YouTube Music",
            targetDurationMs = 243_000L,
            candTitle = "Titi Me Pregunto",
            candArtist = "Bad Bunny",
            candDurationSec = 243,
            isVerified = true
        )
        assertTrue("Expected Artist - Title to recover artist and score >= 150, was $artistDashTitleScore", artistDashTitleScore >= 150)

        // 3. Genre word inside an authentic artist name (e.g., Banda MS) must not block official song
        val bandaMsScore = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "El Color de Tus Ojos",
            targetArtist = "Banda MS",
            targetDurationMs = 215_000L,
            candTitle = "El Color de Tus Ojos",
            candArtist = "Banda MS",
            candDurationSec = 215,
            isVerified = true
        )
        assertTrue("Expected Banda MS to score >= 150, was $bandaMsScore", bandaMsScore >= 150)

        // 4. Music video with 45s intro/outro (260s) must still match studio master (215s) while 30s preview stays blocked
        val mvDiffScore = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Starboy",
            targetArtist = "The Weeknd",
            targetDurationMs = 260_000L,
            candTitle = "Starboy",
            candArtist = "The Weeknd",
            candDurationSec = 215,
            isVerified = true
        )
        assertTrue("Expected MV duration difference within 30% to score >= 150, was $mvDiffScore", mvDiffScore >= 150)

        val preview30sScore = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Starboy",
            targetArtist = "The Weeknd",
            targetDurationMs = 230_000L,
            candTitle = "Starboy",
            candArtist = "The Weeknd",
            candDurationSec = 30,
            isVerified = true
        )
        assertEquals(Int.MIN_VALUE, preview30sScore)
    }

    @Test
    fun `invalidateYouTubeSources is scoped per videoId and does not block subsequent unrelated tracks`() = runBlocking {
        val playerVideoIds = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                when {
                    path.contains("sw.js_data") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(")]}'\n[[\"Cgt2aXNpdG9yMTIzNDU2Nzg5MA\"]]".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    path.contains("youtubei/v1/player") -> {
                        val buf = okio.Buffer()
                        req.body?.writeTo(buf)
                        val vid = JSONObject(buf.readUtf8()).optString("videoId")
                        playerVideoIds.add(vid)
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
                                                .put("url", "https://rr1---sn.googlevideo.com/videoplayback?vid=$vid")
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
                    req.url.host.contains("googlevideo.com") -> {
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
        resolver.invalidateYouTubeSources("blocked_vid_A")
        val nextTrackResult = resolver.resolveAudioStream(
            com.example.model.Track(
                videoId = "healthy_vid_B",
                title = "Cruel Summer",
                artist = "Taylor Swift",
                durationText = "2:58",
                durationMs = 178_000L
            )
        )
        assertTrue("Expected healthy_vid_B to resolve via direct YouTube without global cooldown block", nextTrackResult.isSuccess)
        assertTrue(nextTrackResult.getOrThrow().url.contains("healthy_vid_B"))
        assertTrue(playerVideoIds.contains("healthy_vid_B"))
    }

    @Test
    fun `YouTubeMusicClient parseArtistAndDurationFromSubtitle extracts clean artist and duration for Style`() {
        val ytm = com.example.data.network.YouTubeMusicClient(OkHttpClient())
        val (artist1, dur1) = ytm.parseArtistAndDurationFromSubtitle("Canción • Taylor Swift • 3:51")
        assertEquals("Taylor Swift", artist1)
        assertEquals("3:51", dur1)

        val (artist2, dur2) = ytm.parseArtistAndDurationFromSubtitle("Song • Taylor Swift • 850M views • 3:51")
        assertEquals("Taylor Swift", artist2)
        assertEquals("3:51", dur2)
    }

    @Test
    fun `scoreCatalogCandidate handles nested parentheses hyphenated Taylors Version and bulleted artist strings`() {
        val nestedParens = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Style",
            targetArtist = "Taylor Swift • 3:51",
            targetDurationMs = 231_000L,
            candTitle = "Style (From \"1989 (Taylor's Version)\")",
            candArtist = "Taylor Swift",
            candDurationSec = 231,
            isVerified = true
        )
        assertTrue("Expected nested parens + bulleted artist to score >= 150, was $nestedParens", nestedParens >= 150)

        val dashVersion = com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = "Style",
            targetArtist = "Taylor Swift",
            targetDurationMs = 231_000L,
            candTitle = "Style - Taylor's Version",
            candArtist = "Taylor Swift",
            candDurationSec = 231,
            isVerified = true
        )
        assertTrue("Expected dash Taylor's Version to score >= 150, was $dashVersion", dashVersion >= 150)
    }

    @Test
    fun `resolveAudioStream falls back through JioSaavn 320 to 96 kbps and uses TVHTML5_SIMPLY_EMBEDDED_PLAYER bypass`() = runBlocking {
        // Part A: JioSaavn 320 & 160 return 403/404, 96 succeeds
        val saavnFallbackClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath
                when {
                    host.contains("youtube.com") && path.contains("youtubei/v1/player") -> {
                        val blocked = JSONObject().put(
                            "playabilityStatus",
                            JSONObject().put("status", "LOGIN_REQUIRED")
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
                                        .put("name", "Style")
                                        .put("duration", 231)
                                        .put(
                                            "artists",
                                            JSONObject().put(
                                                "primary",
                                                JSONArray().put(JSONObject().put("name", "Taylor Swift"))
                                            )
                                        )
                                        .put(
                                            "downloadUrl",
                                            JSONArray()
                                                .put(JSONObject().put("quality", "96kbps").put("url", "https://aac.saavncdn.com/mock/style_96.mp4"))
                                                .put(JSONObject().put("quality", "160kbps").put("url", "https://aac.saavncdn.com/mock/style_160.mp4"))
                                                .put(JSONObject().put("quality", "320kbps").put("url", "https://aac.saavncdn.com/mock/style_320.mp4"))
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
                    host == "aac.saavncdn.com" && path.endsWith("_96.mp4") -> {
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

        val resolverA = StreamResolver(saavnFallbackClient)
        val resA = resolverA.resolveAudioStream(
            com.example.model.Track(
                videoId = "style_yt_blocked",
                title = "Style",
                artist = "Taylor Swift",
                durationText = "3:51",
                durationMs = 231_000L
            )
        )
        assertTrue("Expected JioSaavn 96kbps fallback to succeed", resA.isSuccess)
        assertEquals("https://aac.saavncdn.com/mock/style_96.mp4", resA.getOrThrow().url)

        // Part B: TVHTML5_SIMPLY_EMBEDDED_PLAYER succeeds when non-embed profiles return LOGIN_REQUIRED,
        // and googlevideo redirect subdomain preserves YouTube UA
        val embedBypassClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath
                when {
                    path.contains("sw.js_data") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(")]}'\n[[\"Cgt2aXNpdG9yMTIzNDU2Nzg5MA\"]]".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    host.contains("youtube.com") && path.contains("/embed/") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("{\"STS\":20131}".toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                    host.contains("youtube.com") && path.contains("youtubei/v1/player") -> {
                        val buf = okio.Buffer()
                        req.body?.writeTo(buf)
                        val clientName = JSONObject(buf.readUtf8())
                            .optJSONObject("context")?.optJSONObject("client")?.optString("clientName")
                            .orEmpty()
                        val respJson = if (clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                            JSONObject()
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
                                                    .put("url", "https://rr1---sn.googlevideo.com/videoplayback?id=embed_ok")
                                            )
                                        )
                                )
                        } else {
                            JSONObject().put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED"))
                        }
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(respJson.toString().toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    host.endsWith("googlevideo.com") -> {
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

        val resolverB = StreamResolver(embedBypassClient)
        val resB = resolverB.resolveAudioStream(
            com.example.model.Track(
                videoId = "style_embed_ok",
                title = "Style",
                artist = "Taylor Swift",
                durationText = "3:51",
                durationMs = 231_000L
            )
        )
        assertTrue("Expected TVHTML5_SIMPLY_EMBEDDED_PLAYER to bypass LOGIN_REQUIRED", resB.isSuccess)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?id=embed_ok", resB.getOrThrow().url)
        assertEquals(
            resB.getOrThrow().userAgent,
            resolverB.getUserAgentForHost("rr9---sn-redirect.googlevideo.com")
        )
    }

    @Test
    fun `signed-in session keeps ANDROID_VR primary and sends delegated authUser headers on auth fallback`() = runBlocking {
        val playerCalls = mutableListOf<Triple<String, String?, String?>>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath
                when {
                    host.contains("youtube.com") && path.contains("youtubei/v1/player") -> {
                        val buf = okio.Buffer()
                        req.body?.writeTo(buf)
                        val bodyJson = JSONObject(buf.readUtf8())
                        val clientName = bodyJson.optJSONObject("context")?.optJSONObject("client")?.optString("clientName").orEmpty()
                        val onBehalf = bodyJson.optJSONObject("context")?.optJSONObject("user")?.optString("onBehalfOfUser")
                        val authUserHdr = req.header("X-Goog-AuthUser")
                        playerCalls.add(Triple(clientName, authUserHdr, onBehalf))
                        val respJson = if (clientName == "ANDROID_VR" || clientName == "ANDROID_MUSIC") {
                            JSONObject()
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
                                                    .put("url", "https://rr1---sn.googlevideo.com/videoplayback?c=$clientName")
                                            )
                                        )
                                )
                        } else {
                            JSONObject().put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED"))
                        }
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(respJson.toString().toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    host.endsWith("googlevideo.com") -> {
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
        resolver.setSessionContext(
            com.example.model.YouTubeAuthSessionPayload(
                cookies = "SAPISID=s1; __Secure-3PAPISID=p3",
                visitorData = "Cgt2aXNpdG9ySigned",
                delegatedSessionId = "brand_page_99",
                authUser = "2"
            )
        )

        val first = resolver.resolveAudioStream("signed_vid")
        assertTrue(first.isSuccess)
        assertEquals("ANDROID_VR", playerCalls.first().first)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?c=ANDROID_VR", first.getOrThrow().url)

        resolver.invalidate("signed_vid")
        val second = resolver.resolveAudioStream("signed_vid")
        assertTrue(second.isSuccess)
        val musicCall = playerCalls.firstOrNull { it.first == "ANDROID_MUSIC" }
        assertNotNull("Expected auth fallback client ANDROID_MUSIC to be attempted", musicCall)
        assertEquals("2", musicCall!!.second)
        assertEquals("brand_page_99", musicCall.third)
    }

    @Test
    fun `getUserAgentForUrl infers matching client User-Agent from c query param`() {
        val resolver = StreamResolver(OkHttpClient())
        val iosUa = resolver.getUserAgentForUrl("https://rr2---sn.googlevideo.com/videoplayback?itag=140&c=IOS")
        val vrUa = resolver.getUserAgentForUrl("https://rr2---sn.googlevideo.com/videoplayback?itag=251&c=ANDROID_VR")
        val webUa = resolver.getUserAgentForUrl("https://rr2---sn.googlevideo.com/videoplayback?itag=251&c=WEB_REMIX")
        assertNotNull(iosUa)
        assertTrue(iosUa!!.contains("iPhone"))
        assertNotNull(vrUa)
        assertTrue(vrUa!!.contains("vr.oculus"))
        assertEquals(StreamResolver.BROWSER_USER_AGENT, webUa)
    }
}
