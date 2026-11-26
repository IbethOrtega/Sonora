package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.example.model.LyricLine
import com.example.model.Track
import com.example.model.TrackLyrics
import com.example.player.PlaybackUiState
import com.example.ui.player.LyricAutoFollowKey
import com.example.ui.player.LyricBlurDpKey
import com.example.ui.player.LyricsPane
import com.example.ui.theme.SonoraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LyricsPaneComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `LyricsPane advances active line preserves autoFollow during programmatic scroll and keeps non-active lines blurred`() {
        val sampleLines = (0..7).map { idx ->
            LyricLine(
                timeMs = idx * 3_000L,
                text = "Synced lyric line number $idx"
            )
        }
        val lyrics = TrackLyrics(
            lines = sampleLines,
            isSynced = true,
            sourceLabel = "LRCLIB"
        )
        val track = Track(
            videoId = "lovers_id",
            title = "Lovers",
            artist = "Anna of the North",
            durationMs = 240_000L
        )
        val positionFlow = MutableStateFlow(0L)
        var playbackState by mutableStateOf(
            PlaybackUiState(
                currentTrack = track,
                isPlaying = true,
                durationMs = 240_000L,
                lyrics = lyrics,
                isLoadingLyrics = false
            )
        )

        composeTestRule.setContent {
            SonoraTheme {
                Box(modifier = Modifier.size(360.dp, 640.dp)) {
                    LyricsPane(
                        track = track,
                        playbackState = playbackState,
                        positionMsFlow = positionFlow,
                        lyricsOffsetMs = 0L,
                        onAdjustLyricsOffset = {},
                        onResetLyricsOffset = {},
                        onReloadLyrics = {},
                        onSeekTo = {},
                        onTogglePlayPause = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(SemanticsMatcher.expectValue(LyricAutoFollowKey, true)).assertExists()
        composeTestRule.onNodeWithTag("center_lyrics_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("lyric_line_0").assert(SemanticsMatcher.expectValue(LyricBlurDpKey, 0f))
        composeTestRule.onNodeWithTag("lyric_line_1").assert(SemanticsMatcher.expectValue(LyricBlurDpKey, 1.3f))
        composeTestRule.onNodeWithTag("lyric_line_2").assert(SemanticsMatcher.expectValue(LyricBlurDpKey, 2.6f))

        // Programmatically advance playback position across multiple lines to trigger animateScrollToItem
        composeTestRule.runOnIdle { positionFlow.value = 3_500L }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { positionFlow.value = 9_500L }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { positionFlow.value = 15_500L }
        composeTestRule.waitForIdle()

        // Programmatic scroll must NEVER disable autoFollow or reveal the manual recenter pill
        composeTestRule.onNode(SemanticsMatcher.expectValue(LyricAutoFollowKey, true)).assertExists()
        composeTestRule.onNodeWithTag("center_lyrics_button").assertDoesNotExist()

        // Line 5 (15_000ms) is active (0dp blur); adjacent lines remain progressively blurred
        composeTestRule.onNodeWithTag("lyric_line_5").assert(SemanticsMatcher.expectValue(LyricBlurDpKey, 0f))
        composeTestRule.onNodeWithTag("lyric_line_4").assert(SemanticsMatcher.expectValue(LyricBlurDpKey, 1.3f))
        composeTestRule.onNodeWithTag("lyric_line_3").assert(SemanticsMatcher.expectValue(LyricBlurDpKey, 2.6f))
        composeTestRule.onNodeWithTag("lyric_line_2").assert(SemanticsMatcher.expectValue(LyricBlurDpKey, 4.0f))
    }
}
