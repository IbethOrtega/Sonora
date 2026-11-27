package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CanvasArtwork
import com.example.model.SonoraSettings
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.ui.components.GlassCard
import com.example.ui.theme.CoverGradientSpec
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun PlayerPrimaryColumn(
    track: Track,
    playbackState: PlaybackUiState,
    positionMsFlow: StateFlow<Long>,
    settings: SonoraSettings,
    djLiveState: DjLiveState,
    isLiked: Boolean,
    dismissDragModifier: Modifier,
    isWideHero: Boolean,
    isFullscreenPortraitCanvas: Boolean,
    isPlayerActive: Boolean,
    canvasArtwork: CanvasArtwork?,
    motionDirection: PlayerMotionDirection,
    reducedMotion: Boolean,
    queueCarryOffsetPx: Float,
    isLyricsOpen: Boolean,
    isQueueOpen: Boolean,
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
    onToggleLyrics: () -> Unit,
    onToggleDj: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenVoiceDj: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    coverGradient: CoverGradientSpec? = null,
    isPortraitFullBleed: Boolean = false
) {
    val accent = LocalSonoraAccent.current
    val duration = playbackState.durationMs.coerceAtLeast(1L)
    val crossfadeZoneFraction = remember(duration, settings.crossfadeSeconds, settings.autoMixEnabled) {
        when {
            settings.crossfadeSeconds > 0 -> ((settings.crossfadeSeconds * 1000f) / duration.toFloat()).coerceIn(0f, 0.28f)
            settings.crossfadeSeconds < 0 || settings.autoMixEnabled -> (6_000f / duration.toFloat()).coerceIn(0f, 0.25f)
            else -> 0f
        }
    }
    val artistTint = coverGradient?.artistTint ?: Color.White.copy(alpha = 0.62f)
    val trackBarTint = coverGradient?.trackBarTint ?: Color.White.copy(alpha = 0.24f)
    val activeStarTint = coverGradient?.activeStarTint ?: accent

    Column(
        modifier = modifier.padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isPortraitFullBleed) Arrangement.SpaceBetween else Arrangement.SpaceBetween
    ) {
        if (!isPortraitFullBleed) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .graphicsLayer { translationY = queueCarryOffsetPx }
                    .then(dismissDragModifier),
                contentAlignment = Alignment.Center
            ) {
                if (!isFullscreenPortraitCanvas) {
                    ExpandedPlayPauseMotion(
                        isPlaying = playbackState.isPlaying,
                        reducedMotion = reducedMotion
                    ) { motionMod ->
                        MusicVideoLayout(
                            track = track,
                            canvasArtwork = canvasArtwork,
                            animatedArtworkEnabled = settings.animatedArtworkEnabled,
                            allowMobileData = settings.animatedArtworkMobileData,
                            isPlaying = playbackState.isPlaying,
                            isPlayerActive = isPlayerActive,
                            isWideHero = isWideHero,
                            motionDirection = motionDirection,
                            reducedMotion = reducedMotion,
                            accent = accent,
                            modifier = motionMod
                        )
                    }
                }
            }
        }

        if (djLiveState.isActive) {
            DjLivePlayerBanner(
                djLiveState = djLiveState,
                onOpenVoiceDj = onOpenVoiceDj,
                onSwitchVibe = onSwitchDjVibe,
                onStop = onStopDjLive
            )
        }

        if (playbackState.errorMessage != null) {
            ErrorBannerWithDetail(
                errorMessage = playbackState.errorMessage,
                errorDetail = playbackState.errorDetail,
                accent = accent,
                onRetry = onRetry
            )
        }

        // Song title (SemiBold) + Artist (tinted), left-aligned; Star & More circular discs on the right
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isPortraitFullBleed) Modifier.weight(1f) else Modifier)
                .then(dismissDragModifier),
            verticalArrangement = if (isPortraitFullBleed) Arrangement.SpaceEvenly else Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SlidingSongInfo(
                        targetState = track,
                        direction = motionDirection,
                        reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f)
                    ) { infoTrack ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onOpenAlbum)
                            ) {
                                MarqueeText(
                                    text = infoTrack.title,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = (-0.25).sp
                                    ),
                                    color = Color.White,
                                    startDelayMillis = 900L,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (infoTrack.isExplicit) {
                                    ExplicitBadge()
                                }
                            }

                            Spacer(Modifier.height(2.dp))

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onOpenArtist)
                            ) {
                                MarqueeText(
                                    text = infoTrack.artist,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                        letterSpacing = (-0.15).sp
                                    ),
                                    color = artistTint,
                                    startDelayMillis = 1550L
                                )
                            }
                        }
                    }

                    GlassDiscPair(
                        isLiked = isLiked,
                        onToggleLike = onToggleLike,
                        onOpenOptions = onOpenOptions,
                        activeStarTint = activeStarTint
                    )
                }

                CurrentLyricStrip(
                    lyrics = playbackState.lyrics,
                    positionMsFlow = positionMsFlow,
                    lyricsOffsetMs = settings.lyricsOffsetFor(track.videoId),
                    onClick = onToggleLyrics,
                    isLoadingLyrics = playbackState.isLoadingLyrics,
                    activeAudioRoute = playbackState.activeAudioRoute,
                    tintColor = artistTint
                )
            }

            ThinSlider(
                positionMsFlow = positionMsFlow,
                durationMs = duration,
                crossfadeZoneFraction = crossfadeZoneFraction,
                codecBadgeText = "",
                isPlaying = playbackState.isPlaying,
                hasActiveAudioFx = false,
                accent = accent,
                onOpenAudioSheet = onOpenAudioSheet,
                onSeekTo = onSeekTo,
                trackColor = trackBarTint
            )

            TransportRow(
                isPlaying = playbackState.isPlaying,
                isBuffering = playbackState.isBuffering,
                accent = accent,
                onSkipPrevious = onSkipPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext
            )

            VolumeRow(
                modifier = Modifier.testTag("now_playing_volume_slider"),
                trackColor = trackBarTint
            )

            PlayerActionRow(
                shuffleEnabled = playbackState.shuffleEnabled,
                repeatMode = playbackState.repeatMode,
                djLiveActive = djLiveState.isActive,
                isLyricsOpen = isLyricsOpen,
                isQueueOpen = isQueueOpen,
                queueCount = playbackState.queue.size,
                onToggleLyrics = onToggleLyrics,
                onToggleDj = onToggleDj,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onOpenAudioOutput = onOpenAudioOutput,
                onAddToPlaylist = onAddToPlaylist,
                onToggleQueue = onToggleQueue,
                tintColor = artistTint,
                audioOutputLabel = playbackState.activeAudioRoute.label
            )
        }
    }
}

@Composable
internal fun ErrorBannerWithDetail(
    errorMessage: String,
    errorDetail: String?,
    accent: Color,
    onRetry: () -> Unit
) {
    var showErrorDetail by remember(errorMessage, errorDetail) { mutableStateOf(false) }
    val hasDetail = !errorDetail.isNullOrBlank()
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (hasDetail) {
                    TextButton(
                        onClick = { showErrorDetail = !showErrorDetail },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("error_detail_toggle_button")
                    ) {
                        Text(
                            text = if (showErrorDetail) "Ocultar" else "Ver detalle",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                    }
                }
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Reintentar")
                }
            }
            AnimatedVisibility(visible = showErrorDetail && hasDetail) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.38f))
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        text = errorDetail.orEmpty(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        ),
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.testTag("error_detail_text")
                    )
                }
            }
        }
    }
}
