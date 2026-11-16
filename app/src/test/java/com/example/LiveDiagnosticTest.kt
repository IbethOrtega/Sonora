package com.example

import com.example.data.network.StreamResolver
import com.example.data.network.YouTubeMusicClient
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LiveDiagnosticTest {

    @Test
    fun verifyRealProductionStreamResolverEndToEnd(): Unit = runBlocking {
        val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val ytm = YouTubeMusicClient(http)
        val resolver = StreamResolver(http)

        val homeTracks = runCatching { ytm.getHomeShelves() }
            .getOrDefault(emptyList())
            .flatMap { it.tracks }
            .distinctBy { it.videoId }
            .take(4)

        val catalogChecks = listOf(
            Track(
                videoId = "xukbqwRuN5w",
                title = "Choosin' Texas",
                artist = "Ella Langley",
                durationText = "3:52",
                durationMs = 232_000L
            ),
            Track(
                videoId = "Cr8K88UcO0s",
                title = "Tití Me Preguntó",
                artist = "Bad Bunny",
                durationText = "4:03",
                durationMs = 243_000L
            ),
            Track(
                videoId = "4NRXx6U8ABQ",
                title = "Blinding Lights",
                artist = "The Weeknd",
                durationText = "3:20",
                durationMs = 200_000L
            ),
            Track(
                videoId = "kJQP7kiw5Fk",
                title = "Despacito",
                artist = "Luis Fonsi, Daddy Yankee",
                durationText = "4:41",
                durationMs = 281_000L
            )
        )

        val allToTest = (homeTracks + catalogChecks).distinctBy { it.videoId }
        println("Testing ${allToTest.size} tracks (home=${homeTracks.size})")

        var successes = 0
        for (track in allToTest) {
            val t0 = System.currentTimeMillis()
            val res = resolver.resolveAudioStream(track)
            val dt = System.currentTimeMillis() - t0
            if (res.isSuccess) {
                val s = res.getOrThrow()
                val req = Request.Builder()
                    .url(s.url)
                    .header("User-Agent", s.userAgent)
                    .apply {
                        if (!s.mimeType.contains("mpegURL", true) && !s.url.contains(".m3u8", true)) {
                            header("Range", "bytes=0-1")
                        }
                    }
                    .get()
                    .build()
                val probe = http.newCall(req).execute().use { r ->
                    val isHls = s.mimeType.contains("mpegURL", true) || s.url.contains(".m3u8", true)
                    if (isHls) {
                        val body = r.body?.string().orEmpty()
                        val drm = body.contains("SAMPLE-AES", true) || body.contains("skd://", true)
                        (r.code in 200..299) && body.contains("#EXTM3U") && !drm
                    } else {
                        r.code in 200..299 || r.code == 206
                    }
                }
                if (probe) successes++
                println("OK [${track.title} - ${track.artist}] in ${dt}ms -> codec=${s.codecLabel} mime=${s.mimeType} playable=$probe url=${s.url.take(80)}")
                assertTrue("Expected playable stream for ${track.title}", probe)
            } else {
                println("FAIL [${track.title} - ${track.artist}] in ${dt}ms -> ${res.exceptionOrNull()?.message}")
            }
        }

        // Also test bare videoId oEmbed auto-hint path (after clearing cache & metadata)
        val bareResolver = StreamResolver(http)
        val tBare = System.currentTimeMillis()
        val bareRes = bareResolver.resolveAudioStream("xukbqwRuN5w")
        val dtBare = System.currentTimeMillis() - tBare
        println("BARE videoId xukbqwRuN5w in ${dtBare}ms -> success=${bareRes.isSuccess} url=${bareRes.getOrNull()?.url?.take(80)}")
        assertTrue("Expected bare videoId resolution via oEmbed fallback to succeed", bareRes.isSuccess)
        assertTrue("Expected all tracks to resolve cleanly, got $successes/${allToTest.size}", successes == allToTest.size)
    }
}
