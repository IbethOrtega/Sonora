package com.example.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MotionPhotosAuto
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SpatialAudio
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.model.AudioOutputRoute
import com.example.model.EqPreset
import com.example.model.QueueMoodFilter
import com.example.model.RepeatMode
import com.example.model.SonoraSettings
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.player.SonoraPlaybackBridge
import com.example.ui.components.AnimatedEqualizer
import com.example.ui.components.GlassCard
import com.example.ui.components.LyricsShareCardDialog
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.StatsForNerdsDialog
import com.example.ui.components.formatTimeMs
import com.example.ui.components.upgradeArtworkUrl
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.theme.TabularTimerTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class NowPlayingTab {
    PLAYER,
    LYRICS,
    QUEUE
}

@Composable
fun NowPlayingSheet(
    playbackState: PlaybackUiState,
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
    onToggleAnimatedArtwork: (Boolean) -> Unit,
    onStartDjLive: () -> Unit,
    onOpenVoiceDj: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit,
    onAdjustLyricsOffset: (Long) -> Unit,
    onResetLyricsOffset: () -> Unit,
    onSetEqEnabled: (Boolean) -> Unit,
    onSetEqPreset: (EqPreset) -> Unit,
    onSetBassBoost: (Int) -> Unit,
    onSetNormalizeVolume: (Boolean) -> Unit,
    onToggleAutoMix: (Boolean) -> Unit,
    onSetAutoMixOverlapSeconds: (Int) -> Unit,
    onSetDolbyAtmos: (Boolean) -> Unit,
    onSetSpatialAudio: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenOptions: (Track) -> Unit,
    onOpenQueueItemOptions: (Track, Int) -> Unit = { track, _ -> onOpenOptions(track) },
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
    onOpenSongMixer: () -> Unit = {}
) {
    BackHandler { onCollapse() }

    val track = playbackState.currentTrack ?: return
    var activeTab by remember { mutableStateOf(NowPlayingTab.PLAYER) }
    var showEqSheet by remember { mutableStateOf(false) }
    var showStatsForNerds by remember { mutableStateOf(false) }
    var showDrivingMode by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val accent = LocalSonoraAccent.current
    val highResBackdropUrl = remember(track.artworkUrl) { upgradeArtworkUrl(track.artworkUrl) }
    val backdropRequest = remember(highResBackdropUrl, context) {
        if (highResBackdropUrl.isBlank()) null
        else ImageRequest.Builder(context)
            .data(highResBackdropUrl)
            .crossfade(400)
            .build()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070A))
            .testTag("now_playing_sheet")
    ) {
        val isWide = maxWidth >= 700.dp

        if (backdropRequest != null) {
            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.blur(90.dp)
            } else {
                Modifier
            }
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier
                    .fillMaxSize()
                    .then(blurModifier)
                    .alpha(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.46f else 0.18f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.62f),
                        0.45f to Color(0xFF07070A).copy(alpha = 0.68f),
                        0.82f to Color(0xFF050507).copy(alpha = 0.90f),
                        1.0f to Color.Black.copy(alpha = 0.96f)
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp)
            ) {
                // Apple Music top drag handle + minimal header bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            var totalDrag = 0f
                            detectVerticalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onVerticalDrag = { _, dragAmount ->
                                    if (dragAmount > 0f) totalDrag += dragAmount
                                },
                                onDragEnd = {
                                    if (totalDrag > 90f) onCollapse()
                                }
                            )
                        },
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
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
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        val immersionLabels = buildList {
                            add(playbackState.codecLabel)
                            if (settings.dolbyAtmosEnabled) add("Atmos")
                            if (settings.spatialAudioEnabled) add("360°")
                            if (settings.eqEnabled) add("EQ")
                            if (settings.crossfadeSeconds < 0) add("Fundido Auto")
                            else if (settings.crossfadeSeconds > 0) add("Fundido ${settings.crossfadeSeconds}s")
                            if (abs(settings.playbackSpeed - 1.0f) > 0.02f) {
                                add("${"%.2f".format(settings.playbackSpeed)}×")
                            }
                            if (sleepTimerRemainingSec != null || sleepAfterCurrentTrack) {
                                add("Timer")
                            }
                        }.joinToString(" • ")
                        Surface(
                            onClick = { showEqSheet = true },
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(
                                0.7.dp,
                                if (settings.eqEnabled || settings.dolbyAtmosEnabled || settings.spatialAudioEnabled ||
                                    abs(settings.playbackSpeed - 1.0f) > 0.02f || sleepTimerRemainingSec != null || sleepAfterCurrentTrack
                                ) {
                                    LocalSonoraAccent.current.copy(alpha = 0.78f)
                                } else {
                                    Color.White.copy(alpha = 0.26f)
                                }
                            ),
                            modifier = Modifier.testTag("now_playing_eq_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (playbackState.isPlaying) {
                                    AnimatedEqualizer(
                                        isPlaying = true,
                                        color = Color.White.copy(alpha = 0.92f),
                                        modifier = Modifier.height(12.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Equalizer,
                                        contentDescription = "Audio y ecualizador",
                                        tint = LocalSonoraAccent.current,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = immersionLabels,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = Color.White.copy(alpha = 0.94f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = { onOpenOptions(track) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("now_playing_more_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = "Opciones de canción",
                                tint = Color.White.copy(alpha = 0.90f)
                            )
                        }
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PlayerMainContent(
                                track = track,
                                playbackState = playbackState,
                                animatedArtworkEnabled = settings.animatedArtworkEnabled,
                                autoMixEnabled = settings.autoMixEnabled,
                                crossfadeSeconds = settings.crossfadeSeconds,
                                djLiveState = djLiveState,
                                isLiked = isLiked,
                                isWideHero = true,
                                onToggleAnimatedArtwork = onToggleAnimatedArtwork,
                                onToggleAutoMix = onToggleAutoMix,
                                onOpenVoiceDj = onOpenVoiceDj,
                                onOpenDrivingMode = { showDrivingMode = true },
                                onSwitchDjVibe = onSwitchDjVibe,
                                onStopDjLive = onStopDjLive,
                                onTogglePlayPause = onTogglePlayPause,
                                onSkipNext = onSkipNext,
                                onSkipPrevious = onSkipPrevious,
                                onSeekTo = onSeekTo,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                onToggleLike = { onToggleLike(track) },
                                onOpenOptions = { onOpenOptions(track) },
                                onRetry = onRetry
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(vertical = 8.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(Color.Black.copy(alpha = 0.18f))
                                .border(
                                    width = 0.6.dp,
                                    color = Color.White.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(26.dp)
                                )
                        ) {
                            if (activeTab == NowPlayingTab.QUEUE) {
                                QueuePane(
                                    playbackState = playbackState,
                                    onSkipToIndex = onSkipToQueueIndex,
                                    onMoveItem = onMoveQueueItem,
                                    onRemoveItem = onRemoveQueueItem,
                                    onOpenItemOptions = onOpenQueueItemOptions,
                                    onRetuneQueueMood = onRetuneQueueMood,
                                    onOpenSongMixer = onOpenSongMixer
                                )
                            } else {
                                LyricsPane(
                                    track = track,
                                    playbackState = playbackState,
                                    lyricsOffsetMs = settings.lyricsOffsetMs,
                                    onAdjustLyricsOffset = onAdjustLyricsOffset,
                                    onResetLyricsOffset = onResetLyricsOffset,
                                    onReloadLyrics = onReloadLyrics,
                                    onSeekTo = onSeekTo,
                                    onTogglePlayPause = onTogglePlayPause
                                )
                            }
                        }
                    }
                } else {
                    AnimatedContent(
                        targetState = activeTab,
                        transitionSpec = {
                            fadeIn(tween(240)) togetherWith fadeOut(tween(180))
                        },
                        modifier = Modifier.weight(1f),
                        label = "np_tab_transition"
                    ) { tab ->
                        when (tab) {
                            NowPlayingTab.PLAYER -> {
                                PlayerMainContent(
                                    track = track,
                                    playbackState = playbackState,
                                    animatedArtworkEnabled = settings.animatedArtworkEnabled,
                                    autoMixEnabled = settings.autoMixEnabled,
                                    crossfadeSeconds = settings.crossfadeSeconds,
                                    djLiveState = djLiveState,
                                    isLiked = isLiked,
                                    isWideHero = false,
                                    onToggleAnimatedArtwork = onToggleAnimatedArtwork,
                                    onToggleAutoMix = onToggleAutoMix,
                                    onOpenVoiceDj = onOpenVoiceDj,
                                    onOpenDrivingMode = { showDrivingMode = true },
                                    onSwitchDjVibe = onSwitchDjVibe,
                                    onStopDjLive = onStopDjLive,
                                    onTogglePlayPause = onTogglePlayPause,
                                    onSkipNext = onSkipNext,
                                    onSkipPrevious = onSkipPrevious,
                                    onSeekTo = onSeekTo,
                                    onToggleShuffle = onToggleShuffle,
                                    onCycleRepeat = onCycleRepeat,
                                    onToggleLike = { onToggleLike(track) },
                                    onOpenOptions = { onOpenOptions(track) },
                                    onRetry = onRetry
                                )
                            }

                            NowPlayingTab.LYRICS -> {
                                LyricsPane(
                                    track = track,
                                    playbackState = playbackState,
                                    lyricsOffsetMs = settings.lyricsOffsetMs,
                                    onAdjustLyricsOffset = onAdjustLyricsOffset,
                                    onResetLyricsOffset = onResetLyricsOffset,
                                    onReloadLyrics = onReloadLyrics,
                                    onSeekTo = onSeekTo,
                                    onTogglePlayPause = onTogglePlayPause
                                )
                            }

                            NowPlayingTab.QUEUE -> {
                                GlassCard(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 6.dp),
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    QueuePane(
                                        playbackState = playbackState,
                                        onSkipToIndex = onSkipToQueueIndex,
                                        onMoveItem = onMoveQueueItem,
                                        onRemoveItem = onRemoveQueueItem,
                                        onOpenItemOptions = onOpenQueueItemOptions,
                                        onRetuneQueueMood = onRetuneQueueMood,
                                        onOpenSongMixer = onOpenSongMixer
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom frosted Apple Music mode pills (+ DJ Live!)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassModeButton(
                        icon = Icons.Rounded.Lyrics,
                        label = "Letras",
                        selected = activeTab == NowPlayingTab.LYRICS,
                        tag = "tab_lyrics_button",
                        onClick = {
                            activeTab = if (activeTab == NowPlayingTab.LYRICS) NowPlayingTab.PLAYER else NowPlayingTab.LYRICS
                        }
                    )
                    GlassModeButton(
                        icon = Icons.Rounded.AutoAwesome,
                        label = if (djLiveState.isActive) "DJ Sony" else "DJ",
                        selected = djLiveState.isActive,
                        tag = "np_dj_live_button",
                        onClick = {
                            if (djLiveState.isActive) onStopDjLive() else onStartDjLive()
                        }
                    )
                    GlassModeButton(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        label = "Playlist",
                        selected = false,
                        tag = "np_add_playlist_button",
                        onClick = { onAddToPlaylist(track) }
                    )
                    GlassModeButton(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        label = "Cola (${playbackState.queue.size})",
                        selected = activeTab == NowPlayingTab.QUEUE,
                        tag = "tab_queue_button",
                        onClick = {
                            activeTab = if (activeTab == NowPlayingTab.QUEUE) NowPlayingTab.PLAYER else NowPlayingTab.QUEUE
                        }
                    )
                }
            }
        }

        if (showDrivingMode) {
            DrivingModeOverlay(
                track = track,
                playbackState = playbackState,
                djLiveActive = djLiveState.isActive,
                onDismiss = { showDrivingMode = false },
                onTogglePlayPause = onTogglePlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                onOpenVoiceDj = onOpenVoiceDj
            )
        }
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
private fun FullBleedAppleMusicCover(
    url: String,
    contentDescription: String,
    animatedEnabled: Boolean,
    isPlaying: Boolean,
    primaryGlow: Color,
    secondaryGlow: Color
) {
    val context = LocalContext.current
    val highResUrl = remember(url) { upgradeArtworkUrl(url) }
    val imageRequest = remember(highResUrl, context) {
        if (highResUrl.isBlank()) null
        else ImageRequest.Builder(context)
            .data(highResUrl)
            .crossfade(380)
            .allowHardware(true)
            .build()
    }

    val motion = rememberInfiniteTransition(label = "apple_fullbleed_cover")
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "full_phase"
    )
    val shimmerSweep by motion.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_200, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "full_shimmer"
    )

    val playScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.965f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "play_scale"
    )

    val amp = if (animatedEnabled && isPlaying) 1f else if (animatedEnabled) 0.25f else 0f
    val kineticScale = (if (animatedEnabled) 1.09f else 1.0f) + (0.055f * sin(phase) * amp)
    val panX = 18f * cos(phase * 0.7f) * amp
    val panY = 14f * sin(phase * 0.9f) * amp
    val swayRot = 1.1f * sin(phase * 0.5f) * amp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(secondaryGlow.copy(alpha = 0.35f))
    ) {
        // Full-bleed artwork occupying almost the entire playback canvas (top 0% -> ~78%)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    // Seamless Apple Music feather blend at bottom of the cover
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Black,
                            0.68f to Color.Black,
                            0.90f to Color.Black.copy(alpha = 0.45f),
                            1.0f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                placeholder = painterResource(R.drawable.img_album_fallback),
                error = painterResource(R.drawable.img_album_fallback),
                fallback = painterResource(R.drawable.img_album_fallback),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = kineticScale * playScale
                        scaleY = kineticScale * playScale
                        translationX = panX
                        translationY = panY
                        rotationZ = swayRot
                    }
            )

            if (animatedEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val startX = shimmerSweep * size.width
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.13f * amp),
                                        primaryGlow.copy(alpha = 0.10f * amp),
                                        Color.Transparent
                                    ),
                                    start = Offset(startX - size.width * 0.4f, 0f),
                                    end = Offset(startX + size.width * 0.4f, size.height)
                                )
                            )
                        }
                )
            }
        }
    }
}

