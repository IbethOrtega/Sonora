package com.example.ui.player

import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.network.CanvasRepository
import com.example.model.CanvasArtwork
import com.example.model.PlaybackOrigin
import com.example.model.QueueMoodFilter
import com.example.model.SonoraSettings
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.ui.components.NowPlayingMenuActions
import com.example.ui.components.StatsForNerdsDialog
import com.example.ui.components.upgradeArtworkUrl
import com.example.ui.theme.CoverGradientSpec
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.computeDynamicCanvasColors
import com.example.ui.theme.sampleBitmapBottomStripAverageRgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private enum class PlayerSurfaceMode {
    PLAYER,
    LYRICS,
    QUEUE
}

internal fun shouldAutoHidePlayerControls(
    autoHideEnabled: Boolean,
    hasAnimatedVideo: Boolean,
    isPlaying: Boolean,
    isPlayerSurfaceActive: Boolean
): Boolean = autoHideEnabled && hasAnimatedVideo && isPlaying && isPlayerSurfaceActive

@Composable
internal fun rememberCoverGradientSpec(
    artworkUrl: String,
    fallbackPrimary: Color,
    accent: Color,
    liveVideoAvgRgb: Int? = null
): CoverGradientSpec {
    val context = LocalContext.current
    val fallback = remember(fallbackPrimary, accent) {
        computeDynamicCanvasColors(
            avgBottomRgb = fallbackPrimary.toArgbCompat(),
            fallbackAccent = accent,
            vibrantColorsRgb = listOf(fallbackPrimary.toArgbCompat())
        )
    }
    var staticSwatches by remember(artworkUrl) { mutableStateOf<List<Int>>(emptyList()) }
    var staticBottomAvgRgb by remember(artworkUrl) { mutableStateOf<Int?>(null) }

    LaunchedEffect(artworkUrl, fallbackPrimary, context) {
        if (artworkUrl.isBlank()) {
            staticSwatches = emptyList()
            staticBottomAvgRgb = null
            return@LaunchedEffect
        }
        val sampled = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(upgradeArtworkUrl(artworkUrl))
                    .size(96, 96)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                val bmp = (result?.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
                val pal = Palette.from(bmp).maximumColorCount(16).generate()
                val swatchRgbs = listOfNotNull(
                    pal.vibrantSwatch?.rgb,
                    pal.darkVibrantSwatch?.rgb,
                    pal.lightVibrantSwatch?.rgb,
                    pal.dominantSwatch?.rgb,
                    pal.mutedSwatch?.rgb
                ).distinct()

                val bottomAvg = sampleBitmapBottomStripAverageRgb(
                    bitmap = bmp,
                    stripFraction = 0.20f,
                    targetGrid = 32
                )
                bottomAvg to swatchRgbs
            }.getOrNull()
        }
        staticBottomAvgRgb = sampled?.first
        staticSwatches = sampled?.second.orEmpty()
    }

    val targetSpec = remember(liveVideoAvgRgb, staticBottomAvgRgb, staticSwatches, fallbackPrimary, accent, fallback) {
        val activeAvgRgb = liveVideoAvgRgb ?: staticBottomAvgRgb
        if (activeAvgRgb == null && staticSwatches.isEmpty()) {
            fallback
        } else {
            computeDynamicCanvasColors(
                avgBottomRgb = activeAvgRgb,
                fallbackAccent = fallbackPrimary,
                vibrantColorsRgb = staticSwatches
            )
        }
    }

    val top by animateColorAsState(targetSpec.topColor, tween(600), label = "cover_grad_top")
    val mid by animateColorAsState(targetSpec.midColor, tween(600), label = "cover_grad_mid")
    val bottom by animateColorAsState(targetSpec.bottomColor, tween(600), label = "cover_grad_bot")
    val artistTint by animateColorAsState(targetSpec.artistTint, tween(600), label = "cover_grad_artist")
    val trackBarTint by animateColorAsState(targetSpec.trackBarTint, tween(600), label = "cover_grad_track")
    val activeStarTint by animateColorAsState(targetSpec.activeStarTint, tween(600), label = "cover_grad_star")
    val progressTint by animateColorAsState(targetSpec.progressTint, tween(600), label = "cover_grad_progress")
    val bottomIconTint by animateColorAsState(targetSpec.bottomIconTint, tween(600), label = "cover_grad_icons")

    return CoverGradientSpec(
        topColor = top,
        midColor = mid,
        bottomColor = bottom,
        artistTint = artistTint,
        trackBarTint = trackBarTint,
        activeStarTint = activeStarTint,
        progressTint = progressTint,
        bottomIconTint = bottomIconTint
    )
}

