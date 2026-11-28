package com.example

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.Color
import com.example.data.network.CanvasRepository
import com.example.model.CanvasSource
import com.example.model.CanvasVariant
import com.example.ui.player.shouldAutoHidePlayerControls
import com.example.ui.theme.buildCanvasBottomFadeStops
import com.example.ui.theme.canvasBottomFadeAlphaAt
import com.example.ui.theme.computeDynamicCanvasColors
import com.example.ui.theme.rgbToHsl
import com.example.ui.theme.sampleBitmapBottomStripAverageRgb
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppleMusicCanvasTest {

    private var fakeNowMs = 1_760_000_000_000L

    @Before
    fun setUp() {
        CanvasRepository.clearCache()
        fakeNowMs = 1_760_000_000_000L
        CanvasRepository.nowMillisProvider = { fakeNowMs }
        CanvasRepository.defaultCountryProvider = { "MX" }
    }

    @After
    fun tearDown() {
        CanvasRepository.clearCache()
        CanvasRepository.nowMillisProvider = { System.currentTimeMillis() }
        CanvasRepository.defaultCountryProvider = { java.util.Locale.getDefault().country }
    }

    private fun makeJwt(headerJson: String, payloadJson: String, sig: String = "sig123456789"): String {
        val h = Base64.encodeToString(headerJson.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val p = Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "$h.$p.$sig"
    }

    @Test
    fun `extractAppleBundlePaths and selectBestJwtFromScript prefer WebPlayKid or AMPWebPlay and skip expired or rejected tokens`() {
        val html = """
            <html><head>
            <script type="module" src="/assets/index-deadbeef.js"></script>
            <script nomodule src="/assets/index-legacy~cafe1234.js"></script>
            </head></html>
        """.trimIndent()
        val paths = CanvasRepository.extractAppleBundlePaths(html)
        assertEquals(listOf("/assets/index-deadbeef.js", "/assets/index-legacy~cafe1234.js"), paths)

        val nowSec = fakeNowMs / 1000L
        val expiredJwt = makeJwt("""{"alg":"ES256"}""", """{"exp":${nowSec - 10}}""")
        val plainJwt = makeJwt("""{"alg":"ES256","kid":"OtherKid"}""", """{"exp":${nowSec + 3600},"iss":"Generic"}""")
        val preferredJwt = makeJwt("""{"alg":"ES256","kid":"WebPlayKid9"}""", """{"exp":${nowSec + 7200},"sub":"AMPWebPlay"}""")
        val bundleJs = """var a="$expiredJwt", b="$plainJwt", c="$preferredJwt";"""

        val chosen = CanvasRepository.selectBestJwtFromScript(bundleJs, nowSec = nowSec)
        assertNotNull(chosen)
        assertEquals(preferredJwt, chosen!!.token)
        assertTrue(chosen.isPreferred)

        // When preferred is rejected (e.g., 401), falls back to non-rejected valid JWT
        val fallback = CanvasRepository.selectBestJwtFromScript(
            bundleJs,
            nowSec = nowSec,
            rejected = setOf(preferredJwt)
        )
        assertNotNull(fallback)
        assertEquals(plainJwt, fallback!!.token)
        assertFalse(fallback.isPreferred)
    }

    @Test
    fun `fetchCanvas resolves Taylor Swift Style motionDetailTall shares album cache and never hits iTunes musicVideo`() = runBlocking {
        val nowSec = fakeNowMs / 1000L
        val validToken = makeJwt(
            headerJson = """{"alg":"ES256","kid":"WebPlayKid"}""",
            payloadJson = """{"exp":${nowSec + 3600},"scope":"AMPWebPlay"}"""
        )

        val browseHits = AtomicInteger(0)
        val searchHits = AtomicInteger(0)
        val albumHits = AtomicInteger(0)
        var sawItunesMusicVideo = false
        var sawRequiredHeaders = false

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.contains("itunes.apple.com") || url.contains("entity=musicVideo")) {
                    sawItunesMusicVideo = true
                }
                val (code, body, contentType) = when {
                    url == "https://music.apple.com/us/browse" -> {
                        browseHits.incrementAndGet()
                        Triple(200, """<script src="/assets/index-1989tv.js"></script>""", "text/html")
                    }
                    url == "https://music.apple.com/assets/index-1989tv.js" -> {
                        Triple(200, """const t="$validToken";""", "application/javascript")
                    }
                    url.startsWith("https://amp-api.music.apple.com/v1/catalog/mx/search") -> {
                        searchHits.incrementAndGet()
                        sawRequiredHeaders =
                            req.header("Authorization") == "Bearer $validToken" &&
                            req.header("Origin") == "https://music.apple.com" &&
                            req.header("Referer") == "https://music.apple.com/" &&
                            !req.header("User-Agent").isNullOrBlank()

                        val responseJson = """
                            {
                              "results": {
                                "songs": {
                                  "data": [
                                    {
                                      "id": "999",
                                      "attributes": {
                                        "name": "Taylor Swift Greatest Hits",
                                        "artistName": "Taylor Swift",
                                        "albumName": "Greatest Hits Collection",
                                        "url": "https://music.apple.com/mx/album/greatest-hits/999"
                                      }
                                    },
                                    {
                                      "id": "1708308989",
                                      "attributes": {
                                        "name": "Style (Taylor's Version)",
                                        "artistName": "Taylor Swift",
                                        "albumName": "1989 (Taylor's Version)",
                                        "url": "https://music.apple.com/mx/album/1989-taylors-version/1708308980"
                                      },
                                      "relationships": {
                                        "albums": { "data": [ { "id": "1708308980", "type": "albums" } ] }
                                      }
                                    }
                                  ]
                                }
                              }
                            }
                        """.trimIndent()
                        Triple(200, responseJson, "application/json")
                    }
                    url.startsWith("https://amp-api.music.apple.com/v1/catalog/mx/albums/1708308980") -> {
                        albumHits.incrementAndGet()
                        val albJson = """
                            {
                              "data": [
                                {
                                  "id": "1708308980",
                                  "attributes": {
                                    "name": "1989 (Taylor's Version)",
                                    "isCompilation": false,
                                    "editorialVideo": {
                                      "motionDetailTall": { "video": "https://mvod.itunes.apple.com/1989tv_tall.m3u8" },
                                      "motionDetailSquare": { "video": "https://mvod.itunes.apple.com/1989tv_sq.m3u8" }
                                    }
                                  }
                                }
                              ]
                            }
                        """.trimIndent()
                        Triple(200, albJson, "application/json")
                    }
                    else -> Triple(404, "{}", "application/json")
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "ERR")
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            })
            .build()

        CanvasRepository.configure(mockClient)

        val styleArt = CanvasRepository.fetchCanvas(
            videoId = "yt_style_tv",
            title = "Style (Taylor's Version)",
            artist = "Taylor Swift",
            album = "1989 (Taylor's Version)"
        )

        assertFalse(sawItunesMusicVideo)
        assertTrue(sawRequiredHeaders)
        assertNotNull(styleArt)
        assertEquals(CanvasSource.APPLE, styleArt!!.source)
        assertEquals(CanvasVariant.APPLE_VERTICAL, styleArt.variant)
        assertEquals("https://mvod.itunes.apple.com/1989tv_tall.m3u8", styleArt.primaryStreamUrl(preferPortrait = true))
        assertEquals("Apple Music (vertical)", styleArt.menuStatusLabel(preferPortrait = true))
        assertEquals("1708308980", styleArt.appleAlbumId)
        assertEquals(1, albumHits.get())

        // Sibling track from the exact same album shares the album animated artwork cleanly
        val blankSpaceArt = CanvasRepository.fetchCanvas(
            videoId = "yt_blank_space_tv",
            title = "Blank Space (Taylor's Version)",
            artist = "Taylor Swift",
            album = "1989 (Taylor's Version)"
        )
        assertNotNull(blankSpaceArt)
        assertEquals("https://mvod.itunes.apple.com/1989tv_tall.m3u8", blankSpaceArt!!.primaryStreamUrl(preferPortrait = true))
        assertEquals(1, albumHits.get())
        assertEquals(1, browseHits.get())
    }

    @Test
    fun `fetchCanvas triggers 30-min backoff when no Apple token available and switches token on 401`() = runBlocking {
        val browseCount = AtomicInteger(0)
        val failClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (req.url.toString() == "https://music.apple.com/us/browse") {
                    browseCount.incrementAndGet()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Unavailable")
                        .body("".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        CanvasRepository.configure(failClient)
        val diag1 = CanvasRepository.runDiagnostic("Style", "Taylor Swift", "1989")
        assertNull(diag1.resolvedArtwork)
        assertEquals(1, browseCount.get())

        // Advance only 5 minutes -> still inside 30-minute backoff, so /us/browse is NOT hammered again
        fakeNowMs += 5 * 60_000L
        val diag2 = CanvasRepository.runDiagnostic("Style", "Taylor Swift", "1989")
        assertNull(diag2.resolvedArtwork)
        assertEquals(1, browseCount.get())
    }

    @Test
    fun `scoreAppleSongCandidate enforces compilation rejection artist presence and weighted score`() {
        val exactMatch = JSONObject(
            """
            {
              "id": "100",
              "attributes": {
                "name": "Style (Taylor's Version)",
                "artistName": "Taylor Swift",
                "albumName": "1989 (Taylor's Version)",
                "url": "https://music.apple.com/us/album/1989-taylors-version/1708308980"
              }
            }
            """.trimIndent()
        )
        val scoredExact = CanvasRepository.scoreAppleSongCandidate(
            item = exactMatch,
            reqTitle = "Style (Taylor's Version)",
            reqArtist = "Taylor Swift",
            reqAlbum = "1989 (Taylor's Version)"
        )
        // Base 10 + exact title 15 + exact album 20 + matching "Taylor's Version" 5 = 50
        assertEquals(50, scoredExact.score)
        assertNull(scoredExact.rejectedReason)

        val compilationCandidate = JSONObject(
            """
            {
              "id": "101",
              "attributes": {
                "name": "Style",
                "artistName": "Taylor Swift",
                "albumName": "Pop Essentials"
              }
            }
            """.trimIndent()
        )
        val scoredComp = CanvasRepository.scoreAppleSongCandidate(compilationCandidate, "Style", "Taylor Swift", "1989")
        assertEquals(Int.MIN_VALUE, scoredComp.score)
        assertNotNull(scoredComp.rejectedReason)

        val wrongArtistCandidate = JSONObject(
            """
            {
              "id": "102",
              "attributes": {
                "name": "Style (Taylor's Version)",
                "artistName": "Cover Band",
                "albumName": "1989 (Taylor's Version)"
              }
            }
            """.trimIndent()
        )
        val scoredWrongArtist = CanvasRepository.scoreAppleSongCandidate(wrongArtistCandidate, "Style (Taylor's Version)", "Taylor Swift", "1989 (Taylor's Version)")
        assertEquals(Int.MIN_VALUE, scoredWrongArtist.score)
        assertTrue(scoredWrongArtist.rejectedReason?.contains("taylor swift") == true)
    }

    @Test
    fun `sampleBitmapBottomStripAverageRgb averages bottom 20 percent and computeDynamicCanvasColors obeys dark and light HSL bounds`() {
        val bmp = Bitmap.createBitmap(64, 100, Bitmap.Config.ARGB_8888)
        // Top 80 rows bright red, bottom 20 rows rich sky blue -> only bottom 20% should determine color
        for (y in 0 until 80) {
            for (x in 0 until 64) {
                bmp.setPixel(x, y, android.graphics.Color.rgb(240, 20, 20))
            }
        }
        for (y in 80 until 100) {
            for (x in 0 until 64) {
                bmp.setPixel(x, y, android.graphics.Color.rgb(60, 140, 230))
            }
        }

        val sampledRgb = sampleBitmapBottomStripAverageRgb(bmp, stripFraction = 0.20f, targetGrid = 32)
        bmp.recycle()
        assertNotNull(sampledRgb)
        val b = sampledRgb!! and 0xFF
        val r = (sampledRgb ushr 16) and 0xFF
        assertTrue("Expected blue-dominant bottom sample, got R=$r B=$b", b > r + 100)

        val spec = computeDynamicCanvasColors(avgBottomRgb = sampledRgb)
        val topHsl = rgbToHsl(spec.topColor.red, spec.topColor.green, spec.topColor.blue)
        val botHsl = rgbToHsl(spec.bottomColor.red, spec.bottomColor.green, spec.bottomColor.blue)
        val tintHsl = rgbToHsl(spec.progressTint.red, spec.progressTint.green, spec.progressTint.blue)

        assertTrue("top saturation=${topHsl[1]}", topHsl[1] in 0.34f..0.71f)
        assertTrue("top lightness=${topHsl[2]}", topHsl[2] in 0.17f..0.33f)
        assertTrue("bottom darker than top: bot=${botHsl[2]} top=${topHsl[2]}", botHsl[2] < topHsl[2])
        assertTrue("light tint L in 0.75..0.85: ${tintHsl[2]}", tintHsl[2] in 0.74f..0.86f)
    }

    @Test
    fun `canvasBottomFadeAlphaAt and 16-stop smootherstep dissolve smoothly without visible steps`() {
        val stops = buildCanvasBottomFadeStops(fadeBottomFraction = 0.38f, stopCount = 16)
        assertEquals(17, stops.size)
        assertEquals(1f, stops.first().second, 0.0001f)
        assertEquals(0f, stops.last().second, 0.0001f)

        // Check continuity across 500 vertical pixels (~220dp+ portrait fade region): adjacent pixel alpha delta < 3%
        val totalPx = 500
        var prevAlpha = 1f
        for (py in 0..totalPx) {
            val alpha = canvasBottomFadeAlphaAt(py.toFloat() / totalPx.toFloat(), fadeBottomFraction = 0.38f)
            assertTrue("Alpha must monotonically decrease or hold", alpha <= prevAlpha + 0.0001f)
            assertTrue("Adjacent row alpha jump too high: ${abs(prevAlpha - alpha)}", abs(prevAlpha - alpha) < 0.02f)
            prevAlpha = alpha
        }
        assertEquals(0f, prevAlpha, 0.0001f)
    }

    @Test
    fun `shouldAutoHidePlayerControls hides only when enabled, animated video active, playing, and overlays closed`() {
        assertTrue(shouldAutoHidePlayerControls(autoHideEnabled = true, hasAnimatedVideo = true, isPlaying = true, isPlayerSurfaceActive = true))
        assertFalse(shouldAutoHidePlayerControls(autoHideEnabled = false, hasAnimatedVideo = true, isPlaying = true, isPlayerSurfaceActive = true))
        assertFalse(shouldAutoHidePlayerControls(autoHideEnabled = true, hasAnimatedVideo = false, isPlaying = true, isPlayerSurfaceActive = true))
        assertFalse(shouldAutoHidePlayerControls(autoHideEnabled = true, hasAnimatedVideo = true, isPlaying = false, isPlayerSurfaceActive = true))
        assertFalse(shouldAutoHidePlayerControls(autoHideEnabled = true, hasAnimatedVideo = true, isPlaying = true, isPlayerSurfaceActive = false))
    }
}
