package com.example

import com.example.data.network.StreamResolver
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
    fun `resolveAudioStream rotates to next profile after invalidate`() = runBlocking {
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
                val bodyStr = req.body?.let { body ->
                    val buf = okio.Buffer()
                    body.writeTo(buf)
                    buf.readUtf8()
                }.orEmpty()
                val clientName = JSONObject(bodyStr)
                    .getJSONObject("context")
                    .getJSONObject("client")
                    .getString("clientName")
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

        resolver.invalidate("vid123")
        val second = resolver.resolveAudioStream("vid123")
        assertTrue(second.isSuccess)
        assertEquals("https://audio.example/ANDROID_EMBEDDED_PLAYER", second.getOrThrow().url)
        assertEquals(listOf("ANDROID_VR", "ANDROID_EMBEDDED_PLAYER"), calls)
    }
}