private fun Color.toArgbCompat(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
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

    var liveVideoAvgRgb by remember(track.videoId) { mutableStateOf<Int?>(null) }
    var isAnimatedVideoReady by remember(track.videoId) { mutableStateOf(false) }

    val coverGradient = rememberCoverGradientSpec(
        artworkUrl = track.artworkUrl,
        fallbackPrimary = playbackState.ambientPrimary,
        accent = accent,
        liveVideoAvgRgb = liveVideoAvgRgb
    )

    val driftTransition = rememberInfiniteTransition(label = "cover_gradient_drift")
    val driftShift by driftTransition.animateFloat(
        initialValue = -0.05f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cover_gradient_shift"
    )

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

    LaunchedEffect(
        track.videoId,
        track.title,
        track.artist,
        track.album,
        settings.animatedArtworkEnabled,
        settings.spotifyCanvasEnabled
    ) {
        liveVideoAvgRgb = null
        isAnimatedVideoReady = false
        if (!settings.animatedArtworkEnabled) {
            canvasArtwork = null
            return@LaunchedEffect
        }
        canvasArtwork = CanvasRepository.fetchCanvas(
            videoId = track.videoId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            context = context,
            spotifyCanvasEnabled = settings.spotifyCanvasEnabled
        )
    }

    val isPlayerActive = activeMode == PlayerSurfaceMode.PLAYER &&
        !showEqSheet &&
        !showAudioOutputSheet &&
        !showStatsForNerds &&
        !showDrivingMode

    var isWideLayout by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var touchCounter by remember { mutableIntStateOf(0) }
    val canAutoHide = shouldAutoHidePlayerControls(
        autoHideEnabled = settings.autoHidePlayerControls,
        hasAnimatedVideo = isAnimatedVideoReady && settings.animatedArtworkEnabled,
        isPlaying = playbackState.isPlaying,
        isPlayerSurfaceActive = isPlayerActive && !isWideLayout
    )

    LaunchedEffect(canAutoHide, touchCounter, track.videoId) {
        if (!canAutoHide) {
            controlsVisible = true
        } else {
            controlsVisible = true
            delay(5_000L)
            controlsVisible = false
        }
    }

    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible || !canAutoHide) 1f else 0f,
        animationSpec = tween(durationMillis = 360),
        label = "player_controls_autohide_alpha"
    )
    val controlsSlideY by animateDpAsState(
        targetValue = if (controlsVisible || !canAutoHide) 0.dp else 24.dp,
        animationSpec = tween(durationMillis = 360),
        label = "player_controls_autohide_slide"
    )

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
        val validCanvas = canvasArtwork?.takeIf { it.matches(track.title, track.artist, track.album) }
        val statusSummary = validCanvas?.menuStatusLabel(preferPortrait = !isWideLayout)
            ?: CanvasRepository.getStatusInfo(track.videoId, settings.animatedArtworkEnabled).displaySummary()
        val actions = NowPlayingMenuActions(
            animatedArtworkEnabled = settings.animatedArtworkEnabled,
            canvasStatusText = statusSummary,
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
            .background(coverGradient.bottomColor)
            .pointerInput(canAutoHide) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touchCounter++
                    controlsVisible = true
                }
            }
            .onSizeChanged { size ->
                if (size.height > 0) {
                    queueCarryState.thresholdPx = size.height * 0.45f
                }
            }
            .testTag("now_playing_sheet")
    ) {
        val isWide = maxWidth >= 700.dp
        val screenHeight = maxHeight
        isWideLayout = isWide

        if (isWide) {
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
                            0.0f to Color.Black.copy(alpha = maxOf(0.50f, backdropContrast.topBandScrimAlpha)),
                            1.0f to Color.Transparent
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
                PlayerTopDragHandle(
                    onCollapse = onCollapse,
                    dismissDragModifier = dismissDragModifier
                )
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
                    coverGradient = coverGradient,
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
            }
        } else {
            // Portrait: live slowly drifting 3-color vertical cover gradient
            val effectiveShift = if (reducedMotion) 0f else driftShift
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val h = size.height.coerceAtLeast(1f)
                        val w = size.width.coerceAtLeast(1f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to coverGradient.topColor,
                                (0.52f + effectiveShift).coerceIn(0.32f, 0.72f) to coverGradient.midColor,
                                1.0f to coverGradient.bottomColor
                            )
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    coverGradient.topColor.copy(alpha = 0.28f),
                                    Color.Transparent
                                ),
                                center = Offset(w * (0.5f + effectiveShift), h * 0.50f),
                                radius = w * 0.95f
                            )
                        )
                    }
            )

            AnimatedContent(
                targetState = activeMode,
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(180)) },
                modifier = Modifier.fillMaxSize(),
                label = "np_portrait_mode_transition"
            ) { mode ->
                when (mode) {
                    PlayerSurfaceMode.PLAYER -> {
                        val topArtHeight = (screenHeight * 0.65f).coerceAtLeast(300.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = null,
                                    indication = null,
                                    enabled = canAutoHide && !controlsVisible
                                ) {
                                    touchCounter++
                                    controlsVisible = true
                                }
                        ) {
                            // Top 65% full-bleed animated cover or static cover extending behind status bar,
                            // dissolving seamlessly at its bottom edge into the matching dynamic gradient
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(topArtHeight)
                                    .align(Alignment.TopCenter)
                                    .graphicsLayer { translationY = queueCarryState.dragOffsetPx * 0.35f }
                                    .then(dismissDragModifier)
                            ) {
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
                                        ambientPrimary = coverGradient.topColor,
                                        showStaticFallback = true,
                                        cornerRadius = 0.dp,
                                        showBorder = false,
                                        showShadow = false,
                                        fadeBottomFraction = 0.38f,
                                        pauseDarkenAlpha = 0.15f,
                                        onBottomSampleRgb = { rgb ->
                                            if (animTrack.videoId == track.videoId) {
                                                liveVideoAvgRgb = rgb
                                            }
                                        },
                                        onActiveVideoStateChanged = { ready ->
                                            if (animTrack.videoId == track.videoId) {
                                                isAnimatedVideoReady = ready
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                // Subtle top status-bar contrast scrim
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(104.dp)
                                        .align(Alignment.TopCenter)
                                        .background(
                                            Brush.verticalGradient(
                                                0.0f to Color.Black.copy(alpha = 0.52f),
                                                1.0f to Color.Transparent
                                            )
                                        )
                                )

                                // Top center: drag handle + "Reproduciendo desde [origen]" + Album name
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                                        .align(Alignment.TopCenter)
                                        .graphicsLayer { alpha = controlsAlpha },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(42.dp)
                                            .height(5.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.52f))
                                            .clickable(onClick = onCollapse)
                                            .testTag("now_playing_collapse_button")
                                    )
                                    PlaybackOriginCaption(
                                        origin = playbackState.playbackOrigin,
                                        onClick = {
                                            playbackState.playbackOrigin?.let { origin ->
                                                onCollapse()
                                                onNavigateToOrigin(origin)
                                            }
                                        }
                                    )
                                    val albumName = track.album
                                    if (!albumName.isNullOrBlank()) {
                                        Text(
                                            text = albumName,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = Color.White.copy(alpha = 0.84f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onOpenAlbum(track) }
                                                .padding(horizontal = 8.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }

                            PlayerPrimaryColumn(
                                track = track,
                                playbackState = playbackState,
                                positionMsFlow = positionMsFlow,
                                settings = settings,
                                djLiveState = djLiveState,
                                isLiked = isLiked,
                                dismissDragModifier = dismissDragModifier,
                                isWideHero = false,
                                isFullscreenPortraitCanvas = false,
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
                                coverGradient = coverGradient,
                                isPortraitFullBleed = true,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 24.dp, vertical = 8.dp)
                                    .graphicsLayer {
                                        alpha = controlsAlpha
                                        translationY = controlsSlideY.toPx()
                                    }
                            )
                        }
                    }

                    PlayerSurfaceMode.LYRICS -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp)
                        ) {
                            PlayerTopDragHandle(
                                onCollapse = onCollapse,
                                dismissDragModifier = dismissDragModifier
                            )
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
                                tintColor = coverGradient.artistTint,
                                audioOutputLabel = playbackState.activeAudioRoute.label,
                                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                            )
                        }
                    }

                    PlayerSurfaceMode.QUEUE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp)
                        ) {
                            PlayerTopDragHandle(
                                onCollapse = onCollapse,
                                dismissDragModifier = dismissDragModifier
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.Black.copy(alpha = 0.16f))
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
                                tintColor = coverGradient.artistTint,
                                audioOutputLabel = playbackState.activeAudioRoute.label,
                                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                            )
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

@Composable
private fun PlayerTopDragHandle(
    onCollapse: () -> Unit,
    dismissDragModifier: Modifier
) {
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
}
