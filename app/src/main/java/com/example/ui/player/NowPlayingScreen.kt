package com.example.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.network.CanvasRepository
import com.example.model.CanvasArtwork
import com.example.model.PlaybackOrigin
import com.example.model.QueueMoodFilter
import com.example.model.SonoraSettings
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.ui.components.GlassCard
import com.example.ui.components.StatsForNerdsDialog
import com.example.ui.theme.LocalSonoraAccent
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

private enum class PlayerSurfaceMode {
    PLAYER,
    LYRICS,
    QUEUE
}

@Composable
fun NowPlayingScreen(
    playbackState: PlaybackUiState,
    positionMsFlow: StateFlow<Long>,
    settings: SonoraSettings,
    djLiveState: DjLiveState,
    isLiked: Boolean,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onSkipToQueueIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onToggleAnimatedArtwork: (Boolean) -> Unit,
    onStartDjLive: () -> Unit,
    onOpenVoiceDj: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit,
    onAdjustLyricsOffset: (Long) -> Unit,
    onResetLyricsOffset: () -> Unit,
    onSetEqEnabled: (Boolean) -> Unit,
    onSetEqPreset: (com.example.model.EqPreset) -> Unit,
    onSetBassBoost: (Int) -> Unit,
    onSetNormalizeVolume: (Boolean) -> Unit,
    onToggleAutoMix: (Boolean) -> Unit,
    onSetAutoMixOverlapSeconds: (Int) -> Unit,
    onSetDolbyAtmos: (Boolean) -> Unit,
    onSetSpatialAudio: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenOptions: (Track) -> Unit,
    onOpenQueueItemOptions: (Track, Int) -> Unit,
    onNavigateToOrigin: (PlaybackOrigin) -> Unit = {},
    onOpenArtist: (String, String?) -> Unit = { _, _ -> },
    onOpenAlbum: (Track) -> Unit = {},
    sleepTimerRemainingSec: Int? = null,
    sleepAfterCurrentTrack: Boolean = false,
    onSetSleepTimer: (Int?) -> Unit = {},
    onSetSleepAfterCurrentTrack: (Boolean) -> Unit = {},
    onSetPlaybackSpeed: (Float) -> Unit = {},
    onSetPlaybackPitch: (Float) -> Unit = {},
    onResetSpeedAndPitch: () -> Unit = {},
    onReloadLyrics: () -> Unit = {},
    onSetCrossfadeSeconds: (Int) -> Unit = {},
    onRetuneQueueMood: (QueueMoodFilter) -> Unit = {},
    onOpenSongMixer: () -> Unit = {},
    initialOpenQueue: Boolean = false
) {
    val track = playbackState.currentTrack ?: return
    val nextTrack = playbackState.queue.getOrNull(playbackState.currentIndex + 1)
    var activeMode by remember(initialOpenQueue) {
        mutableStateOf(if (initialOpenQueue) PlayerSurfaceMode.QUEUE else PlayerSurfaceMode.PLAYER)
    }
    LaunchedEffect(initialOpenQueue) {
        if (initialOpenQueue) {
            activeMode = PlayerSurfaceMode.QUEUE
        }
    }

    var showEqSheet by remember { mutableStateOf(false) }
    var showAudioOutputSheet by remember { mutableStateOf(false) }
    var showStatsForNerds by remember { mutableStateOf(false) }
    var showDrivingMode by remember { mutableStateOf(false) }

    BackHandler {
        if (activeMode != PlayerSurfaceMode.PLAYER) {
            activeMode = PlayerSurfaceMode.PLAYER
        } else {
            onCollapse()
        }
    }

    val context = LocalContext.current
    val accent = LocalSonoraAccent.current
    val haptics = rememberPlayerHaptics()
    val reducedMotion = remember(context) { isReducedMotionEnabled(context) }
    val queueCarryState = rememberQueueCarryState()

    var lastIndex by remember { mutableIntStateOf(playbackState.currentIndex) }
    var motionDirection by remember { mutableStateOf(PlayerMotionDirection.NEXT) }
    LaunchedEffect(playbackState.currentIndex, track.videoId) {
        if (playbackState.currentIndex != lastIndex) {
            motionDirection = if (playbackState.currentIndex >= lastIndex) {
                PlayerMotionDirection.NEXT
            } else {
                PlayerMotionDirection.PREVIOUS
            }
            lastIndex = playbackState.currentIndex
        }
    }

    var canvasArtwork by remember(track.videoId) {
        mutableStateOf<CanvasArtwork?>(CanvasRepository.getCached(track.videoId))
    }
    var backdropContrast by remember { mutableStateOf(BackdropContrastMetrics()) }

    LaunchedEffect(track.videoId, track.title, track.artist, settings.animatedArtworkEnabled) {
        if (!settings.animatedArtworkEnabled) {
            canvasArtwork = null
            return@LaunchedEffect
        }
        canvasArtwork = CanvasRepository.fetchCanvas(
            videoId = track.videoId,
            title = track.title,
            artist = track.artist
        )
    }

    val isPlayerActive = activeMode == PlayerSurfaceMode.PLAYER &&
        !showEqSheet &&
        !showAudioOutputSheet &&
        !showStatsForNerds &&
        !showDrivingMode

    var isWideLayout by remember { mutableStateOf(false) }

    val dismissDragModifier = Modifier.pointerInput(onCollapse, activeMode) {
        var totalDrag = 0f
        detectVerticalDragGestures(
            onDragStart = {
                totalDrag = 0f
                queueCarryState.reset()
            },
            onVerticalDrag = { _, dragAmount ->
                totalDrag += dragAmount
                if (activeMode == PlayerSurfaceMode.PLAYER && totalDrag < 0f && !isWideLayout) {
                    queueCarryState.dragOffsetPx = totalDrag
                }
            },
            onDragEnd = {
                if (totalDrag > 95f) {
                    haptics.tick()
                    onCollapse()
                } else if (activeMode == PlayerSurfaceMode.PLAYER && !isWideLayout && queueCarryState.carryProgress >= QUEUE_CARRY_FRACTION) {
                    haptics.confirm()
                    activeMode = PlayerSurfaceMode.QUEUE
                }
                queueCarryState.reset()
            },
            onDragCancel = {
                queueCarryState.reset()
            }
        )
    }

    val codecBadgeText = remember(
        playbackState.codecLabel,
        settings.dolbyAtmosEnabled,
        settings.spatialAudioEnabled,
        settings.eqEnabled,
        settings.crossfadeSeconds,
        settings.playbackSpeed,
        sleepTimerRemainingSec,
        sleepAfterCurrentTrack
    ) {
        buildList {
            add(playbackState.codecLabel)
            if (settings.dolbyAtmosEnabled) add("Atmos")
            if (settings.spatialAudioEnabled) add("360°")
            if (settings.eqEnabled) add("EQ")
            if (settings.crossfadeSeconds < 0) add("Fundido Auto")
            else if (settings.crossfadeSeconds > 0) add("${settings.crossfadeSeconds}s")
            if (abs(settings.playbackSpeed - 1.0f) > 0.02f) add("${"%.2f".format(settings.playbackSpeed)}×")
            if (sleepTimerRemainingSec != null || sleepAfterCurrentTrack) add("Timer")
        }.joinToString(" • ")
    }

    val hasActiveAudioFx = settings.eqEnabled ||
        settings.dolbyAtmosEnabled ||
        settings.spatialAudioEnabled ||
        abs(settings.playbackSpeed - 1.0f) > 0.02f ||
        sleepTimerRemainingSec != null ||
        sleepAfterCurrentTrack

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070A))
            .onSizeChanged { size ->
                if (size.height > 0) {
                    queueCarryState.thresholdPx = size.height * 0.45f
                }
            }
            .testTag("now_playing_sheet")
    ) {
        val isWide = maxWidth >= 700.dp
        isWideLayout = isWide

        ArtworkMeshBackdrop(
            artworkUrl = track.artworkUrl,
            style = settings.playerBackdropStyle,
            accent = accent,
            ambientPrimary = playbackState.ambientPrimary,
            ambientSecondary = playbackState.ambientSecondary,
            isPlaying = playbackState.isPlaying,
            isReducedMotion = reducedMotion,
            onContrastChange = { backdropContrast = it },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = backdropContrast.topBandScrimAlpha),
                        1.0f to Color.Transparent
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dismissDragModifier),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.36f))
                        .clickable(onClick = onCollapse)
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("now_playing_collapse_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Minimizar reproductor",
                            tint = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PlaybackOriginCaption(
                            origin = playbackState.playbackOrigin,
                            onClick = { playbackState.playbackOrigin?.let(onNavigateToOrigin) }
                        )
                    }

                    Spacer(Modifier.size(48.dp))
                }
            }

            if (isWide) {
                LandscapePlayer(
                    track = track,
                    nextTrack = nextTrack,
                    playbackState = playbackState,
                    positionMsFlow = positionMsFlow,
                    settings = settings,
                    djLiveState = djLiveState,
                    isLiked = isLiked,
                    codecBadgeText = codecBadgeText,
                    hasActiveAudioFx = hasActiveAudioFx,
                    dismissDragModifier = dismissDragModifier,
                    isPlayerActive = isPlayerActive,
                    canvasArtwork = canvasArtwork,
                    motionDirection = motionDirection,
                    reducedMotion = reducedMotion,
                    showQueuePane = activeMode == PlayerSurfaceMode.QUEUE,
                    accent = accent,
                    onSelectPane = { showQueue ->
                        activeMode = if (showQueue) PlayerSurfaceMode.QUEUE else PlayerSurfaceMode.LYRICS
                    },
                    onToggleAnimatedArtwork = onToggleAnimatedArtwork,
                    onToggleAutoMix = onToggleAutoMix,
                    onOpenVoiceDj = onOpenVoiceDj,
                    onOpenDrivingMode = { showDrivingMode = true },
                    onSwitchDjVibe = onSwitchDjVibe,
                    onStopDjLive = onStopDjLive,
                    onOpenAlbum = { onOpenAlbum(track) },
                    onOpenArtist = { onOpenArtist(track.artist, track.artistId) },
                    onToggleLike = { onToggleLike(track) },
                    onOpenOptions = { onOpenOptions(track) },
                    onOpenAudioSheet = { showEqSheet = true },
                    onOpenAudioOutput = { showAudioOutputSheet = true },
                    onSeekTo = onSeekTo,
                    onSkipPrevious = onSkipPrevious,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onToggleDj = { if (djLiveState.isActive) onStopDjLive() else onStartDjLive() },
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onRetry = onRetry,
                    onSkipToQueueIndex = onSkipToQueueIndex,
                    onMoveQueueItem = onMoveQueueItem,
                    onRemoveQueueItem = onRemoveQueueItem,
                    onClearQueue = onClearQueue,
                    onOpenQueueItemOptions = onOpenQueueItemOptions,
                    onRetuneQueueMood = onRetuneQueueMood,
                    onOpenSongMixer = onOpenSongMixer,
                    onAdjustLyricsOffset = onAdjustLyricsOffset,
                    onResetLyricsOffset = onResetLyricsOffset,
                    onReloadLyrics = onReloadLyrics,
                    modifier = Modifier.weight(1f)
                )
            } else {
                AnimatedContent(
                    targetState = activeMode,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(180)) },
                    modifier = Modifier.weight(1f),
                    label = "np_mode_transition"
                ) { mode ->
                    when (mode) {
                        PlayerSurfaceMode.PLAYER -> {
                            PlayerPrimaryColumn(
                                track = track,
                                nextTrack = nextTrack,
                                playbackState = playbackState,
                                positionMsFlow = positionMsFlow,
                                settings = settings,
                                djLiveState = djLiveState,
                                isLiked = isLiked,
                                codecBadgeText = codecBadgeText,
                                hasActiveAudioFx = hasActiveAudioFx,
                                dismissDragModifier = dismissDragModifier,
                                isWideHero = false,
                                isPlayerActive = isPlayerActive,
                                canvasArtwork = canvasArtwork,
                                motionDirection = motionDirection,
                                reducedMotion = reducedMotion,
                                queueCarryOffsetPx = queueCarryState.dragOffsetPx * 0.45f,
                                isLyricsOpen = false,
                                isQueueOpen = false,
                                onToggleAnimatedArtwork = onToggleAnimatedArtwork,
                                onToggleAutoMix = onToggleAutoMix,
                                onOpenVoiceDj = onOpenVoiceDj,
                                onOpenDrivingMode = { showDrivingMode = true },
                                onSwitchDjVibe = onSwitchDjVibe,
                                onStopDjLive = onStopDjLive,
                                onOpenAlbum = { onOpenAlbum(track) },
                                onOpenArtist = { onOpenArtist(track.artist, track.artistId) },
                                onToggleLike = { onToggleLike(track) },
                                onOpenOptions = { onOpenOptions(track) },
                                onOpenLyrics = { activeMode = PlayerSurfaceMode.LYRICS },
                                onOpenAudioSheet = { showEqSheet = true },
                                onOpenAudioOutput = { showAudioOutputSheet = true },
                                onSeekTo = onSeekTo,
                                onSkipPrevious = onSkipPrevious,
                                onTogglePlayPause = onTogglePlayPause,
                                onSkipNext = onSkipNext,
                                onToggleLyrics = { activeMode = PlayerSurfaceMode.LYRICS },
                                onToggleDj = { if (djLiveState.isActive) onStopDjLive() else onStartDjLive() },
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleQueue = { activeMode = PlayerSurfaceMode.QUEUE },
                                onRetry = onRetry,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        PlayerSurfaceMode.LYRICS -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    LyricsPane(
                                        track = track,
                                        playbackState = playbackState,
                                        positionMsFlow = positionMsFlow,
                                        lyricsOffsetMs = settings.lyricsOffsetMs,
                                        onAdjustLyricsOffset = onAdjustLyricsOffset,
                                        onResetLyricsOffset = onResetLyricsOffset,
                                        onReloadLyrics = onReloadLyrics,
                                        onSeekTo = onSeekTo,
                                        onTogglePlayPause = onTogglePlayPause
                                    )
                                }
                                PlayerActionRow(
                                    shuffleEnabled = playbackState.shuffleEnabled,
                                    repeatMode = playbackState.repeatMode,
                                    djLiveActive = djLiveState.isActive,
                                    isLyricsOpen = true,
                                    isQueueOpen = false,
                                    queueCount = playbackState.queue.size,
                                    onToggleLyrics = { activeMode = PlayerSurfaceMode.PLAYER },
                                    onToggleDj = { if (djLiveState.isActive) onStopDjLive() else onStartDjLive() },
                                    onToggleShuffle = onToggleShuffle,
                                    onCycleRepeat = onCycleRepeat,
                                    onOpenAudioOutput = { showAudioOutputSheet = true },
                                    onAddToPlaylist = { onAddToPlaylist(track) },
                                    onToggleQueue = { activeMode = PlayerSurfaceMode.QUEUE },
                                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                                )
                            }
                        }

                        PlayerSurfaceMode.QUEUE -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                GlassCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    QueuePane(
                                        playbackState = playbackState,
                                        onSkipToIndex = onSkipToQueueIndex,
                                        onMoveItem = onMoveQueueItem,
                                        onRemoveItem = onRemoveQueueItem,
                                        onClearQueue = onClearQueue,
                                        onOpenItemOptions = onOpenQueueItemOptions,
                                        onRetuneQueueMood = onRetuneQueueMood,
                                        onOpenSongMixer = onOpenSongMixer,
                                        onDismissQueue = { activeMode = PlayerSurfaceMode.PLAYER }
                                    )
                                }
                                PlayerActionRow(
                                    shuffleEnabled = playbackState.shuffleEnabled,
                                    repeatMode = playbackState.repeatMode,
                                    djLiveActive = djLiveState.isActive,
                                    isLyricsOpen = false,
                                    isQueueOpen = true,
                                    queueCount = playbackState.queue.size,
                                    onToggleLyrics = { activeMode = PlayerSurfaceMode.LYRICS },
                                    onToggleDj = { if (djLiveState.isActive) onStopDjLive() else onStartDjLive() },
                                    onToggleShuffle = onToggleShuffle,
                                    onCycleRepeat = onCycleRepeat,
                                    onOpenAudioOutput = { showAudioOutputSheet = true },
                                    onAddToPlaylist = { onAddToPlaylist(track) },
                                    onToggleQueue = { activeMode = PlayerSurfaceMode.PLAYER },
                                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showDrivingMode) {
            DrivingModeOverlay(
                track = track,
                playbackState = playbackState,
                positionMsFlow = positionMsFlow,
                djLiveActive = djLiveState.isActive,
                onDismiss = { showDrivingMode = false },
                onTogglePlayPause = onTogglePlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onOpenVoiceDj = onOpenVoiceDj
            )
        }
    }

    if (showAudioOutputSheet) {
        AudioOutputSheet(
            activeAudioRoute = playbackState.activeAudioRoute,
            settings = settings,
            onDismiss = { showAudioOutputSheet = false },
            onSetDolbyAtmos = onSetDolbyAtmos,
            onSetSpatialAudio = onSetSpatialAudio,
            onOpenEqualizer = { showEqSheet = true }
        )
    }

    if (showEqSheet) {
        QuickEqualizerSheet(
            settings = settings,
            activeAudioRoute = playbackState.activeAudioRoute,
            autoMixStatus = playbackState.autoMixStatus,
            sleepTimerRemainingSec = sleepTimerRemainingSec,
            sleepAfterCurrentTrack = sleepAfterCurrentTrack,
            onDismiss = { showEqSheet = false },
            onSetEqEnabled = onSetEqEnabled,
            onSetEqPreset = onSetEqPreset,
            onSetBassBoost = onSetBassBoost,
            onSetNormalizeVolume = onSetNormalizeVolume,
            onSetDolbyAtmos = onSetDolbyAtmos,
            onSetSpatialAudio = onSetSpatialAudio,
            onSetAutoMix = onToggleAutoMix,
            onSetAutoMixOverlapSeconds = onSetAutoMixOverlapSeconds,
            onSetCrossfadeSeconds = onSetCrossfadeSeconds,
            onSetPlaybackSpeed = onSetPlaybackSpeed,
            onSetPlaybackPitch = onSetPlaybackPitch,
            onResetSpeedAndPitch = onResetSpeedAndPitch,
            onSetSleepTimer = onSetSleepTimer,
            onSetSleepAfterCurrentTrack = onSetSleepAfterCurrentTrack,
            onOpenStatsForNerds = {
                showEqSheet = false
                showStatsForNerds = true
            }
        )
    }

    if (showStatsForNerds) {
        StatsForNerdsDialog(
            stats = playbackState.statsForNerds,
            track = track,
            onDismiss = { showStatsForNerds = false }
        )
    }
}
