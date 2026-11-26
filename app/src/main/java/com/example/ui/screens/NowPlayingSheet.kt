package com.example.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.example.model.EqPreset
import com.example.model.PlaybackOrigin
import com.example.model.QueueMoodFilter
import com.example.model.SonoraSettings
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.ui.components.NowPlayingMenuActions
import com.example.ui.player.NowPlayingScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    onOpenPlayerOptions: ((Track, NowPlayingMenuActions) -> Unit)? = null,
    onOpenQueueItemOptions: (Track, Int) -> Unit = { track, _ -> onOpenOptions(track) },
    positionMsFlow: StateFlow<Long>? = null,
    onClearQueue: () -> Unit = {},
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
    val effectivePositionFlow = if (positionMsFlow != null) {
        positionMsFlow
    } else {
        val fallbackFlow = remember { MutableStateFlow(playbackState.positionMs) }
        LaunchedEffect(playbackState.positionMs) {
            fallbackFlow.value = playbackState.positionMs
        }
        fallbackFlow
    }

    NowPlayingScreen(
        playbackState = playbackState,
        positionMsFlow = effectivePositionFlow,
        settings = settings,
        djLiveState = djLiveState,
        isLiked = isLiked,
        onCollapse = onCollapse,
        onTogglePlayPause = onTogglePlayPause,
        onSkipNext = onSkipNext,
        onSkipPrevious = onSkipPrevious,
        onSeekTo = onSeekTo,
        onToggleShuffle = onToggleShuffle,
        onCycleRepeat = onCycleRepeat,
        onToggleLike = onToggleLike,
        onAddToPlaylist = onAddToPlaylist,
        onSkipToQueueIndex = onSkipToQueueIndex,
        onMoveQueueItem = onMoveQueueItem,
        onRemoveQueueItem = onRemoveQueueItem,
        onClearQueue = onClearQueue,
        onToggleAnimatedArtwork = onToggleAnimatedArtwork,
        onStartDjLive = onStartDjLive,
        onOpenVoiceDj = onOpenVoiceDj,
        onSwitchDjVibe = onSwitchDjVibe,
        onStopDjLive = onStopDjLive,
        onAdjustLyricsOffset = onAdjustLyricsOffset,
        onResetLyricsOffset = onResetLyricsOffset,
        onSetEqEnabled = onSetEqEnabled,
        onSetEqPreset = onSetEqPreset,
        onSetBassBoost = onSetBassBoost,
        onSetNormalizeVolume = onSetNormalizeVolume,
        onToggleAutoMix = onToggleAutoMix,
        onSetAutoMixOverlapSeconds = onSetAutoMixOverlapSeconds,
        onSetDolbyAtmos = onSetDolbyAtmos,
        onSetSpatialAudio = onSetSpatialAudio,
        onRetry = onRetry,
        onOpenOptions = onOpenOptions,
        onOpenPlayerOptions = onOpenPlayerOptions,
        onOpenQueueItemOptions = onOpenQueueItemOptions,
        onNavigateToOrigin = onNavigateToOrigin,
        onOpenArtist = onOpenArtist,
        onOpenAlbum = onOpenAlbum,
        sleepTimerRemainingSec = sleepTimerRemainingSec,
        sleepAfterCurrentTrack = sleepAfterCurrentTrack,
        onSetSleepTimer = onSetSleepTimer,
        onSetSleepAfterCurrentTrack = onSetSleepAfterCurrentTrack,
        onSetPlaybackSpeed = onSetPlaybackSpeed,
        onSetPlaybackPitch = onSetPlaybackPitch,
        onResetSpeedAndPitch = onResetSpeedAndPitch,
        onReloadLyrics = onReloadLyrics,
        onSetCrossfadeSeconds = onSetCrossfadeSeconds,
        onRetuneQueueMood = onRetuneQueueMood,
        onOpenSongMixer = onOpenSongMixer,
        initialOpenQueue = initialOpenQueue,
        openQueueTrigger = openQueueTrigger
    )
}
