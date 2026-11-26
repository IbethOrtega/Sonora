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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.ui.components.NowPlayingMenuActions
import com.example.ui.components.StatsForNerdsDialog
import com.example.ui.theme.LocalSonoraAccent
import kotlinx.coroutines.flow.StateFlow

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
    onOpenPlayerOptions: ((Track, NowPlayingMenuActions) -> Unit)? = null,
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
    initialOpenQueue: Boolean = false,
    openQueueTrigger: Int = 0
) {
    val track = playbackState.currentTrack ?: return
    var activeMode by remember(initialOpenQueue) {
        mutableStateOf(if (initialOpenQueue) PlayerSurfaceMode.QUEUE else PlayerSurfaceMode.PLAYER)
    }
    LaunchedEffect(initialOpenQueue, openQueueTrigger) {
        if (initialOpenQueue || openQueueTrigger > 0) {
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

    LaunchedEffect(track.videoId, track.title, track.artist, track.album, settings.animatedArtworkEnabled) {
        if (!settings.animatedArtworkEnabled) {
            canvasArtwork = null
            return@LaunchedEffect
        }
        canvasArtwork = CanvasRepository.fetchCanvas(
            videoId = track.videoId,
            title = track.title,
            artist = track.artist,
            album = track.album
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
            onDragCancel = { queueCarryState.reset() }
        )
    }

    val openOptionsWithMenu: () -> Unit = {
        val actions = NowPlayingMenuActions(
            animatedArtworkEnabled = settings.animatedArtworkEnabled,
            autoMixEnabled = settings.autoMixEnabled,
            shuffleEnabled = playbackState.shuffleEnabled,
            repeatMode = playbackState.repeatMode,
            djLiveActive = djLiveState.isActive,
            onToggleAnimatedArtwork = onToggleAnimatedArtwork,
            onToggleAutoMix = onToggleAutoMix,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            onOpenVoiceDj = onOpenVoiceDj,
            onOpenDrivingMode = { showDrivingMode = true },
            onOpenEqualizer = { showEqSheet = true },
            onOpenAudioOutput = { showAudioOutputSheet = true }
        )
        if (onOpenPlayerOptions != null) {
            onOpenPlayerOptions(track, actions)
        } else {
            onOpenOptions(track)
        }
    }

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

        val hasFullscreenPortraitCanvas = !isWide &&
            activeMode == PlayerSurfaceMode.PLAYER &&
            settings.animatedArtworkEnabled &&
            canvasArtwork?.portraitUrl?.isNotBlank() == true &&
            canvasArtwork?.matches(track.title, track.artist, track.album) == true

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

        if (hasFullscreenPortraitCanvas) {
            PlayerSkipMotion(
                targetState = track,
                direction = motionDirection,
                reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxSize()
            ) { animTrack ->
                CanvasArtworkPlayer(
                    track = animTrack,
                    canvasArtwork = if (animTrack.videoId == track.videoId) canvasArtwork else null,
                    animatedArtworkEnabled = settings.animatedArtworkEnabled,
                    allowMobileData = settings.animatedArtworkMobileData,
                    preferPortrait = true,
                    isPlaying = playbackState.isPlaying,
                    isPlayerActive = isPlayerActive,
                    ambientPrimary = accent,
                    showStaticFallback = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Top & bottom dark gradients so drag pill, lyrics, and bottom controls remain legible
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = maxOf(0.55f, backdropContrast.topBandScrimAlpha)),
                        1.0f to Color.Transparent
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (hasFullscreenPortraitCanvas) 390.dp else 260.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = if (hasFullscreenPortraitCanvas) 0.72f else 0.35f),
                        1.0f to Color.Black.copy(alpha = if (hasFullscreenPortraitCanvas) 0.92f else 0.56f)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // Clean top bar: centered drag handle pill ONLY
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp)
                    .then(dismissDragModifier),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.44f))
                        .clickable(onClick = onCollapse)
                        .testTag("now_playing_collapse_button")
                )
            }

            if (isWide) {
                LandscapePlayer(
                    track = track,
                    playbackState = playbackState,
                    positionMsFlow = positionMsFlow,
                    settings = settings,
                    djLiveState = djLiveState,
                    isLiked = isLiked,
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
                    onOpenVoiceDj = onOpenVoiceDj,
                    onSwitchDjVibe = onSwitchDjVibe,
                    onStopDjLive = onStopDjLive,
                    onOpenAlbum = { onOpenAlbum(track) },
                    onOpenArtist = { onOpenArtist(track.artist, track.artistId) },
                    onToggleLike = { onToggleLike(track) },
                    onOpenOptions = openOptionsWithMenu,
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
                                playbackState = playbackState,
                                positionMsFlow = positionMsFlow,
                                settings = settings,
                                djLiveState = djLiveState,
                                isLiked = isLiked,
                                dismissDragModifier = dismissDragModifier,
                                isWideHero = false,
                                isFullscreenPortraitCanvas = hasFullscreenPortraitCanvas,
                                isPlayerActive = isPlayerActive,
                                canvasArtwork = canvasArtwork,
                                motionDirection = motionDirection,
                                reducedMotion = reducedMotion,
                                queueCarryOffsetPx = queueCarryState.dragOffsetPx * 0.45f,
                                isLyricsOpen = false,
                                isQueueOpen = false,
                                onOpenAlbum = { onOpenAlbum(track) },
                                onOpenArtist = { onOpenArtist(track.artist, track.artistId) },
                                onToggleLike = { onToggleLike(track) },
                                onOpenOptions = openOptionsWithMenu,
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
                                onOpenVoiceDj = onOpenVoiceDj,
                                onSwitchDjVibe = onSwitchDjVibe,
                                onStopDjLive = onStopDjLive,
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
                                        lyricsOffsetMs = settings.lyricsOffsetFor(track.videoId),
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