@Composable
private fun PlayerMainContent(
    track: Track,
    playbackState: PlaybackUiState,
    animatedArtworkEnabled: Boolean,
    autoMixEnabled: Boolean,
    crossfadeSeconds: Int,
    djLiveState: DjLiveState,
    isLiked: Boolean,
    isWideHero: Boolean,
    onToggleAnimatedArtwork: (Boolean) -> Unit,
    onToggleAutoMix: (Boolean) -> Unit,
    onOpenVoiceDj: () -> Unit,
    onOpenDrivingMode: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenOptions: () -> Unit,
    onRetry: () -> Unit
) {
    val accent = LocalSonoraAccent.current
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val duration = playbackState.durationMs.coerceAtLeast(1L)
    val liveFraction = (playbackState.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val sliderValue = if (isScrubbing) scrubFraction else liveFraction
    val displayPosMs = if (isScrubbing) (scrubFraction * duration).toLong() else playbackState.positionMs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Cover Mode Toggle Pill + AutoMix + Voz + Modo Conducción
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverModeTogglePill(
                animatedEnabled = animatedArtworkEnabled,
                onSelect = onToggleAnimatedArtwork
            )

            Surface(
                onClick = { onToggleAutoMix(!autoMixEnabled) },
                shape = RoundedCornerShape(50),
                color = Color(0xFF17171C).copy(alpha = 0.85f),
                border = BorderStroke(
                    1.dp,
                    if (autoMixEnabled) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier.testTag("now_playing_automix_toggle")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "Mezcla automática",
                        tint = if (autoMixEnabled) accent else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (autoMixEnabled) "AutoMix ON" else "AutoMix",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (autoMixEnabled) Color.White else Color.White.copy(alpha = 0.72f)
                    )
                }
            }

            Surface(
                onClick = onOpenVoiceDj,
                shape = RoundedCornerShape(50),
                color = Color(0xFF17171C).copy(alpha = 0.85f),
                border = BorderStroke(
                    1.dp,
                    if (djLiveState.isActive) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier.testTag("np_voice_dj_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Hablar con DJ Sony",
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Voz",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White.copy(alpha = 0.90f)
                    )
                }
            }

            Surface(
                onClick = onOpenDrivingMode,
                shape = RoundedCornerShape(50),
                color = Color(0xFF17171C).copy(alpha = 0.85f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                modifier = Modifier.testTag("np_driving_mode_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsCar,
                        contentDescription = "Modo conducción",
                        tint = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Auto",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White.copy(alpha = 0.90f)
                    )
                }
            }
        }

        if (isWideHero) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (animatedArtworkEnabled) {
                    AnimatedCoverArtwork(
                        url = track.artworkUrl,
                        contentDescription = track.title,
                        isPlaying = playbackState.isPlaying,
                        ambientPrimary = playbackState.ambientPrimary,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    SonoraArtwork(
                        url = track.artworkUrl,
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 18.dp
                    )
                }
            }
        } else {
            val coverScale by animateFloatAsState(
                targetValue = if (playbackState.isPlaying) 1.0f else 0.92f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "apple_cover_scale"
            )
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .widthIn(max = 310.dp)
                        .aspectRatio(1f)
                        .scale(coverScale)
                        .shadow(
                            elevation = if (playbackState.isPlaying) 28.dp else 10.dp,
                            shape = RoundedCornerShape(18.dp),
                            ambientColor = Color.Black,
                            spotColor = Color.Black
                        )
                ) {
                    if (animatedArtworkEnabled) {
                        AnimatedCoverArtwork(
                            url = track.artworkUrl,
                            contentDescription = track.title,
                            isPlaying = playbackState.isPlaying,
                            ambientPrimary = accent,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        SonoraArtwork(
                            url = track.artworkUrl,
                            contentDescription = track.title,
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp)),
                            cornerRadius = 18.dp
                        )
                    }
                }
            }
        }

        if (playbackState.isPlaying) {
            LiveAudioVisualizerStrip(
                accent = accent,
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .padding(bottom = 6.dp)
            )
        }

        if (playbackState.autoMixStatus != null && (autoMixEnabled || crossfadeSeconds != 0)) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.14f),
                border = BorderStroke(0.6.dp, accent.copy(alpha = 0.55f)),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = playbackState.autoMixStatus,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.94f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Live DJ caption card when DJ Live is active
        if (djLiveState.isActive) {
            DjLivePlayerBanner(
                djLiveState = djLiveState,
                onOpenVoiceDj = onOpenVoiceDj,
                onSwitchVibe = onSwitchDjVibe,
                onStop = onStopDjLive
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (playbackState.errorMessage != null) {
                var showErrorDetail by remember(playbackState.errorMessage, playbackState.errorDetail) {
                    mutableStateOf(false)
                }
                val hasDetail = !playbackState.errorDetail.isNullOrBlank()
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
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
                                text = playbackState.errorMessage,
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
                                        color = LocalSonoraAccent.current
                                    )
                                }
                            }
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(containerColor = LocalSonoraAccent.current),
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
                                    .heightIn(max = 150.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = playbackState.errorDetail.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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

            // Track Title + Artist + Like & More buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!playbackState.streamNotice.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.testTag("now_playing_stream_notice")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                tint = LocalSonoraAccent.current.copy(alpha = 0.85f),
                                modifier = Modifier.size(13.dp)
                            )
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

                IconButton(
                    onClick = onToggleLike,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_like_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isLiked) "Quitar me gusta" else "Me gusta",
                            tint = if (isLiked) LocalSonoraAccent.current else Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onOpenOptions,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_quick_options_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = "Más opciones",
                            tint = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (isScrubbing) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF17171C),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.78f)),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp)
                ) {
                    Text(
                        text = formatTimeMs(displayPosMs),
                        style = TabularTimerTextStyle,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                }
            }

            val crossfadeZoneFraction = remember(duration, crossfadeSeconds, autoMixEnabled) {
                when {
                    crossfadeSeconds > 0 -> ((crossfadeSeconds * 1000f) / duration.toFloat()).coerceIn(0f, 0.28f)
                    crossfadeSeconds < 0 || autoMixEnabled -> (6_000f / duration.toFloat()).coerceIn(0f, 0.25f)
                    else -> 0f
                }
            }

            AppleMusicScrubber(
                fraction = sliderValue,
                isScrubbing = isScrubbing,
                crossfadeZoneFraction = crossfadeZoneFraction,
                accent = accent,
                onScrubStart = { f ->
                    isScrubbing = true
                    scrubFraction = f
                },
                onScrubChange = { f ->
                    isScrubbing = true
                    scrubFraction = f
                },
                onScrubEnd = { f ->
                    onSeekTo((f * duration).toLong())
                    isScrubbing = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("now_playing_slider")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimeMs(displayPosMs),
                    style = TabularTimerTextStyle,
                    color = if (isScrubbing) Color.White else Color.White.copy(alpha = 0.62f)
                )
                Text(
                    text = "-${formatTimeMs((duration - displayPosMs).coerceAtLeast(0L))}",
                    style = TabularTimerTextStyle,
                    color = if (isScrubbing) Color.White else Color.White.copy(alpha = 0.62f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Clean Apple Music borderless transport controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Aleatorio",
                        tint = if (playbackState.shuffleEnabled) LocalSonoraAccent.current else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(23.dp)
                    )
                }

                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("now_playing_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF18181D).copy(alpha = 0.88f))
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.85f),
                                    Color.White.copy(alpha = 0.26f),
                                    accent.copy(alpha = 0.50f)
                                )
                            ),
                            shape = CircleShape
                        )
                        .clickable(onClick = onTogglePlayPause)
                        .testTag("now_playing_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (playbackState.isBuffering) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.8.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("now_playing_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_repeat_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == RepeatMode.ONE) {
                            Icons.Rounded.RepeatOne
                        } else Icons.Rounded.Repeat,
                        contentDescription = "Repetir",
                        tint = if (playbackState.repeatMode != RepeatMode.OFF) LocalSonoraAccent.current else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(23.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Apple Music In-Player Volume Control Bar
            AppleMusicVolumeBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("now_playing_volume_slider")
            )

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun CoverModeTogglePill(
    animatedEnabled: Boolean,
    onSelect: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.10f),
        border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverModeChip(
                icon = Icons.Rounded.Image,
                label = "Original",
                selected = !animatedEnabled,
                tag = "cover_mode_original",
                onClick = { onSelect(false) }
            )
            CoverModeChip(
                icon = Icons.Rounded.MotionPhotosAuto,
                label = "Animada",
                selected = animatedEnabled,
                tag = "cover_mode_animated",
                onClick = { onSelect(true) }
            )
        }
    }
}

