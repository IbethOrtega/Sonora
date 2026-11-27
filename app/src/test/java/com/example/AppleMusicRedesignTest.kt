package com.example

import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.data.network.LyricsResolver
import com.example.data.network.PoTokenWebViewManager
import com.example.model.AudioOutputRoute
import com.example.model.LyricLine
import com.example.model.LyricWord
import com.example.model.Track
import com.example.model.TrackLyrics
import com.example.ui.player.PlayerLockActivity
import com.example.ui.player.computeLyricClockSnapshot
import com.example.ui.theme.boostCoverSwatchColor
import com.example.ui.theme.computeCoverGradientSpec
import com.example.ui.theme.computeDominantTopColor
import com.example.ui.theme.computeTintedBlackPalette
import com.example.ui.theme.hslToColor
import com.example.ui.theme.rgbToHsl
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppleMusicRedesignTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun `computeTintedBlackPalette derives exact HSL tinted black surface ramp from accent hue`() {
        val crimsonAccent = hslToColor(hue = 352f, saturation = 0.95f, lightness = 0.58f)
        val palette = computeTintedBlackPalette(crimsonAccent)

        assertFalse(palette.isNearGray)
        assertEquals(0.10f, palette.hairline.alpha, 0.005f)
        assertEquals(0.60f, palette.textSecondary.alpha, 0.005f)

        val bgHsl = rgbToHsl(palette.background.red, palette.background.green, palette.background.blue)
        val surfHsl = rgbToHsl(palette.surface.red, palette.surface.green, palette.surface.blue)
        val elevHsl = rgbToHsl(palette.elevated.red, palette.elevated.green, palette.elevated.blue)
        val pressHsl = rgbToHsl(palette.pressed.red, palette.pressed.green, palette.pressed.blue)
        val glassHsl = rgbToHsl(palette.glassTint.red, palette.glassTint.green, palette.glassTint.blue)
        val ambientHsl = rgbToHsl(palette.ambientTop.red, palette.ambientTop.green, palette.ambientTop.blue)

        assertEquals(0.28f, bgHsl[1], 0.03f)
        assertEquals(0.035f, bgHsl[2], 0.01f)

        assertEquals(0.24f, surfHsl[1], 0.03f)
        assertEquals(0.07f, surfHsl[2], 0.01f)

        assertEquals(0.22f, elevHsl[1], 0.03f)
        assertEquals(0.105f, elevHsl[2], 0.01f)

        assertEquals(0.20f, pressHsl[1], 0.03f)
        assertEquals(0.14f, pressHsl[2], 0.01f)

        assertEquals(0.30f, glassHsl[1], 0.03f)
        assertEquals(0.06f, glassHsl[2], 0.01f)
        assertEquals(0.40f, palette.glassTint.alpha, 0.01f)

        assertEquals(0.55f, ambientHsl[1], 0.03f)
        assertEquals(0.22f, ambientHsl[2], 0.01f)
        assertEquals(0.55f, palette.ambientTop.alpha, 0.01f)
    }

    @Test
    fun `computeTintedBlackPalette clamps saturation to zero when accent is near gray`() {
        val nearGray = Color(0.52f, 0.50f, 0.50f, 1f)
        val palette = computeTintedBlackPalette(nearGray)

        assertTrue(palette.isNearGray)
        assertEquals(0f, rgbToHsl(palette.background.red, palette.background.green, palette.background.blue)[1], 0.005f)
        assertEquals(0f, rgbToHsl(palette.surface.red, palette.surface.green, palette.surface.blue)[1], 0.005f)
        assertEquals(0f, rgbToHsl(palette.elevated.red, palette.elevated.green, palette.elevated.blue)[1], 0.005f)
        assertEquals(0f, rgbToHsl(palette.pressed.red, palette.pressed.green, palette.pressed.blue)[1], 0.005f)
        assertEquals(0f, rgbToHsl(palette.glassTint.red, palette.glassTint.green, palette.glassTint.blue)[1], 0.005f)
        assertEquals(0f, rgbToHsl(palette.ambientTop.red, palette.ambientTop.green, palette.ambientTop.blue)[1], 0.005f)
    }

    @Test
    fun `cover gradient boosts swatch saturation and lightness and never drops to pure black`() {
        val dullBlue = hslToColor(hue = 215f, saturation = 0.25f, lightness = 0.14f)
        val boosted = boostCoverSwatchColor(dullBlue)
        val boostedHsl = rgbToHsl(boosted.red, boosted.green, boosted.blue)

        assertTrue("Expected boosted saturation >= 0.55, was ${boostedHsl[1]}", boostedHsl[1] >= 0.54f)
        assertTrue("Expected boosted lightness in 0.28..0.42, was ${boostedHsl[2]}", boostedHsl[2] in 0.27f..0.43f)

        val spec = computeCoverGradientSpec(
            vibrantColorsRgb = listOf(0xFF284B7Eu.toInt(), 0xFF422C6Au.toInt(), 0xFF1F6A63u.toInt()),
            bottomEdgeRgb = 0xFF335588u.toInt()
        )
        val bottomHsl = rgbToHsl(spec.bottomColor.red, spec.bottomColor.green, spec.bottomColor.blue)
        assertTrue("Expected gradient bottom lightness > 0.08 (never pure black), was ${bottomHsl[2]}", bottomHsl[2] >= 0.09f)

        val topDominant = computeDominantTopColor(hslToColor(hue = 30f, saturation = 0.92f, lightness = 0.78f))
        val topDomHsl = rgbToHsl(topDominant.red, topDominant.green, topDominant.blue)
        assertTrue(topDomHsl[1] <= 0.61f)
        assertTrue(topDomHsl[2] in 0.24f..0.46f)
    }

    @Test
    fun `LyricsResolver honors positive and negative LRC offset tags and null syncedLyrics`() {
        val rawPositive = """
            [offset:500]
            [00:10.00]<00:10.00>Hello <00:10.50>world
        """.trimIndent()
        val shiftedEarly = LyricsResolver.parseLrcLyrics(rawPositive)
        assertEquals(1, shiftedEarly.size)
        assertEquals(9_500L, shiftedEarly.first().timeMs)
        assertEquals(9_500L, shiftedEarly.first().words.first().startMs)
        assertEquals(10_000L, shiftedEarly.first().words[1].startMs)

        val rawNegative = """
            [offset:-750]
            [00:10.00]Delayed line
        """.trimIndent()
        val shiftedLate = LyricsResolver.parseLrcLyrics(rawNegative)
        assertEquals(10_750L, shiftedLate.first().timeMs)

        // JSON null in syncedLyrics should not count as synced bonus and shouldn't coerce to literal "null"
        val resolver = LyricsResolver(OkHttpClient(), ApplicationProvider.getApplicationContext())
        val plainOnlyCandidate = JSONObject()
            .put("trackName", "Style")
            .put("artistName", "Taylor Swift")
            .put("duration", 231)
            .put("syncedLyrics", JSONObject.NULL)
            .put("plainLyrics", "Midnight you come and pick me up")
        assertTrue(
            resolver.isAcceptableLyricsCandidate(
                obj = plainOnlyCandidate,
                targetTitle = "Style",
                targetArtist = "Taylor Swift",
                targetDurSec = 231
            )
        )
        // Verify JSONObject.NULL in syncedLyrics does NOT grant the +30 sync bonus
        val sharedWithoutSync = JSONObject()
            .put("trackName", "Style Part One")
            .put("artistName", "Taylor Swift")
            .put("duration", 221) // titleScore=55 + artistScore=50 + durBonus=10 = 115 (< 120 without sync)
            .put("syncedLyrics", JSONObject.NULL)
            .put("plainLyrics", "Some lyrics")
        assertFalse(
            resolver.isAcceptableLyricsCandidate(
                obj = sharedWithoutSync,
                targetTitle = "Style Part",
                targetArtist = "Taylor Swift",
                targetDurSec = 231
            )
        )
        val sharedWithSync = JSONObject(sharedWithoutSync.toString())
            .put("syncedLyrics", "[00:05.00]Some lyrics")
        assertTrue(
            resolver.isAcceptableLyricsCandidate(
                obj = sharedWithSync,
                targetTitle = "Style Part",
                targetArtist = "Taylor Swift",
                targetDurSec = 231
            )
        )
    }

    @Test
    fun `computeLyricClockSnapshot snaps first word to index 0 and compensates audio latency`() {
        val lyrics = TrackLyrics(
            isSynced = true,
            sourceLabel = "Test",
            lines = listOf(
                LyricLine(
                    timeMs = 5_000L,
                    text = "Midnight drive",
                    words = listOf(
                        LyricWord(startMs = 5_300L, endMs = 5_800L, text = "Midnight "),
                        LyricWord(startMs = 5_800L, endMs = 6_400L, text = "drive")
                    )
                )
            )
        )

        // Line activates at effectivePos=5_000 (160ms lookahead passes line 5_000),
        // even though first word starts at 5_300ms -> snaps activeWordIndex to 0 cleanly
        val snap = computeLyricClockSnapshot(
            lyrics = lyrics,
            positionMs = 5_140L,
            durationMs = 200_000L,
            offsetMs = 0L,
            audioLatencyMs = 140L
        )
        assertEquals(5_000L, snap.effectivePositionMs)
        assertEquals(0, snap.activeIndex)
        assertEquals(0, snap.activeWordIndex)
        assertEquals(0f, snap.activeWordProgress, 0.001f)
    }

    @Test
    fun `PoTokenWebViewManager cooldown guards against cold-start renderer crashes`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SonoraApplication>()
        val manager = PoTokenWebViewManager(app)
        var now = 10_000L
        manager.clockMs = { now }

        assertFalse(manager.isRendererInCooldown())
        manager.recordRendererProcessCrash()
        assertTrue(manager.isRendererInCooldown())

        // During cooldown getPoToken immediately yields null without touching WebView
        val token = manager.getPoToken("vid_1", "Cgt_visitor_data", null)
        assertNull(token)

        now += 10 * 60_000L + 1L
        assertFalse(manager.isRendererInCooldown())
    }

    @Test
    fun `Track toMediaItem sets high-res square artwork URI displayTitle and durationMs`() {
        val app = ApplicationProvider.getApplicationContext<SonoraApplication>()
        val controller = app.playerController
        val track = Track(
            videoId = "yt_abc123",
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            artworkUrl = "https://lh3.googleusercontent.com/xyz=w120-h120-l90-rj",
            durationMs = 243_000L
        )

        val mediaItem = with(controller) { track.toMediaItem() }
        val meta = mediaItem.mediaMetadata
        assertEquals("Midnight City", meta.title.toString())
        assertEquals("Midnight City", meta.displayTitle.toString())
        assertEquals("M83", meta.artist.toString())
        assertEquals(
            "https://lh3.googleusercontent.com/xyz=w1200-h1200-l95-rj",
            meta.artworkUri.toString()
        )
        assertEquals(243_000L, meta.durationMs)
    }

    @Test
    fun `PlayerLockActivity launches renders close button and dismisses on SCREEN_OFF`() {
        val app = ApplicationProvider.getApplicationContext<SonoraApplication>()
        app.playerController.controllerFutureFactoryForTest = {
            Futures.immediateFailedFuture(IllegalStateException("Test bypass service bind"))
        }

        val scenario = ActivityScenario.launch<PlayerLockActivity>(
            PlayerLockActivity.createIntent(app)
        )
        try {
            composeRule.waitForIdle()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            composeRule.onNodeWithText("Sin reproducción activa").assertIsDisplayed()
            composeRule.onNodeWithTag("lock_player_close_button").assertIsDisplayed()

            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF).setPackage(app.packageName))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activity ->
                assertTrue("Expected PlayerLockActivity to finish on ACTION_SCREEN_OFF", activity.isFinishing)
            }
        } finally {
            scenario.close()
        }
    }
}
