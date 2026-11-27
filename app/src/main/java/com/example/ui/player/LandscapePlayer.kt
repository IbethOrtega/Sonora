package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CanvasArtwork
import com.example.model.QueueMoodFilter
import com.example.model.SonoraSettings
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.ui.theme.CoverGradientSpec
import kotlinx.coroutines.flow.StateFlow

@Composable
fun LandscapePlayer(
    track: Track,
    playbackState: PlaybackUiState,
    positionMsFlow: StateFlow<Long>,
    settings: SonoraSettings,
    djLiveState: DjLiveState,
    isLiked: Boolean,
    dismissDragModifier: Modifier,
    isPlayerActive: Boolean,
    canvasArtwork: CanvasArtwork?,
    motionDirection: PlayerMotionDirection,
    reducedMotion: Boolean,
    showQueuePane: Boolean,
    accent: Color,
    onSelectPane: (Boolean) -> Unit,
    onOpenVoiceDj: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenOptions: () -> Unit,
    onOpenAudioSheet: () -> Unit,
    onOpenAudioOutput: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleDj: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRetry: () -> Unit,
    onSkipToQueueIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onOpenQueueItemOptions: (Track, Int) -> Unit,
    onRetuneQueueMood: (QueueMoodFilter) -> Unit,
    onOpenSongMixer: () -> Unit,
    onAdjustLyricsOffset: (Long) -> Unit,
    onResetLyricsOffset: () -> Unit,
    onReloadLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    coverGradient: CoverGradientSpec? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerPrimaryColumn(
            track = track,
            playbackState = playbackState,
            positionMsFlow = positionMsFlow,
            settings = settings,
            djLiveState = djLiveState,
            isLiked = isLiked,
            dismissDragModifier = dismissDragModifier,
            isWideHero = true,
            isFullscreenPortraitCanvas = false,
            isPlayerActive = isPlayerActive,
            canvasArtwork = canvasArtwork,
            motionDirection = motionDirection,
            reducedMotion = reducedMotion,
            queueCarryOffsetPx = 0f,
            isLyricsOpen = !showQueuePane,
            isQueueOpen = showQueuePane,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            onToggleLike = onToggleLike,
            onOpenOptions = onOpenOptions,
            onOpenAudioSheet = onOpenAudioSheet,
            onOpenAudioOutput = onOpenAudioOutput,
            onSeekTo = onSeekTo,
            onSkipPrevious = onSkipPrevious,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onToggleLyrics = { onSelectPane(false) },
            onToggleDj = onToggleDj,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            onAddToPlaylist = onAddToPlaylist,
            onToggleQueue = { onSelectPane(true) },
            onOpenVoiceDj = onOpenVoiceDj,
            onSwitchDjVibe = onSwitchDjVibe,
            onStopDjLive = onStopDjLive,
            onRetry = onRetry,
            coverGradient = coverGradient,
            isPortraitFullBleed = false,
            modifier = Modifier.weight(1f)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.Black.copy(alpha = 0.20f))
                .border(0.6.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                LandscapeSidePill(
                    label = "Letras",
                    selected = !showQueuePane,
                    accent = accent,
                    onClick = { onSelectPane(false) }
                )
                LandscapeSidePill(
                    label = "Cola (${playbackState.queue.size})",
                    selected = showQueuePane,
                    accent = accent,
                    onClick = { onSelectPane(true) }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                if (showQueuePane) {
                    QueuePane(
                        playbackState = playbackState,
                        onSkipToIndex = onSkipToQueueIndex,
                        onMoveItem = onMoveQueueItem,
                        onRemoveItem = onRemoveQueueItem,
                        onClearQueue = onClearQueue,
                        onOpenItemOptions = onOpenQueueItemOptions,
                        onRetuneQueueMood = onRetuneQueueMood,
                        onOpenSongMixer = onOpenSongMixer,
                        onDismissQueue = null
                    )
                } else {
                    LyricsPane(
                        track = track,
                        playbackState = playbackState,
                        positionMsFlow = positionMsFlow,
                        lyricsOffsetMs = settings.lyricsOffsetFor(track.videoId),
                        onAdjustLyricsOffset = onAdjustLyricsOffset,
                        onResetLyricsOffset = onResetLyricsOffset,
                        onReloadLyrics = onReloadLyrics,
                        onSeekTo = onSeekTo,
                        onTogglePlayPause = onTogglePlayPause
                    )
                }
            }
        }
    }
}

@Composable
private fun LandscapeSidePill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f),
        border = BorderStroke(
            0.7.dp,
            if (selected) accent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.14f)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}