@Composable
private fun CoverModeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent,
        modifier = Modifier.testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) Color.White else Color.White.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
private fun AnimatedCoverArtwork(
    url: String,
    contentDescription: String,
    isPlaying: Boolean,
    ambientPrimary: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val highResUrl = remember(url) { upgradeArtworkUrl(url) }
    val imageRequest = remember(highResUrl, context) {
        if (highResUrl.isBlank()) null
        else ImageRequest.Builder(context)
            .data(highResUrl)
            .crossfade(280)
            .allowHardware(true)
            .build()
    }

    val motion = rememberInfiniteTransition(label = "animated_cover_motion")
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "cover_phase"
    )
    val shimmerSweep by motion.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5_500, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "shimmer_sweep"
    )

    val amp = if (isPlaying) 1f else 0.2f
    val kineticScale = 1.08f + (0.06f * sin(phase) * amp)
    val panX = 14f * cos(phase * 0.7f) * amp
    val panY = 12f * sin(phase * 0.9f) * amp
    val swayRot = 1.4f * sin(phase * 0.5f) * amp

    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = if (isPlaying) 28.dp else 12.dp,
                shape = shape,
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            placeholder = painterResource(R.drawable.img_album_fallback),
            error = painterResource(R.drawable.img_album_fallback),
            fallback = painterResource(R.drawable.img_album_fallback),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = kineticScale
                    scaleY = kineticScale
                    translationX = panX
                    translationY = panY
                    rotationZ = swayRot
                }
        )

        // Subtle liquid glass shimmer highlight across animated cover
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val startX = shimmerSweep * size.width
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.14f * amp),
                                Color.Transparent
                            ),
                            start = Offset(startX - size.width * 0.35f, 0f),
                            end = Offset(startX + size.width * 0.35f, size.height)
                        )
                    )
                }
        )
    }
}

