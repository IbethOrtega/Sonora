package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CanvasArtwork
import com.example.model.SonoraSettings
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.ui.components.GlassCard
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun PlayerPrimaryColumn(
    track: Track,
    nextTrack: Track?,
    playbackState: PlaybackUiState,
    positionMsFlow: StateFlow<Long>,
    settings: SonoraSettings,
    djLiveState: DjLiveState,
    isLiked: Boolean,
    codecBadgeText: String,
    hasActiveAudioFx: Boolean,
    dismissDragModifier: Modifier,
    isWideHero: Boolean,
    isPlayerActive: Boolean,
    canvasArtwork: CanvasArtwork?,
    motionDirection: PlayerMotionDirection,
    reducedMotion: Boolean,
    queueCarryOffsetPx: Float,
    isLyricsOpen: Boolean,
    isQueueOpen: Boolean,
    onToggleAnimatedArtwork: (Boolean) -> Unit,
    onToggleAutoMix: (Boolean) -> Unit,
    onOpenVoiceDj: () -> Unit,
    onOpenDrivingMode: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenArtist: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenOptions: () -> Unit,
    onOpenLyrics: () -> Unit,
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
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
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
    val showAutoMixDeck = playbackState.autoMixStatus != null && (settings.autoMixEnabled || settings.crossfadeSeconds != 0)

    Column(
        modifier = modifier.padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverModeTogglePill(
                animatedEnabled = settings.animatedArtworkEnabled,
                onSelect = onToggleAnimatedArtwork
            )
            Surface(
                onClick = { onToggleAutoMix(!settings.autoMixEnabled) },
                shape = RoundedCornerShape(50),
                color = Color(0xFF17171C).copy(alpha = 0.82f),
                border = BorderStroke(
                    1.dp,
                    if (settings.autoMixEnabled) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier.testTag("now_playing_automix_toggle")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "Mezcla automática",
                        tint = if (settings.autoMixEnabled) accent else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (settings.autoMixEnabled) "AutoMix ON" else "AutoMix",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (settings.autoMixEnabled) Color.White else Color.White.copy(alpha = 0.72f)
                    )
                }
            }
            Surface(
                onClick = onOpenVoiceDj,
                shape = RoundedCornerShape(50),
                color = Color(0xFF17171C).copy(alpha = 0.82f),
                border = BorderStroke(1.dp, if (djLiveState.isActive) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.18f)),
                modifier = Modifier.testTag("np_voice_dj_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Rounded.Mic, contentDescription = "Hablar con DJ Sony", tint = accent, modifier = Modifier.size(14.dp))
                    Text("Voz", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.90f))
                }
            }
            Surface(
                onClick = onOpenDrivingMode,
                shape = RoundedCornerShape(50),
                color = Color(0xFF17171C).copy(alpha = 0.82f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                modifier = Modifier.testTag("np_driving_mode_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Rounded.DirectionsCar, contentDescription = "Modo conducción", tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(14.dp))
                    Text("Auto", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.90f))
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .graphicsLayer { translationY = queueCarryOffsetPx }
                .then(dismissDragModifier),
            contentAlignment = Alignment.Center
        ) {
            ExpandedPlayPauseMotion(
                isPlaying = playbackState.isPlaying,
                reducedMotion = reducedMotion
            ) { motionMod ->
                MusicVideoLayout(
                    track = track,
                    nextTrack = nextTrack,
                    canvasArtwork = canvasArtwork,
                    animatedArtworkEnabled = settings.animatedArtworkEnabled,
                    animatedArtworkWifiOnly = settings.animatedArtworkWifiOnly,
                    isPlaying = playbackState.isPlaying,
                    isPlayerActive = isPlayerActive,
                    isWideHero = isWideHero,
                    motionDirection = motionDirection,
                    reducedMotion = reducedMotion,
                    autoMixStatus = playbackState.autoMixStatus,
                    showAutoMixBadge = showAutoMixDeck,
                    accent = accent,
                    modifier = motionMod
                )
            }
        }

        if (playbackState.isPlaying) {
            LiveAudioVisualizerStrip(
                accent = accent,
                modifier = Modifier
                    .fillMaxWidth(0.60f)
                    .padding(bottom = 4.dp)
            )
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(dismissDragModifier),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SlidingSongInfo(
                        targetState = track,
                        direction = motionDirection,
                        reducedMotion = reducedMotion,
                        modifier = Modifier.fillMaxWidth()
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
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
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
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Normal,
                                        letterSpacing = (-0.15).sp
                                    ),
                                    color = Color.White.copy(alpha = 0.55f),
                                    startDelayMillis = 1550L
                                )
                            }
                        }
                    }

                    if (!playbackState.streamNotice.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.testTag("now_playing_stream_notice")
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = accent.copy(alpha = 0.85f), modifier = Modifier.size(13.dp))
                            Text(
                                text = playbackState.streamNotice,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.66f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                GlassDiscPair(
                    isLiked = isLiked,
                    onToggleLike = onToggleLike,
                    onOpenOptions = onOpenOptions
                )
            }

            CurrentLyricStrip(
                lyrics = playbackState.lyrics,
                positionMsFlow = positionMsFlow,
                lyricsOffsetMs = settings.lyricsOffsetMs,
                onClick = onOpenLyrics
            )

            ThinSlider(
                positionMsFlow = positionMsFlow,
                durationMs = duration,
                crossfadeZoneFraction = crossfadeZoneFraction,
                codecBadgeText = codecBadgeText,
                isPlaying = playbackState.isPlaying,
                hasActiveAudioFx = hasActiveAudioFx,
                accent = accent,
                onOpenAudioSheet = onOpenAudioSheet,
                onSeekTo = onSeekTo
            )

            TransportRow(
                isPlaying = playbackState.isPlaying,
                isBuffering = playbackState.isBuffering,
                accent = accent,
                onSkipPrevious = onSkipPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext
            )

            VolumeRow(modifier = Modifier.testTag("now_playing_volume_slider"))

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
                onToggleQueue = onToggleQueue
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