@Composable
private fun DjLivePlayerBanner(
    djLiveState: DjLiveState,
    onOpenVoiceDj: () -> Unit,
    onSwitchVibe: () -> Unit,
    onStop: () -> Unit
) {
    val accent = LocalSonoraAccent.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.12f),
        border = BorderStroke(0.7.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("np_dj_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedEqualizer(
                isPlaying = djLiveState.isSpeaking,
                color = accent,
                modifier = Modifier.height(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            var showDetail by remember(djLiveState.aiTechnicalDetail) { mutableStateOf(false) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = djLiveState.segmentTitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = accent
                )
                Text(
                    text = djLiveState.spokenCaption.ifBlank { djLiveState.currentVibe.subtitle },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.90f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val bannerNotice = djLiveState.aiNotice
                    ?: if (djLiveState.voiceEngine == com.example.player.DjVoiceEngine.DEVICE) {
                        "Voz del dispositivo"
                    } else null
                if (!bannerNotice.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = bannerNotice,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!djLiveState.aiTechnicalDetail.isNullOrBlank()) {
                            Text(
                                text = if (showDetail) "Ocultar" else "Ver detalle",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = accent,
                                modifier = Modifier
                                    .clickable { showDetail = !showDetail }
                                    .testTag("np_dj_detail_toggle")
                            )
                        }
                    }
                    if (showDetail && !djLiveState.aiTechnicalDetail.isNullOrBlank()) {
                        Text(
                            text = djLiveState.aiTechnicalDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(
                onClick = onOpenVoiceDj,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("np_dj_banner_voice_button")
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = "Hablar con DJ Sony", tint = accent, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onSwitchVibe,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("np_dj_switch_vibe_button")
            ) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Cambiar vibra", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("np_dj_stop_button")
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = "Detener DJ Sony", tint = accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AppleMusicVolumeBar(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVol = remember(audioManager) {
        (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
    }

    var currentVolFraction by remember {
        mutableFloatStateOf(
            audioManager?.let {
                it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat()
            } ?: 0.7f
        )
    }
    var isDragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }

    DisposableEffect(context, audioManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!isDragging && audioManager != null) {
                    currentVolFraction = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat())
                        .coerceIn(0f, 1f)
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun applyVolumeFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        currentVolFraction = clamped
        val targetInt = (clamped * maxVol).roundToInt().coerceIn(0, maxVol)
        runCatching {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetInt, 0)
        }
    }

    val barHeight by animateDpAsState(
        targetValue = if (isDragging) 9.dp else 5.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "volume_height"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (currentVolFraction <= 0.02f) {
                Icons.AutoMirrored.Rounded.VolumeMute
            } else {
                Icons.AutoMirrored.Rounded.VolumeDown
            },
            contentDescription = "Volumen mínimo",
            tint = Color.White.copy(alpha = 0.65f),
            modifier = Modifier
                .size(20.dp)
                .clickable { applyVolumeFraction(0f) }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        applyVolumeFraction(offset.x / widthPx.toFloat())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            isDragging = true
                            applyVolumeFraction(startOffset.x / widthPx.toFloat())
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            applyVolumeFraction(change.position.x / widthPx.toFloat())
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentVolFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.72f),
                                    Color.White
                                )
                            )
                        )
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = "Volumen máximo",
            tint = Color.White.copy(alpha = 0.65f),
            modifier = Modifier
                .size(20.dp)
                .clickable { applyVolumeFraction(1f) }
        )
    }
}

@Composable
private fun AppleMusicScrubber(
    fraction: Float,
    isScrubbing: Boolean,
    crossfadeZoneFraction: Float = 0f,
    accent: Color = LocalSonoraAccent.current,
    onScrubStart: (Float) -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val barHeight by animateDpAsState(
        targetValue = if (isScrubbing) 9.dp else 5.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scrubber_height"
    )
    var widthPx by remember { mutableIntStateOf(1) }
    var activeFraction by remember { mutableFloatStateOf(fraction) }

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val tapped = (offset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                    onScrubEnd(tapped)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { startOffset ->
                        val f = (startOffset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        activeFraction = f
                        onScrubStart(f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        activeFraction = f
                        onScrubChange(f)
                    },
                    onDragEnd = {
                        onScrubEnd(activeFraction)
                    },
                    onDragCancel = {
                        onScrubEnd(activeFraction)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val clamped = fraction.coerceIn(0f, 1f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
        ) {
            val h = size.height
            val radius = CornerRadius(h / 2f, h / 2f)
            // Base track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.24f),
                cornerRadius = radius
            )
            // Crossfade tail zone marker
            if (crossfadeZoneFraction > 0.005f) {
                val zoneW = size.width * crossfadeZoneFraction.coerceIn(0f, 0.3f)
                drawRoundRect(
                    color = accent.copy(alpha = 0.42f),
                    topLeft = Offset(size.width - zoneW, 0f),
                    size = Size(zoneW, h),
                    cornerRadius = radius
                )
            }
            // Filled playback progress
            val progressW = size.width * clamped
            if (progressW > 0.5f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isScrubbing) 0.94f else 0.78f),
                            Color.White
                        )
                    ),
                    size = Size(progressW, h),
                    cornerRadius = radius
                )
            }
            // Active scrubbing thumb
            if (isScrubbing) {
                drawCircle(
                    color = Color.White,
                    radius = h * 0.9f,
                    center = Offset(progressW.coerceIn(h, size.width - h), h / 2f)
                )
            }
        }
    }
}

@Composable
private fun LyricsPane(
    track: Track,
    playbackState: PlaybackUiState,
    lyricsOffsetMs: Long,
    onAdjustLyricsOffset: (Long) -> Unit,
    onResetLyricsOffset: () -> Unit,
    onReloadLyrics: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit
) {
    val context = LocalContext.current
    val trackLyrics = playbackState.lyrics
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var autoFollow by remember { mutableStateOf(true) }
    var showLyricsCardDialog by remember { mutableStateOf(false) }

    val shimmerTransition = rememberInfiniteTransition(label = "lyrics_glow")
    val shimmerPulse by shimmerTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Reverse
        ),
        label = "shimmer_pulse"
    )

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            autoFollow = false
        }
    }
    LaunchedEffect(autoFollow, track.videoId) {
        if (!autoFollow) {
            delay(4_200L)
            autoFollow = true
        }
    }

    val effectivePositionMs = (playbackState.positionMs + lyricsOffsetMs).coerceAtLeast(0L)
    val lines = trackLyrics?.lines.orEmpty()
    val activeIndex = remember(lines, effectivePositionMs) {
        if (lines.isEmpty()) -1
        else {
            var idx = 0
            for (i in lines.indices) {
                if (lines[i].timeMs <= effectivePositionMs + 180L) {
                    idx = i
                } else {
                    break
                }
            }
            idx
        }
    }

    val activeLineProgress = remember(lines, activeIndex, effectivePositionMs, playbackState.durationMs) {
        if (activeIndex !in lines.indices) 0f
        else {
            val startMs = lines[activeIndex].timeMs
            val nextMs = lines.getOrNull(activeIndex + 1)?.timeMs
                ?: playbackState.durationMs.takeIf { it > startMs }
                ?: (startMs + 4_500L)
            val span = (nextMs - startMs).coerceAtLeast(600L)
            ((effectivePositionMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(activeIndex, autoFollow, viewportHeightPx) {
        if (autoFollow && activeIndex >= 0 && viewportHeightPx > 0) {
            val centerOffset = -(viewportHeightPx * 0.28f).toInt()
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = centerOffset
            )
        }
    }

    val progressFraction = if (playbackState.durationMs > 0L) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SonoraArtwork(
                url = track.artworkUrl,
                contentDescription = track.title,
                modifier = Modifier.size(46.dp),
                cornerRadius = 10.dp
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (trackLyrics != null) {
                        val badgeLabel = trackLyrics.sourceLabel.ifBlank {
                            if (trackLyrics.isSynced) "Sincronizada" else "Auto"
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = badgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onReloadLyrics,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lyrics_reload_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Recargar letra",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            if (lines.isNotEmpty()) {
                IconButton(
                    onClick = {
                        val idx = activeIndex.coerceIn(0, lines.lastIndex)
                        val excerpt = lines.subList(idx, (idx + 3).coerceAtMost(lines.size))
                            .joinToString("\n") { "“${it.text}”" }
                        val shareText = "$excerpt\n\n— ${track.title} • ${track.artist} (Sonora)"
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(sendIntent, "Compartir letra"))
                        }
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lyrics_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Compartir verso",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lyrics_play_pause_button")
            ) {
                Icon(
                    imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Compact Lyric Sync Fine-Tuner (-0.5s / +0.5s) + Tarjeta de Letra PNG
        if (lines.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { showLyricsCardDialog = true },
                    shape = RoundedCornerShape(50),
                    color = LocalSonoraAccent.current.copy(alpha = 0.22f),
                    border = BorderStroke(0.6.dp, LocalSonoraAccent.current.copy(alpha = 0.56f)),
                    modifier = Modifier.testTag("lyrics_card_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Style,
                            contentDescription = "Crear tarjeta de letra",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Tarjeta",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.10f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "-0.5s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onAdjustLyricsOffset(-500L) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("lyrics_offset_minus")
                        )
                        Text(
                            text = if (lyricsOffsetMs == 0L) "Sincro 0s" else "%+.1fs".format(lyricsOffsetMs / 1000f),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (lyricsOffsetMs == 0L) Color.White.copy(alpha = 0.72f) else LocalSonoraAccent.current,
                            modifier = Modifier
                                .clickable { onResetLyricsOffset() }
                                .testTag("lyrics_offset_reset")
                        )
                        Text(
                            text = "+0.5s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onAdjustLyricsOffset(500L) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("lyrics_offset_plus")
                        )
                    }
                }
            }
        }

        if (showLyricsCardDialog && lines.isNotEmpty()) {
            LyricsShareCardDialog(
                track = track,
                lines = lines,
                initialLineIndex = activeIndex.coerceIn(0, lines.lastIndex),
                ambientPrimary = playbackState.ambientPrimary,
                onDismiss = { showLyricsCardDialog = false }
            )
        }

        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .height(2.5.dp)
                .clip(RoundedCornerShape(50)),
            color = Color.White.copy(alpha = 0.86f),
            trackColor = Color.White.copy(alpha = 0.16f)
        )

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                playbackState.isLoadingLyrics -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                lines.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No hay letras disponibles para esta canción.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 68.dp, bottom = 140.dp, start = 4.dp, end = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportHeightPx = it.height }
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.0f to Color.Transparent,
                                        0.10f to Color.Black,
                                        0.86f to Color.Black,
                                        1.0f to Color.Transparent
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                            .testTag("lyrics_list")
                    ) {
                        itemsIndexed(lines, key = { idx, line -> "${idx}_${line.timeMs}" }) { idx, line ->
                            val distance = abs(idx - activeIndex)
                            val isActive = distance == 0
                            val targetAlpha = when {
                                !autoFollow -> if (isActive) 1.0f else 0.72f
                                isActive -> 1.0f
                                distance == 1 -> 0.48f
                                distance == 2 -> 0.27f
                                else -> 0.15f
                            }
                            val targetScale = if (isActive) 1.0f else 0.94f
                            val targetBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && autoFollow) {
                                when (distance) {
                                    0 -> 0f
                                    1 -> 1.3f
                                    2 -> 2.6f
                                    else -> 4.0f
                                }
                            } else {
                                0f
                            }

                            val animatedAlpha by animateFloatAsState(
                                targetValue = targetAlpha,
                                animationSpec = tween(durationMillis = 360),
                                label = "lyric_alpha_$idx"
                            )
                            val animatedScale by animateFloatAsState(
                                targetValue = targetScale,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "lyric_scale_$idx"
                            )
                            val animatedBlur by animateFloatAsState(
                                targetValue = targetBlur,
                                animationSpec = tween(durationMillis = 360),
                                label = "lyric_blur_$idx"
                            )

                            val baseStyle = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = if (isActive) 28.sp else 25.sp,
                                lineHeight = if (isActive) 36.sp else 33.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp
                            )

                            val sweepCenter = (activeLineProgress * 1.12f).coerceIn(0.05f, 0.98f)
                            val activeBrush = Brush.horizontalGradient(
                                0.0f to Color.White,
                                (sweepCenter - 0.10f).coerceAtLeast(0.0f) to Color.White,
                                sweepCenter to Color(0xFFFFFFFF),
                                (sweepCenter + 0.14f).coerceAtMost(1.0f) to Color.White.copy(alpha = 0.74f),
                                1.0f to Color.White.copy(alpha = 0.62f)
                            )

                            val lineStyle = if (isActive && line.words.isNotEmpty()) {
                                baseStyle.copy(
                                    color = Color.White,
                                    shadow = Shadow(
                                        color = Color.White.copy(alpha = 0.56f * shimmerPulse),
                                        offset = Offset.Zero,
                                        blurRadius = 22f
                                    )
                                )
                            } else if (isActive) {
                                baseStyle.copy(
                                    brush = activeBrush,
                                    shadow = Shadow(
                                        color = Color.White.copy(alpha = 0.56f * shimmerPulse),
                                        offset = Offset.Zero,
                                        blurRadius = 22f
                                    )
                                )
                            } else {
                                baseStyle.copy(color = Color.White)
                            }

                            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && animatedBlur > 0.1f) {
                                Modifier.blur(animatedBlur.dp)
                            } else {
                                Modifier
                            }

                            val commonModifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onSeekTo((line.timeMs - lyricsOffsetMs).coerceAtLeast(0L))
                                    autoFollow = true
                                }
                                .padding(vertical = 4.dp, horizontal = 6.dp)
                                .graphicsLayer {
                                    alpha = animatedAlpha
                                    scaleX = animatedScale
                                    scaleY = animatedScale
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                                .then(blurModifier)

                            if (isActive && line.words.isNotEmpty()) {
                                val wordAnnotated = buildAnnotatedString {
                                    line.words.forEach { w ->
                                        val sung = w.startMs <= effectivePositionMs + 80L
                                        pushStyle(
                                            SpanStyle(
                                                color = if (sung) Color.White else Color.White.copy(alpha = 0.50f)
                                            )
                                        )
                                        append(w.text)
                                        pop()
                                    }
                                }
                                Text(
                                    text = wordAnnotated,
                                    style = lineStyle,
                                    modifier = commonModifier
                                )
                            } else {
                                Text(
                                    text = line.text,
                                    style = lineStyle,
                                    modifier = commonModifier
                                )
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !autoFollow,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                    ) {
                        Surface(
                            onClick = {
                                autoFollow = true
                                if (activeIndex >= 0 && viewportHeightPx > 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(
                                            index = activeIndex,
                                            scrollOffset = -(viewportHeightPx * 0.28f).toInt()
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.22f),
                            border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.36f)),
                            modifier = Modifier.testTag("center_lyrics_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CenterFocusStrong,
                                    contentDescription = "Centrar letra",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Centrar letra",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePane(
    playbackState: PlaybackUiState,
    onSkipToIndex: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onOpenItemOptions: (Track, Int) -> Unit,
    onRetuneQueueMood: (QueueMoodFilter) -> Unit,
    onOpenSongMixer: () -> Unit = {}
) {
    val accent = LocalSonoraAccent.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "A continuación",
                style = MaterialTheme.typography.headlineMedium,
                color = SonoraTextPrimary
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (playbackState.isRetuningQueue) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                }
                Surface(
                    onClick = onOpenSongMixer,
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF17171C),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
                    modifier = Modifier.testTag("queue_open_mixer_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Mezclar canciones",
                            tint = accent,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Mezclar",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = SonoraTextPrimary
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Smart Queue Mood Tuning ("Afina tu cola")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueMoodFilter.entries.forEach { mood ->
                val selected = playbackState.activeQueueMood == mood
                FilterChip(
                    selected = selected,
                    onClick = { onRetuneQueueMood(mood) },
                    label = {
                        Text(
                            text = mood.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF1A1A20),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF111115),
                        labelColor = SonoraTextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = Color.White.copy(alpha = 0.14f),
                        selectedBorderColor = accent.copy(alpha = 0.85f)
                    ),
                    modifier = Modifier.testTag("queue_mood_${mood.name.lowercase()}")
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(playbackState.queue) { idx, item ->
                val isCurrent = idx == playbackState.currentIndex
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSkipToIndex(idx) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SonoraArtwork(
                            url = item.artworkUrl,
                            contentDescription = item.title,
                            modifier = Modifier.size(44.dp),
                            cornerRadius = 9.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isCurrent) accent else SonoraTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (!isCurrent) {
                            if (idx > 0) {
                                IconButton(
                                    onClick = { onMoveItem(idx, idx - 1) },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .testTag("queue_move_up_$idx")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = "Subir en la cola",
                                        tint = SonoraTextSecondary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            if (idx < playbackState.queue.lastIndex) {
                                IconButton(
                                    onClick = { onMoveItem(idx, idx + 1) },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .testTag("queue_move_down_$idx")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Bajar en la cola",
                                        tint = SonoraTextSecondary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onRemoveItem(idx) },
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag("queue_remove_$idx")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Quitar de la cola",
                                    tint = SonoraTextTertiary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            IconButton(
                                onClick = { onOpenItemOptions(item, idx) },
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag("queue_more_$idx")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "Más opciones",
                                    tint = SonoraTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AnimatedEqualizer(
                                    isPlaying = playbackState.isPlaying,
                                    color = accent,
                                    modifier = Modifier.height(14.dp)
                                )
                                if (item.durationText.isNotBlank()) {
                                    Text(
                                        text = item.durationText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent
                                    )
                                }
                                IconButton(
                                    onClick = { onOpenItemOptions(item, idx) },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .testTag("queue_more_$idx")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "Más opciones",
                                        tint = SonoraTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (idx < playbackState.queue.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 8.dp),
                            thickness = 0.5.dp,
                            color = Color.White.copy(alpha = 0.08f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickEqualizerSheet(
    settings: SonoraSettings,
    activeAudioRoute: AudioOutputRoute,
    autoMixStatus: String?,
    sleepTimerRemainingSec: Int?,
    sleepAfterCurrentTrack: Boolean,
    onDismiss: () -> Unit,
    onSetEqEnabled: (Boolean) -> Unit,
    onSetEqPreset: (EqPreset) -> Unit,
    onSetBassBoost: (Int) -> Unit,
    onSetNormalizeVolume: (Boolean) -> Unit,
    onSetDolbyAtmos: (Boolean) -> Unit,
    onSetSpatialAudio: (Boolean) -> Unit,
    onSetAutoMix: (Boolean) -> Unit,
    onSetAutoMixOverlapSeconds: (Int) -> Unit,
    onSetCrossfadeSeconds: (Int) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetPlaybackPitch: (Float) -> Unit,
    onResetSpeedAndPitch: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    onSetSleepAfterCurrentTrack: (Boolean) -> Unit,
    onOpenStatsForNerds: () -> Unit
) {
    val accent = LocalSonoraAccent.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF014141E),
        contentColor = SonoraTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Dolby Atmos
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSetDolbyAtmos(!settings.dolbyAtmosEnabled) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.SurroundSound, contentDescription = null, tint = accent)
                    Column {
                        Text("Dolby Atmos", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                        Text("Sonido envolvente y escena multidimensional", style = MaterialTheme.typography.bodySmall, color = SonoraTextSecondary)
                    }
                }
                Switch(
                    checked = settings.dolbyAtmosEnabled,
                    onCheckedChange = onSetDolbyAtmos,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent),
                    modifier = Modifier.testTag("sheet_dolby_atmos_switch")
                )
            }

            // Spatial Audio
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSetSpatialAudio(!settings.spatialAudioEnabled) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.SpatialAudio, contentDescription = null, tint = accent)
                    Column {
                        Text("Audio Espacial", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                        Text("Amplitud acústica 360°", style = MaterialTheme.typography.bodySmall, color = SonoraTextSecondary)
                    }
                }
                Switch(
                    checked = settings.spatialAudioEnabled,
                    onCheckedChange = onSetSpatialAudio,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent),
                    modifier = Modifier.testTag("sheet_spatial_audio_switch")
                )
            }

            // Crossfade (Fundido Spotify / Apple Music)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = accent)
                        Column {
                            Text("Fundido (Crossfade)", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                            Text(
                                text = when {
                                    settings.crossfadeSeconds < 0 -> "Automático inteligente entre pistas"
                                    settings.crossfadeSeconds == 0 -> "Desactivado (corte directo)"
                                    else -> "Mezcla suave de ${settings.crossfadeSeconds}s al cambiar"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (settings.crossfadeSeconds != 0) accent else SonoraTextSecondary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = settings.crossfadeSeconds == 0,
                        onClick = { onSetCrossfadeSeconds(0) },
                        label = { Text("Desactivado") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        ),
                        modifier = Modifier.testTag("sheet_crossfade_off_chip")
                    )
                    FilterChip(
                        selected = settings.crossfadeSeconds < 0,
                        onClick = { onSetCrossfadeSeconds(-1) },
                        label = { Text("Auto") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        ),
                        modifier = Modifier.testTag("sheet_crossfade_auto_chip")
                    )
                }
                Slider(
                    value = when {
                        settings.crossfadeSeconds > 0 -> settings.crossfadeSeconds.toFloat()
                        settings.crossfadeSeconds < 0 -> 6f
                        else -> 0f
                    },
                    onValueChange = { value ->
                        val sec = value.roundToInt().coerceIn(0, 12)
                        onSetCrossfadeSeconds(sec)
                    },
                    valueRange = 0f..12f,
                    steps = 11,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.testTag("sheet_crossfade_slider")
                )
            }

            // AutoMix
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSetAutoMix(!settings.autoMixEnabled) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accent)
                    Column {
                        Text("Mezcla Automática (AutoMix)", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                        Text(
                            text = autoMixStatus ?: "Calcula y mezcla cada transición entre canciones",
                            style = MaterialTheme.typography.bodySmall,
                            color = SonoraTextSecondary
                        )
                    }
                }
                Switch(
                    checked = settings.autoMixEnabled,
                    onCheckedChange = onSetAutoMix,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent),
                    modifier = Modifier.testTag("sheet_automix_switch")
                )
            }

            if (settings.autoMixEnabled) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Entrada de la siguiente canción",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = if (settings.autoMixOverlapSeconds == 0) {
                                    "Automático (IA)"
                                } else {
                                    "${settings.autoMixOverlapSeconds}s antes del final"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = accent
                            )
                        }
                        FilterChip(
                            selected = settings.autoMixOverlapSeconds == 0,
                            onClick = { onSetAutoMixOverlapSeconds(0) },
                            label = { Text("Auto") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.28f),
                                selectedLabelColor = SonoraTextPrimary
                            ),
                            modifier = Modifier.testTag("sheet_automix_auto_chip")
                        )
                    }
                    Slider(
                        value = if (settings.autoMixOverlapSeconds == 0) 6f else settings.autoMixOverlapSeconds.toFloat(),
                        onValueChange = { onSetAutoMixOverlapSeconds(it.roundToInt().coerceIn(2, 12)) },
                        valueRange = 2f..12f,
                        steps = 9,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                        modifier = Modifier.testTag("sheet_automix_overlap_slider")
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Playback Speed & Pitch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = accent)
                    Text("Velocidad y tono", style = MaterialTheme.typography.titleMedium, color = SonoraTextPrimary)
                }
                if (abs(settings.playbackSpeed - 1.0f) > 0.02f || abs(settings.playbackPitch - 1.0f) > 0.02f) {
                    TextButton(onClick = onResetSpeedAndPitch) {
                        Text("Restablecer", color = accent)
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { speedPreset ->
                    FilterChip(
                        selected = abs(settings.playbackSpeed - speedPreset) < 0.03f,
                        onClick = { onSetPlaybackSpeed(speedPreset) },
                        label = { Text("${"%.2f".format(speedPreset)}×") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        )
                    )
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Velocidad", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                    Text("${"%.2f".format(settings.playbackSpeed)}×", style = MaterialTheme.typography.labelLarge, color = accent)
                }
                Slider(
                    value = settings.playbackSpeed,
                    onValueChange = { onSetPlaybackSpeed((it * 20f).roundToInt() / 20f) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.testTag("sheet_speed_slider")
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tono (Pitch)", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                    Text("${"%.2f".format(settings.playbackPitch)}×", style = MaterialTheme.typography.labelLarge, color = accent)
                }
                Slider(
                    value = settings.playbackPitch,
                    onValueChange = { onSetPlaybackPitch((it * 20f).roundToInt() / 20f) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.testTag("sheet_pitch_slider")
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Quick Sleep Timer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = accent)
                    Text("Temporizador rápido", style = MaterialTheme.typography.titleMedium, color = SonoraTextPrimary)
                }
                if (sleepTimerRemainingSec != null) {
                    Text(
                        text = "%d:%02d".format(sleepTimerRemainingSec / 60, sleepTimerRemainingSec % 60),
                        style = MaterialTheme.typography.labelLarge,
                        color = accent
                    )
                } else if (sleepAfterCurrentTrack) {
                    Text("Al finalizar pista", style = MaterialTheme.typography.labelMedium, color = accent)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = sleepTimerRemainingSec == null && !sleepAfterCurrentTrack,
                    onClick = {
                        onSetSleepTimer(null)
                        onSetSleepAfterCurrentTrack(false)
                    },
                    label = { Text("Off") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.28f),
                        selectedLabelColor = SonoraTextPrimary
                    )
                )
                listOf(15, 30, 60).forEach { mins ->
                    FilterChip(
                        selected = false,
                        onClick = { onSetSleepTimer(mins) },
                        label = { Text("${mins}m") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        )
                    )
                }
                FilterChip(
                    selected = sleepAfterCurrentTrack,
                    onClick = { onSetSleepAfterCurrentTrack(!sleepAfterCurrentTrack) },
                    label = { Text("Al terminar canción") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.28f),
                        selectedLabelColor = SonoraTextPrimary
                    ),
                    modifier = Modifier.testTag("sheet_sleep_after_track_chip")
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = accent)
                    Column {
                        Text("Ecualizador Pro", style = MaterialTheme.typography.titleLarge, color = SonoraTextPrimary)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Headphones,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Perfil guardado para ${activeAudioRoute.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SonoraTextSecondary
                            )
                        }
                    }
                }
                Switch(
                    checked = settings.eqEnabled,
                    onCheckedChange = onSetEqEnabled,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EqPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.eqPreset == preset,
                        enabled = settings.eqEnabled,
                        onClick = { onSetEqPreset(preset) },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        )
                    )
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Refuerzo de graves", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                    Text("${settings.bassBoostStrength / 10}%", style = MaterialTheme.typography.labelLarge, color = accent)
                }
                Slider(
                    value = settings.bassBoostStrength.toFloat(),
                    onValueChange = { onSetBassBoost(it.toInt()) },
                    enabled = settings.eqEnabled,
                    valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSetNormalizeVolume(!settings.normalizeVolume) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Normalizar volumen", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                    Text("Nivela la potencia entre pistas", style = MaterialTheme.typography.bodyMedium, color = SonoraTextSecondary)
                }
                Switch(
                    checked = settings.normalizeVolume,
                    onCheckedChange = onSetNormalizeVolume,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                )
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sheet_stats_for_nerds_row"),
                shape = RoundedCornerShape(16.dp),
                onClick = onOpenStatsForNerds
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Estadísticas para geeks",
                            style = MaterialTheme.typography.titleMedium,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = "Códec, bitrate, muestreo, sesión y búfer en vivo",
                            style = MaterialTheme.typography.bodySmall,
                            color = SonoraTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val accent = LocalSonoraAccent.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) Color(0xFF1D1D24) else Color(0xFF121216).copy(alpha = 0.85f),
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.14f)
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) accent else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.78f)
            )
        }
    }
}

@Composable
private fun LiveAudioVisualizerStrip(
    accent: Color,
    modifier: Modifier = Modifier
) {
    val fftBars by SonoraPlaybackBridge.visualizerBars.collectAsState()
    val hasLiveFft = remember(fftBars) { fftBars.any { it > 0.02f } }
    val anim = rememberInfiniteTransition(label = "live_visualizer_fallback")
    val phase by anim.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "vis_phase"
    )
    Canvas(
        modifier = modifier.height(22.dp)
    ) {
        val barCount = 20
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        for (i in 0 until barCount) {
            val energy = if (hasLiveFft) {
                val srcIdx = (i * fftBars.size) / barCount
                fftBars.getOrElse(srcIdx) { 0.1f }.coerceIn(0.12f, 1f)
            } else {
                val wave = 0.5f + 0.5f * sin(phase * 1.4f + i * 0.52f)
                (0.20f + 0.65f * wave).coerceIn(0.14f, 0.92f)
            }
            val h = (size.height * energy).coerceAtLeast(3.dp.toPx())
            val x = i * (barWidth + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.85f), accent.copy(alpha = 0.75f))
                ),
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun DrivingModeOverlay(
    track: Track,
    playbackState: PlaybackUiState,
    djLiveActive: Boolean,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenVoiceDj: () -> Unit
) {
    BackHandler { onDismiss() }
    val accent = LocalSonoraAccent.current
    val duration = playbackState.durationMs.coerceAtLeast(1L)
    val fraction = (playbackState.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF5090A10),
                        playbackState.ambientPrimary.copy(alpha = 0.30f),
                        Color(0xFA06070B)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 18.dp)
            .testTag("driving_mode_overlay")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accent.copy(alpha = 0.22f),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DirectionsCar,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Modo Conducción",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }

                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("driving_mode_close_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Salir", tint = Color.White)
                        Text("Salir", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SonoraArtwork(
                    url = track.artworkUrl,
                    contentDescription = track.title,
                    modifier = Modifier.size(152.dp),
                    cornerRadius = 24.dp
                )
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = accent,
                    trackColor = Color.White.copy(alpha = 0.22f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTimeMs(playbackState.positionMs),
                        style = TabularTimerTextStyle,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = formatTimeMs(playbackState.durationMs),
                        style = TabularTimerTextStyle,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onSkipPrevious,
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                        modifier = Modifier
                            .size(72.dp)
                            .testTag("driving_prev_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Anterior",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onTogglePlayPause,
                        shape = CircleShape,
                        color = accent,
                        modifier = Modifier
                            .size(92.dp)
                            .testTag("driving_play_pause_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(50.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onSkipNext,
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                        modifier = Modifier
                            .size(72.dp)
                            .testTag("driving_next_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Siguiente",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                Surface(
                    onClick = onOpenVoiceDj,
                    shape = RoundedCornerShape(50),
                    color = if (djLiveActive) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (djLiveActive) "Hablar con DJ Sony" else "Pedir mix a DJ Sony",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
