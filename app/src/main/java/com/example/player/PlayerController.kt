package com.example.player

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.local.LocalMusicRepository
import com.example.data.local.SonoraPreferences
import com.example.data.network.InnerTubeXResolver
import com.example.data.network.IntegrationsManager
import com.example.data.network.PlaybackDiagnostics
import com.example.data.network.StreamResolver
import com.example.data.repository.MusicRepository
import com.example.data.repository.OfflineDownloadManager
import com.example.model.AudioOutputRoute
import com.example.model.CustomMixFragment
import com.example.model.PersistedQueueState
import com.example.model.PlaybackOrigin
import com.example.model.PlaylistTransitionSpec
import com.example.model.QueueMoodFilter
import com.example.model.RepeatMode
import com.example.model.ResolvedStream
import com.example.model.ResolvedTrackMetadata
import com.example.model.SonoraSettings
import com.example.model.StatsForNerdsData
import com.example.model.Track
import com.example.model.TrackLyrics
import com.example.model.TransitionCurveType
import com.example.ui.theme.SonoraCrimson
import com.example.ui.theme.SonoraPlum
import com.example.widget.SonoraMediaWidgetProvider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

data class PlaybackUiState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val codecLabel: String = "Sin anuncios",
    val lyrics: TrackLyrics? = null,
    val isLoadingLyrics: Boolean = false,
    val errorMessage: String? = null,
    val errorDetail: String? = null,
    val ambientPrimary: Color = SonoraCrimson,
    val ambientSecondary: Color = SonoraPlum,
    val autoMixEnabled: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val autoMixStatus: String? = null,
    val activeQueueMood: QueueMoodFilter = QueueMoodFilter.BALANCED,
    val isRetuningQueue: Boolean = false,
    val activeAudioRoute: AudioOutputRoute = AudioOutputRoute.SPEAKER,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val statsForNerds: StatsForNerdsData = StatsForNerdsData(),
    val isResyncingController: Boolean = false,
    val streamNotice: String? = null,
    val playbackOrigin: PlaybackOrigin? = null,
    val radioSeedTitle: String? = null
)

class PlayerController(
    private val context: Context,
    private val repository: MusicRepository,
    private val streamResolver: StreamResolver,
    private val preferences: SonoraPreferences,
    private val offlineDownloadManager: OfflineDownloadManager? = null,
    private val localMusicRepository: LocalMusicRepository? = null,
    private val integrationsManager: IntegrationsManager? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var customControllerFuture: ListenableFuture<out Player>? = null
    private var mediaController: Player? = null
    private var controllerSyncTimeoutJob: Job? = null

    @Volatile
    internal var controllerFutureFactoryForTest: (() -> ListenableFuture<out Player>)? = null
    private var progressJob: Job? = null
    private var metadataJob: Job? = null
    private var paletteJob: Job? = null
    private var playJob: Job? = null
    private var prefetchJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var tailExtendJob: Job? = null
    private var pendingAction: (() -> Unit)? = null
    private var autoRetryVideoId: String? = null
    private var autoRetryCount: Int = 0
    private var activeResolvedSourceVideoId: String? = null
    private val failedAlternatesByTrack = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private var lastRecordedPlayKey: String? = null
    private var lastTailExtendedKey: String? = null
    private var preCuedAutoMixKey: String? = null
    private var triggeredAutoMixKey: String? = null
    private var pendingSmartContextSongs: Pair<String, List<Track>>? = null

    private var activeListenedMs: Long = 0L
    private var lastProgressWallMs: Long = 0L
    private var trackStartedAtSec: Long = 0L
    private var nowPlayingSentKey: String? = null
    private var scrobbledKey: String? = null
    private var lastQueuePersistWallMs: Long = 0L
    private var pendingRestoredPositionMs: Long? = null
    private var activeSourcePlaylistId: Long? = null
    private var activePlaylistTransitions: Map<Pair<String, String>, PlaylistTransitionSpec> = emptyMap()
    private var customFragmentPlans: Map<Int, PlaylistTransitionSpec> = emptyMap()
    private var previewJob: Job? = null

    @Volatile
    private var latestSettings: SonoraSettings = SonoraSettings()

    @Volatile
    private var autoplayRadioEnabled: Boolean = true

    @Volatile
    private var autoMixEnabled: Boolean = false

    @Volatile
    private var autoMixOverlapSeconds: Int = 0

    @Volatile
    private var crossfadeSeconds: Int = 0

    private val queueJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val queueStateFile by lazy { File(context.filesDir, "queue_state.json") }

    private val imageLoader by lazy { ImageLoader(context) }

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMsFlow: StateFlow<Long> = _positionMs.asStateFlow()

    private val listeningRecorder = ListeningRecorder(
        onCommitPlay = { track, playedAtMs, listenedMs ->
            repository.recordListeningSession(track, playedAtMs, listenedMs)
        },
        onUpdateListenedMs = { rowId, listenedMs ->
            repository.updateListeningSessionDuration(rowId, listenedMs)
        }
    )

    private val _sleepTimerRemainingSec = MutableStateFlow<Int?>(null)
    val sleepTimerRemainingSec: StateFlow<Int?> = _sleepTimerRemainingSec.asStateFlow()

    private val _sleepAfterCurrentTrack = MutableStateFlow(false)
    val sleepAfterCurrentTrack: StateFlow<Boolean> = _sleepAfterCurrentTrack.asStateFlow()

    init {
        scope.launch {
            preferences.settingsFlow.collect { settings ->
                latestSettings = settings
                streamResolver.setNetworkAudioQualities(
                    wifi = settings.wifiAudioQuality,
                    mobile = settings.mobileAudioQuality
                )
                autoplayRadioEnabled = settings.autoplayRadio
                autoMixEnabled = settings.autoMixEnabled
                autoMixOverlapSeconds = settings.autoMixOverlapSeconds
                crossfadeSeconds = settings.crossfadeSeconds
                _state.update { state ->
                    state.copy(
                        autoMixEnabled = settings.autoMixEnabled,
                        crossfadeSeconds = settings.crossfadeSeconds,
                        playbackSpeed = settings.playbackSpeed,
                        playbackPitch = settings.playbackPitch,
                        autoMixStatus = computeNextAutoMixStatus(
                            enabled = settings.autoMixEnabled || settings.crossfadeSeconds != 0 || activePlaylistTransitions.isNotEmpty() || customFragmentPlans.isNotEmpty(),
                            currentTrack = state.currentTrack,
                            queue = state.queue,
                            currentIndex = state.currentIndex,
                            durationMs = state.durationMs
                        )
                    )
                }
                integrationsManager?.updateDiscordPresence(
                    settings = settings,
                    track = _state.value.currentTrack,
                    isPlaying = _state.value.isPlaying
                )
            }
        }
        scope.launch {
            SonoraPlaybackBridge.activeAudioRoute.collect { route ->
                _state.update { it.copy(activeAudioRoute = route) }
            }
        }
        scope.launch {
            SonoraPlaybackBridge.autoMixStatus.collect { liveStatus ->
                if (liveStatus != null) {
                    _state.update { it.copy(autoMixStatus = liveStatus) }
                } else {
                    val s = _state.value
                    _state.update {
                        it.copy(
                            autoMixStatus = computeNextAutoMixStatus(
                                enabled = autoMixEnabled || crossfadeSeconds != 0 || activePlaylistTransitions.isNotEmpty() || customFragmentPlans.isNotEmpty(),
                                currentTrack = s.currentTrack,
                                queue = s.queue,
                                currentIndex = s.currentIndex,
                                durationMs = s.durationMs
                            )
                        )
                    }
                }
            }
        }
        scope.launch {
            SonoraPlaybackBridge.liveTelemetry.collect { telemetry ->
                val current = _state.value.currentTrack ?: return@collect
                val stats = buildStatsForNerds(current, telemetry)
                _state.update { it.copy(statsForNerds = stats) }
            }
        }
        restorePersistedQueueOnStartup()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            listeningRecorder.onPlayingChanged(isPlaying)
            lastProgressWallMs = if (isPlaying) System.currentTimeMillis() else 0L
            val active = _state.value.currentTrack
            if (active != null) {
                SonoraMediaWidgetProvider.updateWidgets(
                    context = context,
                    title = active.title,
                    artist = active.artist,
                    artworkUrl = active.artworkUrl,
                    isPlaying = isPlaying
                )
            }
            integrationsManager?.updateDiscordPresence(latestSettings, active, isPlaying)
            if (isPlaying && active != null) {
                val key = "${_state.value.currentIndex}:${active.videoId}"
                if (nowPlayingSentKey != key) {
                    nowPlayingSentKey = key
                    scope.launch { integrationsManager?.dispatchNowPlaying(latestSettings, active) }
                }
            }
            if (isPlaying) {
                startProgressLoop()
            } else {
                stopProgressLoop()
                // Capture exact current controller position immediately on pause so closing app never loses it
                val livePos = mediaController?.takeIf { it.mediaItemCount > 0 }?.currentPosition?.coerceAtLeast(0L)
                if (livePos != null) {
                    _positionMs.value = livePos
                    _state.update { it.copy(positionMs = livePos) }
                }
                scope.launch { listeningRecorder.flush() }
                persistQueueStateSync()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            val controller = mediaController
            val duration = controller?.duration?.takeIf { it > 0L } ?: _state.value.currentTrack?.durationMs ?: 0L
            if (playbackState == Player.STATE_READY) {
                autoRetryVideoId = null
                autoRetryCount = 0
                bufferingWatchdogJob?.cancel()
                val snapshot = _state.value
                val activeTrack = snapshot.currentTrack
                val activeIdx = snapshot.currentIndex
                if (activeTrack != null) {
                    val key = "$activeIdx:${activeTrack.videoId}"
                    if (snapshot.isPlaying && nowPlayingSentKey != key) {
                        nowPlayingSentKey = key
                        scope.launch { integrationsManager?.dispatchNowPlaying(latestSettings, activeTrack) }
                    }
                    if (snapshot.queue.size > activeIdx + 1) {
                        prefetchNextTrack(snapshot.queue, activeIdx)
                    }
                }
            } else if (buffering) {
                startBufferingWatchdog()
            } else {
                bufferingWatchdogJob?.cancel()
            }
            if (playbackState == Player.STATE_ENDED) {
                if (_sleepAfterCurrentTrack.value) {
                    _sleepAfterCurrentTrack.value = false
                    controller?.pause()
                    _state.update { it.copy(isPlaying = false, isBuffering = false) }
                    persistQueueStateAsync()
                    return
                }
                val snapshot = _state.value
                if (snapshot.repeatMode == RepeatMode.OFF &&
                    autoplayRadioEnabled &&
                    snapshot.queue.isNotEmpty() &&
                    snapshot.currentIndex >= snapshot.queue.lastIndex
                ) {
                    extendQueueAtTail(advanceToNextAfterAppend = true)
                }
            }
            _state.update {
                it.copy(
                    isBuffering = buffering,
                    durationMs = duration,
                    errorMessage = if (playbackState == Player.STATE_READY) null else it.errorMessage
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = mediaController ?: return
            val controllerIndex = controller.currentMediaItemIndex
            val mediaId = mediaItem?.mediaId?.takeIf { it.isNotBlank() }
                ?: controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                ?: if (controllerIndex in 0 until controller.mediaItemCount) {
                    controller.getMediaItemAt(controllerIndex).mediaId.takeIf { it.isNotBlank() }
                } else null

            var queue = _state.value.queue
            var resolvedIndex = if (mediaId != null) {
                resolveTrackIndexInQueue(queue, mediaId, controllerIndex)
            } else -1

            if (resolvedIndex < 0 && (mediaId != null || controller.mediaItemCount > 0)) {
                val (reconstructed, reconstructedIdx) = reconstructQueueFromControllerOrPersisted(
                    controller = controller,
                    targetMediaId = mediaId,
                    controllerIndex = controllerIndex
                )
                if (reconstructed.isNotEmpty()) {
                    queue = reconstructed
                    resolvedIndex = reconstructedIdx
                    streamResolver.registerTracksMetadata(reconstructed)
                    _state.update { it.copy(queue = reconstructed) }
                }
            }

            val track = queue.getOrNull(resolvedIndex)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _sleepAfterCurrentTrack.value) {
                _sleepAfterCurrentTrack.value = false
                controller.pause()
                if (track != null) {
                    onActiveTrackChanged(track, resolvedIndex)
                }
                _state.update { it.copy(isPlaying = false) }
                persistQueueStateAsync()
                return
            }
            if (track != null) {
                val alreadyActive = track.videoId == _state.value.currentTrack?.videoId &&
                    resolvedIndex == _state.value.currentIndex &&
                    metadataJob != null
                if (!alreadyActive) {
                    onActiveTrackChanged(track, resolvedIndex)
                }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (playJob?.isActive == true) return
            val controller = mediaController ?: return
            val controllerMediaId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
            if (controllerMediaId != _state.value.currentTrack?.videoId) {
                syncFromController(controller)
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (playJob?.isActive == true) return
            val controller = mediaController ?: return
            val controllerMediaId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
            if (controllerMediaId != _state.value.currentTrack?.videoId) {
                syncFromController(controller)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (playJob?.isActive == true) return
            val controller = mediaController ?: return
            val controllerMediaId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
            if (controllerMediaId != _state.value.currentTrack?.videoId) {
                syncFromController(controller)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val mapped = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            }
            _state.update { it.copy(repeatMode = mapped) }
        }

        override fun onPlayerError(error: PlaybackException) {
            bufferingWatchdogJob?.cancel()
            val sanitizedErrMsg = PlaybackDiagnostics.sanitize(error.message.orEmpty())
            Log.e(
                "Sonora",
                "ExoPlayer error code=${error.errorCode} name=${error.errorCodeName}: $sanitizedErrMsg"
            )
            var httpStatus: Int? = null
            var cause: Throwable? = error.cause
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) {
                    httpStatus = cause.responseCode
                    break
                }
                cause = cause.cause
            }

            val failedTrack = _state.value.currentTrack
            val currentUri = mediaController?.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
            if (failedTrack != null) {
                PlaybackDiagnostics.updateTrackInfo(failedTrack.videoId, failedTrack.title, failedTrack.artist)
                val httpPart = httpStatus?.let { " (HTTP $it)" }.orEmpty()
                PlaybackDiagnostics.recordClientResult(
                    failedTrack.videoId,
                    "ExoPlayer fallo: ${error.errorCodeName}$httpPart — $sanitizedErrMsg"
                )
                val altVideoId = activeResolvedSourceVideoId?.takeIf { it != failedTrack.videoId }
                activeResolvedSourceVideoId = null

                if (currentUri.startsWith("file://", ignoreCase = true)) {
                    scope.launch {
                        offlineDownloadManager?.deleteDownload(failedTrack.videoId)
                    }
                } else {
                    val refusedUrl = when {
                        currentUri.startsWith("http", ignoreCase = true) -> currentUri
                        else -> streamResolver.getCachedStream(failedTrack.videoId)?.url
                    }
                    if (!refusedUrl.isNullOrBlank() && refusedUrl.startsWith("http", ignoreCase = true)) {
                        InnerTubeXResolver.onRefused(refusedUrl)
                    }
                }

                streamResolver.invalidate(failedTrack.videoId)
                if (altVideoId != null) {
                    failedAlternatesByTrack
                        .getOrPut(failedTrack.videoId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                        .add(altVideoId)
                    repository.evictAlternateVideoCache(failedTrack.videoId)
                    streamResolver.invalidate(altVideoId)
                }

                if (autoRetryVideoId != failedTrack.videoId) {
                    autoRetryVideoId = failedTrack.videoId
                    autoRetryCount = 0
                }
                if (autoRetryCount < 3) {
                    autoRetryCount++
                    val queue = _state.value.queue.ifEmpty { listOf(failedTrack) }
                    val index = _state.value.currentIndex.coerceIn(0, queue.lastIndex)
                    val preservePos = _state.value.positionMs.takeIf { it > 1_000L } ?: pendingRestoredPositionMs ?: 0L
                    val isRepeatedHttpBlock = httpStatus == 403 || httpStatus == 410 || httpStatus == 404
                    val preferAlt = autoRetryCount >= 2 || altVideoId != null || isRepeatedHttpBlock
                    Log.w(
                        "Sonora",
                        "Retrying playback video=${failedTrack.videoId} attempt=$autoRetryCount preferAlternate=$preferAlt http=$httpStatus"
                    )
                    resolveAndStartPlayback(
                        queue = queue,
                        safeIndex = index,
                        initialSeekMs = preservePos,
                        preferAlternate = preferAlt
                    )
                    return
                }
            }
            val detail = failedTrack?.let {
                PlaybackDiagnostics.logFailureReport(it.videoId, it.title, it.artist)
            }
            _state.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    streamNotice = null,
                    errorMessage = "No se pudo reproducir este tema. Toca reintentar o elige otro.",
                    errorDetail = detail
                )
            }
        }
    }

    private fun startBufferingWatchdog(timeoutMs: Long = 16_000L) {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = scope.launch {
            delay(timeoutMs)
            val controller = mediaController
            if (_state.value.isBuffering && controller?.isPlaying != true) {
                playJob?.cancel()
                pendingAction = null
                controller?.stop()
                val track = _state.value.currentTrack
                if (track != null) {
                    PlaybackDiagnostics.recordClientResult(
                        track.videoId,
                        "Watchdog: sin respuesta tras ${timeoutMs / 1000}s de buffering"
                    )
                    streamResolver.invalidate(track.videoId)
                }
                activeResolvedSourceVideoId?.let { altId ->
                    streamResolver.invalidate(altId)
                    activeResolvedSourceVideoId = null
                }
                val detail = track?.let {
                    PlaybackDiagnostics.logFailureReport(it.videoId, it.title, it.artist)
                }
                _state.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = "La conexión tardó demasiado. Toca reintentar.",
                        errorDetail = detail
                    )
                }
            }
        }
    }

    fun connect() {
        if (controllerFuture != null || customControllerFuture != null) return
        val shouldGateUi = _state.value.currentTrack != null && playJob?.isActive != true
        if (shouldGateUi) {
            _state.update { it.copy(isResyncingController = true) }
            controllerSyncTimeoutJob?.cancel()
            controllerSyncTimeoutJob = scope.launch {
                delay(2_500L)
                _state.update { it.copy(isResyncingController = false) }
            }
        }
        val testFactory = controllerFutureFactoryForTest
        val future: ListenableFuture<out Player> = if (testFactory != null) {
            testFactory.invoke().also { customControllerFuture = it }
        } else {
            val sessionToken = SessionToken(context, ComponentName(context, SonoraPlaybackService::class.java))
            MediaController.Builder(context, sessionToken).buildAsync().also { controllerFuture = it }
        }
        future.addListener(
            {
                runCatching {
                    val controller = future.get()
                    controllerSyncTimeoutJob?.cancel()
                    mediaController = controller
                    controller.addListener(playerListener)
                    syncFromController(controller)
                    pendingAction?.invoke()
                    pendingAction = null
                }.onFailure {
                    controllerSyncTimeoutJob?.cancel()
                    controllerFuture = null
                    customControllerFuture = null
                    pendingAction = null
                    _state.update {
                        it.copy(
                            isBuffering = false,
                            isPlaying = false,
                            isResyncingController = false,
                            errorMessage = "No se pudo iniciar el servicio de reproducción."
                        )
                    }
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    fun release() {
        persistQueueStateSync()
        scope.launch { listeningRecorder.flush() }
        controllerSyncTimeoutJob?.cancel()
        stopProgressLoop()
        bufferingWatchdogJob?.cancel()
        playJob?.cancel()
        prefetchJob?.cancel()
        tailExtendJob?.cancel()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        customControllerFuture?.cancel(false)
        controllerFuture = null
        customControllerFuture = null
        mediaController = null
        _state.update { it.copy(isResyncingController = false) }
    }

    fun playTrack(
        track: Track,
        contextQueue: List<Track> = emptyList(),
        origin: PlaybackOrigin? = null
    ) {
        activeSourcePlaylistId = null
        activePlaylistTransitions = emptyMap()
        customFragmentPlans = emptyMap()
        previewJob?.cancel()
        val queue = if (contextQueue.isNotEmpty()) {
            if (contextQueue.any { it.videoId == track.videoId }) contextQueue else listOf(track) + contextQueue
        } else {
            listOf(track)
        }
        val startIndex = queue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
        playQueue(queue, startIndex, origin = origin)
    }

    /**
     * Starts playing [track] immediately as a single-item queue and enriches Up Next
     * asynchronously with songs by the same artist + YouTube Music Radio recommendations.
     */
    fun playTrackWithSmartQueue(
        track: Track,
        searchContextSongs: List<Track> = emptyList(),
        origin: PlaybackOrigin? = null
    ) {
        activeSourcePlaylistId = null
        activePlaylistTransitions = emptyMap()
        customFragmentPlans = emptyMap()
        previewJob?.cancel()
        pendingSmartContextSongs = track.videoId to searchContextSongs
        playQueueInternal(
            queue = listOf(track),
            startIndex = 0,
            initialSeekMs = 0L,
            clearPlaylistContext = true,
            origin = origin,
            radioSeedTitle = track.title
        )
    }

    internal fun hasActivePlaylistOrTransitionContext(): Boolean =
        activeSourcePlaylistId != null ||
            activePlaylistTransitions.isNotEmpty() ||
            customFragmentPlans.isNotEmpty()

    fun playPlaylistWithTransitions(
        tracks: List<Track>,
        startIndex: Int = 0,
        playlistId: Long,
        origin: PlaybackOrigin? = null
    ) {
        if (tracks.isEmpty()) return
        previewJob?.cancel()
        customFragmentPlans = emptyMap()
        activeSourcePlaylistId = playlistId
        scope.launch {
            val specs = runCatching { repository.getPlaylistTransitionsOnce(playlistId) }.getOrDefault(emptyList())
            activePlaylistTransitions = specs.associateBy { it.fromVideoId to it.toVideoId }
            playQueueInternal(
                queue = tracks,
                startIndex = startIndex,
                initialSeekMs = 0L,
                clearPlaylistContext = false,
                origin = origin
            )
        }
    }

    fun playCustomMixFragments(fragments: List<CustomMixFragment>) {
        if (fragments.isEmpty()) return
        previewJob?.cancel()
        activeSourcePlaylistId = null
        activePlaylistTransitions = emptyMap()
        val tracks = fragments.map { it.track }
        val plansMap = mutableMapOf<Int, PlaylistTransitionSpec>()
        for (i in 0 until fragments.lastIndex) {
            val outFrag = fragments[i]
            val inFrag = fragments[i + 1]
            plansMap[i] = PlaylistTransitionSpec(
                playlistId = 0L,
                fromVideoId = outFrag.track.videoId,
                toVideoId = inFrag.track.videoId,
                fromExitMs = outFrag.endMs.coerceAtLeast(outFrag.startMs + 5_000L),
                toEntryMs = inFrag.startMs.coerceAtLeast(0L),
                crossfadeMs = outFrag.crossfadeMs.coerceIn(1_500, 16_000),
                curveType = outFrag.curveType
            )
        }
        customFragmentPlans = plansMap
        val entryMs = fragments.first().startMs.coerceAtLeast(0L)
        playQueueInternal(tracks, 0, initialSeekMs = entryMs, clearPlaylistContext = false)
    }

    fun previewTransitionPair(
        outgoingTrack: Track,
        fromExitMs: Long,
        incomingTrack: Track,
        toEntryMs: Long,
        overlapMs: Int,
        curveType: TransitionCurveType
    ) {
        previewJob?.cancel()
        activeSourcePlaylistId = null
        activePlaylistTransitions = emptyMap()
        val safeOverlap = overlapMs.coerceIn(1_500, 16_000)
        val effectiveExit = fromExitMs.takeIf { it > 4_000L }
            ?: (outgoingTrack.durationMs.takeIf { it > 15_000L } ?: 120_000L)
        val previewStartMs = (effectiveExit - safeOverlap.toLong() - 2_800L).coerceAtLeast(0L)
        val spec = PlaylistTransitionSpec(
            playlistId = 0L,
            fromVideoId = outgoingTrack.videoId,
            toVideoId = incomingTrack.videoId,
            fromExitMs = effectiveExit,
            toEntryMs = toEntryMs.coerceAtLeast(0L),
            crossfadeMs = safeOverlap,
            curveType = curveType
        )
        customFragmentPlans = mapOf(0 to spec)
        playQueueInternal(
            queue = listOf(outgoingTrack, incomingTrack),
            startIndex = 0,
            initialSeekMs = previewStartMs,
            clearPlaylistContext = false
        )
    }

    fun retuneUpcomingQueue(mood: QueueMoodFilter) {
        val snapshot = _state.value
        val currentTrack = snapshot.currentTrack ?: return
        if (currentTrack.isLocal || currentTrack.localUri != null) return
        _state.update { it.copy(activeQueueMood = mood, isRetuningQueue = true) }
        scope.launch {
            val existingTail = snapshot.queue.drop((snapshot.currentIndex + 1).coerceAtLeast(0))
            val retunedTail = runCatching {
                repository.retuneUpcomingQueue(
                    seedTrack = currentTrack,
                    existingTail = existingTail,
                    moodFilter = mood
                )
            }.getOrDefault(emptyList())

            val latest = _state.value
            if (latest.currentTrack?.videoId != currentTrack.videoId) {
                _state.update { it.copy(isRetuningQueue = false) }
                return@launch
            }
            if (retunedTail.isEmpty()) {
                _state.update { it.copy(isRetuningQueue = false) }
                return@launch
            }
            val safeCurrIdx = latest.currentIndex.coerceIn(0, latest.queue.lastIndex.coerceAtLeast(0))
            val prefix = latest.queue.take(safeCurrIdx + 1)
            val prefixIds = prefix.map { it.videoId }.toSet()
            val cleanTail = retunedTail.filter { it.videoId !in prefixIds }
            if (cleanTail.isEmpty()) {
                _state.update { it.copy(isRetuningQueue = false) }
                return@launch
            }
            val updatedQueue = prefix + cleanTail
            streamResolver.registerTracksMetadata(cleanTail)
            _state.update {
                it.copy(
                    queue = updatedQueue,
                    isRetuningQueue = false,
                    autoMixStatus = computeNextAutoMixStatus(
                        enabled = autoMixEnabled || crossfadeSeconds != 0,
                        currentTrack = currentTrack,
                        queue = updatedQueue,
                        currentIndex = safeCurrIdx,
                        durationMs = it.durationMs
                    )
                )
            }
            mediaController?.let { controller ->
                if (controller.mediaItemCount > safeCurrIdx + 1) {
                    controller.removeMediaItems(safeCurrIdx + 1, controller.mediaItemCount)
                }
                controller.addMediaItems(cleanTail.map { it.toMediaItem() })
                prefetchNextTrack(updatedQueue, safeCurrIdx)
            }
            persistQueueStateAsync()
        }
    }

    fun playQueue(
        queue: List<Track>,
        startIndex: Int = 0,
        origin: PlaybackOrigin? = null
    ) {
        playQueueInternal(
            queue = queue,
            startIndex = startIndex,
            initialSeekMs = 0L,
            clearPlaylistContext = true,
            origin = origin
        )
    }

    private fun playQueueInternal(
        queue: List<Track>,
        startIndex: Int = 0,
        initialSeekMs: Long = 0L,
        clearPlaylistContext: Boolean = true,
        origin: PlaybackOrigin? = null,
        radioSeedTitle: String? = null
    ) {
        if (queue.isEmpty()) return
        if (clearPlaylistContext) {
            activeSourcePlaylistId = null
            activePlaylistTransitions = emptyMap()
            customFragmentPlans = emptyMap()
            previewJob?.cancel()
        }
        pendingRestoredPositionMs = null
        tailExtendJob?.cancel()
        lastTailExtendedKey = null
        streamResolver.registerTracksMetadata(queue)
        val safeIndex = startIndex.coerceIn(0, queue.lastIndex)
        val targetTrack = queue[safeIndex]
        if (autoRetryVideoId != targetTrack.videoId) {
            autoRetryVideoId = targetTrack.videoId
            autoRetryCount = 0
            failedAlternatesByTrack.remove(targetTrack.videoId)
        }
        val clampedSeek = initialSeekMs.coerceAtLeast(0L)
        _positionMs.value = clampedSeek

        _state.update {
            it.copy(
                currentTrack = targetTrack,
                queue = queue,
                currentIndex = safeIndex,
                positionMs = clampedSeek,
                activeQueueMood = QueueMoodFilter.BALANCED,
                isBuffering = true,
                errorMessage = null,
                errorDetail = null,
                streamNotice = null,
                lyrics = null,
                playbackOrigin = origin ?: if (clearPlaylistContext) null else it.playbackOrigin,
                radioSeedTitle = radioSeedTitle ?: if (clearPlaylistContext) null else it.radioSeedTitle
            )
        }
        onActiveTrackChanged(targetTrack, safeIndex)
        resolveAndStartPlayback(queue, safeIndex, initialSeekMs = clampedSeek)
    }

    private fun resolveAndStartPlayback(
        queue: List<Track>,
        safeIndex: Int,
        initialSeekMs: Long = 0L,
        preferAlternate: Boolean = false
    ) {
        val targetTrack = queue.getOrNull(safeIndex) ?: return
        streamResolver.registerTracksMetadata(queue)
        if (!preferAlternate && autoRetryCount == 0) {
            PlaybackDiagnostics.beginResolution(targetTrack.videoId, targetTrack.title, targetTrack.artist)
        } else {
            PlaybackDiagnostics.updateTrackInfo(targetTrack.videoId, targetTrack.title, targetTrack.artist)
        }
        playJob?.cancel()
        prefetchJob?.cancel()
        bufferingWatchdogJob?.cancel()
        activeResolvedSourceVideoId = null
        playJob = scope.launch {
            val localEntity = if (targetTrack.isLocal || targetTrack.videoId.startsWith(LocalMusicRepository.LOCAL_ID_PREFIX)) {
                localMusicRepository?.getLocalTrack(targetTrack.videoId)
            } else null
            val resolvedLocalUri = targetTrack.localUri ?: localEntity?.contentUri

            val stream = if (resolvedLocalUri != null) {
                ResolvedStream(
                    url = resolvedLocalUri,
                    mimeType = localEntity?.mimeType?.takeIf { it.isNotBlank() } ?: "audio/*",
                    bitrate = localEntity?.bitrateBps ?: 0,
                    userAgent = StreamResolver.BROWSER_USER_AGENT,
                    expiresAtMs = Long.MAX_VALUE,
                    codecLabel = "Local"
                )
            } else {
                val localFile = offlineDownloadManager?.getLocalAudioFile(targetTrack.videoId)
                if (localFile != null) {
                    ResolvedStream(
                        url = Uri.fromFile(localFile).toString(),
                        mimeType = if (localFile.extension.equals("webm", true)) "audio/webm" else "audio/mp4",
                        bitrate = 256_000,
                        userAgent = StreamResolver.BROWSER_USER_AGENT,
                        expiresAtMs = Long.MAX_VALUE,
                        codecLabel = "Descargada"
                    )
                } else {
                    val excludedAlts = failedAlternatesByTrack[targetTrack.videoId].orEmpty()
                    if (preferAlternate) {
                        Log.i("Sonora", "Preferring alternate stream lookup for video=${targetTrack.videoId}")
                        withTimeoutOrNull(9_500L) {
                            repository.findAlternatePlayableStream(
                                originalTrack = targetTrack,
                                excludeVideoIds = excludedAlts
                            )
                        } ?: withTimeoutOrNull(14_000L) {
                            streamResolver.resolveAudioStream(targetTrack).getOrNull()
                        }
                    } else {
                        withTimeoutOrNull(14_000L) {
                            streamResolver.resolveAudioStream(targetTrack).getOrNull()
                        } ?: run {
                            Log.w(
                                "Sonora",
                                "Primary resolution unsuccessful for video=${targetTrack.videoId}; running alternate fallback"
                            )
                            withTimeoutOrNull(9_500L) {
                                repository.findAlternatePlayableStream(
                                    originalTrack = targetTrack,
                                    excludeVideoIds = excludedAlts
                                )
                            }
                        }
                    }
                }
            }
            if (_state.value.currentTrack?.videoId != targetTrack.videoId) return@launch

            if (stream == null) {
                bufferingWatchdogJob?.cancel()
                val detail = PlaybackDiagnostics.logFailureReport(
                    videoId = targetTrack.videoId,
                    titleHint = targetTrack.title,
                    artistHint = targetTrack.artist
                )
                Log.w("Sonora", "All resolution attempts failed for video=${targetTrack.videoId}")
                _state.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        streamNotice = null,
                        errorMessage = "No se pudo reproducir este tema. Toca reintentar o elige otro.",
                        errorDetail = detail
                    )
                }
                return@launch
            }

            val isAlternateVersion = !stream.sourceVideoId.isNullOrBlank() && stream.sourceVideoId != targetTrack.videoId
            activeResolvedSourceVideoId = stream.sourceVideoId
            streamResolver.recordResolvedAlternate(targetTrack.videoId, stream.sourceVideoId)
            Log.i(
                "Sonora",
                "Starting playback original=${targetTrack.videoId} alt=${stream.sourceVideoId ?: "none"} codec=${stream.codecLabel}"
            )
            _state.update {
                it.copy(
                    errorMessage = null,
                    errorDetail = null,
                    codecLabel = if (targetTrack.isLocal || resolvedLocalUri != null) "Audio Local" else "${stream.codecLabel} • Sin anuncios",
                    streamNotice = if (isAlternateVersion) ALTERNATE_VERSION_NOTICE else null,
                    statsForNerds = buildStatsForNerds(targetTrack, SonoraPlaybackBridge.liveTelemetry.value, stream)
                )
            }

            val startAction = {
                val controller = mediaController
                if (controller != null) {
                    val mediaItems = queue.mapIndexed { idx, track ->
                        if (idx == safeIndex) track.toMediaItem(stream) else track.toMediaItem()
                    }
                    controller.setMediaItems(mediaItems, safeIndex, initialSeekMs.coerceAtLeast(0L))
                    controller.prepare()
                    controller.play()
                    startBufferingWatchdog(timeoutMs = 16_000L)
                }
            }

            if (mediaController != null) {
                startAction()
            } else {
                pendingAction = startAction
                connect()
            }
        }
    }

    private fun prefetchNextTrack(queue: List<Track>, activeIndex: Int) {
        val nextIndex = activeIndex + 1
        val nextTrack = queue.getOrNull(nextIndex) ?: return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            delay(500L)
            if (!nextTrack.isLocal && nextTrack.localUri == null) {
                val nextStream = withTimeoutOrNull(10_000L) {
                    streamResolver.resolveAudioStream(nextTrack).getOrNull()
                } ?: withTimeoutOrNull(8_000L) {
                    repository.findAlternatePlayableStream(nextTrack)
                }
                val controller = mediaController
                if (nextStream != null && controller != null &&
                    controller.currentMediaItemIndex == activeIndex &&
                    nextIndex < controller.mediaItemCount
                ) {
                    controller.replaceMediaItem(nextIndex, nextTrack.toMediaItem(nextStream))
                }
            }
        }
    }

    fun addToQueue(track: Track) {
        streamResolver.registerTrackMetadata(track)
        val currentQueue = _state.value.queue
        if (currentQueue.isEmpty()) {
            playTrack(track)
            return
        }
        val updated = currentQueue + track
        _state.update { it.copy(queue = updated) }
        mediaController?.addMediaItem(track.toMediaItem())
    }

    fun playNext(track: Track) {
        streamResolver.registerTrackMetadata(track)
        val currentQueue = _state.value.queue
        if (currentQueue.isEmpty() || _state.value.currentTrack == null) {
            playTrack(track)
            return
        }
        val insertIndex = (_state.value.currentIndex + 1).coerceIn(0, currentQueue.size)
        val updated = currentQueue.toMutableList().apply { add(insertIndex, track) }
        _state.update { it.copy(queue = updated) }
        mediaController?.addMediaItem(insertIndex, track.toMediaItem())
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentQueue = _state.value.queue
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return
        val updated = currentQueue.toMutableList()
        val moved = updated.removeAt(fromIndex)
        updated.add(toIndex, moved)

        val curIdx = _state.value.currentIndex
        val newCurIdx = when {
            curIdx == fromIndex -> toIndex
            fromIndex < curIdx && toIndex >= curIdx -> curIdx - 1
            fromIndex > curIdx && toIndex <= curIdx -> curIdx + 1
            else -> curIdx
        }
        _state.update { it.copy(queue = updated, currentIndex = newCurIdx) }
        mediaController?.moveMediaItem(fromIndex, toIndex)
    }

    fun removeQueueItem(index: Int) {
        val currentQueue = _state.value.queue
        if (index !in currentQueue.indices) return
        if (currentQueue.size == 1) {
            playJob?.cancel()
            prefetchJob?.cancel()
            bufferingWatchdogJob?.cancel()
            mediaController?.stop()
            mediaController?.clearMediaItems()
            _positionMs.value = 0L
            _state.update {
                it.copy(
                    currentTrack = null,
                    queue = emptyList(),
                    currentIndex = -1,
                    isPlaying = false,
                    isBuffering = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    errorMessage = null,
                    errorDetail = null,
                    streamNotice = null,
                    playbackOrigin = null
                )
            }
            return
        }
        val curIdx = _state.value.currentIndex
        if (index == curIdx) {
            val updated = currentQueue.toMutableList().apply { removeAt(index) }
            val nextIdx = index.coerceIn(0, updated.lastIndex)
            playQueue(updated, nextIdx, origin = _state.value.playbackOrigin)
            return
        }

        val updated = currentQueue.toMutableList().apply { removeAt(index) }
        val newCurIdx = if (index < curIdx) curIdx - 1 else curIdx
        _state.update { it.copy(queue = updated, currentIndex = newCurIdx) }
        mediaController?.removeMediaItem(index)
        persistQueueStateAsync()
    }

    fun clearQueue() {
        val snapshot = _state.value
        val currentTrack = snapshot.currentTrack
        if (currentTrack == null || snapshot.queue.isEmpty()) {
            playJob?.cancel()
            prefetchJob?.cancel()
            bufferingWatchdogJob?.cancel()
            mediaController?.stop()
            mediaController?.clearMediaItems()
            _positionMs.value = 0L
            _state.update {
                it.copy(
                    currentTrack = null,
                    queue = emptyList(),
                    currentIndex = -1,
                    isPlaying = false,
                    isBuffering = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    errorMessage = null,
                    errorDetail = null,
                    streamNotice = null,
                    playbackOrigin = null
                )
            }
            return
        }
        val safeIdx = snapshot.currentIndex.coerceIn(0, snapshot.queue.lastIndex)
        val controller = mediaController
        if (controller != null && controller.mediaItemCount > safeIdx + 1) {
            controller.removeMediaItems(safeIdx + 1, controller.mediaItemCount)
        }
        if (controller != null && safeIdx > 0 && controller.mediaItemCount > 1) {
            controller.removeMediaItems(0, safeIdx)
        }
        val keptQueue = listOf(currentTrack)
        _state.update {
            it.copy(
                queue = keptQueue,
                currentIndex = 0,
                autoMixStatus = computeNextAutoMixStatus(
                    enabled = autoMixEnabled || crossfadeSeconds != 0,
                    currentTrack = currentTrack,
                    queue = keptQueue,
                    currentIndex = 0,
                    durationMs = it.durationMs
                )
            )
        }
        persistQueueStateAsync()
    }

    fun updateTrackMetadata(meta: ResolvedTrackMetadata) {
        _state.update { current ->
            fun merge(track: Track): Track {
                if (track.videoId != meta.videoId) return track
                return track.copy(
                    artistId = track.artistId?.takeIf { it.isNotBlank() } ?: meta.artistId,
                    album = track.album?.takeIf { it.isNotBlank() } ?: meta.album,
                    albumId = track.albumId?.takeIf { it.isNotBlank() } ?: meta.albumId
                )
            }
            current.copy(
                currentTrack = current.currentTrack?.let(::merge),
                queue = current.queue.map(::merge)
            )
        }
    }

    fun skipToQueueIndex(index: Int) {
        val controller = mediaController
        if (controller != null) {
            syncFromController(controller)
        }
        val queue = _state.value.queue
        if (index !in queue.indices) return
        val hasMixContext = activeSourcePlaylistId != null || customFragmentPlans.isNotEmpty()
        val incomingEntryMs = if (hasMixContext && index > 0) {
            customFragmentPlans[index - 1]?.toEntryMs
                ?: activePlaylistTransitions[queue[index - 1].videoId to queue[index].videoId]?.toEntryMs
                ?: 0L
        } else 0L

        if (controller != null && controllerMatchesQueue(controller, queue)) {
            val currentIdx = resolveCurrentIndexFromController(controller, queue)
            val targetTrack = queue[index]
            pendingRestoredPositionMs = null
            if (autoRetryVideoId != targetTrack.videoId) {
                autoRetryVideoId = targetTrack.videoId
                autoRetryCount = 0
            }
            if (!hasMixContext && incomingEntryMs == 0L && !controller.shuffleModeEnabled &&
                index == currentIdx + 1 && controller.hasNextMediaItem()
            ) {
                controller.seekToNextMediaItem()
                controller.play()
            } else if (!hasMixContext && incomingEntryMs == 0L && !controller.shuffleModeEnabled &&
                index == currentIdx - 1 && controller.hasPreviousMediaItem()
            ) {
                controller.seekToPreviousMediaItem()
                controller.play()
            } else {
                controller.seekTo(index, incomingEntryMs.coerceAtLeast(0L))
                controller.play()
            }
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            if (_state.value.currentTrack?.videoId != targetTrack.videoId || _state.value.currentIndex != index) {
                _state.update {
                    it.copy(
                        positionMs = incomingEntryMs.coerceAtLeast(0L),
                        errorMessage = null
                    )
                }
                onActiveTrackChanged(targetTrack, index)
            } else {
                _state.update {
                    it.copy(
                        positionMs = incomingEntryMs.coerceAtLeast(0L),
                        errorMessage = null
                    )
                }
            }
            return
        }

        val rebuildQueue = if (playJob?.isActive != true &&
            controller != null &&
            (controller.mediaItemCount > 0 || controller.currentMediaItem != null)
        ) {
            val (reconstructed, _) = reconstructQueueFromControllerOrPersisted(
                controller = controller,
                targetMediaId = controller.currentMediaItem?.mediaId,
                controllerIndex = controller.currentMediaItemIndex
            )
            if (reconstructed.isNotEmpty()) reconstructed else queue
        } else {
            queue
        }
        val safeTargetIndex = index.coerceIn(0, rebuildQueue.lastIndex)
        val targetEntryMs = if (hasMixContext && safeTargetIndex > 0) {
            customFragmentPlans[safeTargetIndex - 1]?.toEntryMs
                ?: activePlaylistTransitions[rebuildQueue[safeTargetIndex - 1].videoId to rebuildQueue[safeTargetIndex].videoId]?.toEntryMs
                ?: 0L
        } else 0L

        if (hasMixContext) {
            playQueueInternal(rebuildQueue, safeTargetIndex, initialSeekMs = targetEntryMs, clearPlaylistContext = false)
        } else {
            playQueue(rebuildQueue, safeTargetIndex)
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: run {
            if (_state.value.currentTrack != null) retryCurrent()
            return
        }
        val restoredSeek = pendingRestoredPositionMs
        if (restoredSeek != null && _state.value.queue.isNotEmpty()) {
            pendingRestoredPositionMs = null
            resolveAndStartPlayback(
                queue = _state.value.queue,
                safeIndex = _state.value.currentIndex.coerceIn(0, _state.value.queue.lastIndex),
                initialSeekMs = restoredSeek
            )
            return
        }
        if ((controller.playbackState == Player.STATE_IDLE || _state.value.errorMessage != null) &&
            _state.value.currentTrack != null
        ) {
            retryCurrent()
            return
        }
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun skipNext() {
        val controller = mediaController
        if (controller != null) {
            syncFromController(controller)
        }
        val snapshot = _state.value
        val queue = snapshot.queue
        val repeat = snapshot.repeatMode
        if (controller != null && controller.shuffleModeEnabled && controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            controller.play()
            return
        }
        if (queue.isNotEmpty()) {
            val currentIdx = resolveCurrentIndexFromController(controller, queue)
            val nextIndex = currentIdx + 1
            when {
                nextIndex in queue.indices -> skipToQueueIndex(nextIndex)
                repeat == RepeatMode.ALL -> skipToQueueIndex(0)
                autoplayRadioEnabled && snapshot.currentTrack?.isLocal != true -> extendQueueAtTail(advanceToNextAfterAppend = true)
                else -> controller?.pause()
            }
        } else if (controller != null && controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            controller.play()
        }
    }

    fun skipPrevious() {
        val controller = mediaController
        if (controller != null) {
            syncFromController(controller)
        }
        if (controller != null && controller.currentPosition > 3_500L) {
            controller.seekTo(0L)
            return
        }
        val queue = _state.value.queue
        if (controller != null && controller.shuffleModeEnabled && controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
            controller.play()
            return
        }
        if (queue.isNotEmpty()) {
            val currentIdx = resolveCurrentIndexFromController(controller, queue)
            val prevIndex = currentIdx - 1
            if (prevIndex in queue.indices) {
                skipToQueueIndex(prevIndex)
            } else {
                controller?.seekTo(0L)
            }
        } else {
            controller?.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        val controller = mediaController
        listeningRecorder.onSeek(controller?.isPlaying == true)
        _positionMs.value = clamped
        if (controller == null || controller.mediaItemCount == 0) {
            // Preserve seek target when paused/restored on cold startup!
            pendingRestoredPositionMs = clamped
            _state.update { it.copy(positionMs = clamped) }
            persistQueueStateAsync()
            return
        }
        pendingRestoredPositionMs = null
        _state.update { it.copy(positionMs = clamped) }
        controller.seekTo(clamped)
        if (!controller.isPlaying) {
            persistQueueStateAsync()
        }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun retryCurrent() {
        val track = _state.value.currentTrack ?: return
        val resumeMs = pendingRestoredPositionMs ?: _state.value.positionMs.takeIf { it > 1_000L } ?: 0L
        pendingRestoredPositionMs = null
        autoRetryVideoId = track.videoId
        autoRetryCount = 0
        failedAlternatesByTrack.remove(track.videoId)
        repository.evictAlternateVideoCache(track.videoId)
        activeResolvedSourceVideoId?.let { altId ->
            streamResolver.resetFailedSources(altId)
            activeResolvedSourceVideoId = null
        }
        streamResolver.resetFailedSources(track.videoId)
        val queue = _state.value.queue.ifEmpty { listOf(track) }
        val index = _state.value.currentIndex.coerceIn(0, queue.lastIndex)
        resolveAndStartPlayback(queue, index, resumeMs)
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null, errorDetail = null) }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _sleepTimerRemainingSec.value = null
            return
        }
        _sleepAfterCurrentTrack.value = false
        var remaining = minutes * 60
        _sleepTimerRemainingSec.value = remaining
        sleepTimerJob = scope.launch {
            while (remaining > 0) {
                delay(1_000L)
                remaining--
                _sleepTimerRemainingSec.value = remaining
            }
            mediaController?.pause()
            _sleepTimerRemainingSec.value = null
        }
    }

    fun setSleepAfterCurrentTrack(enabled: Boolean) {
        if (enabled) {
            sleepTimerJob?.cancel()
            _sleepTimerRemainingSec.value = null
        }
        _sleepAfterCurrentTrack.value = enabled
    }

    fun reloadLyrics() {
        val track = _state.value.currentTrack ?: return
        _state.update { it.copy(isLoadingLyrics = true) }
        scope.launch {
            val fresh = repository.reloadLyrics(track, null)
            if (_state.value.currentTrack?.videoId == track.videoId) {
                _state.update { it.copy(lyrics = fresh, isLoadingLyrics = false) }
            }
        }
    }

    private fun onActiveTrackChanged(track: Track, index: Int) {
        preCuedAutoMixKey = null
        triggeredAutoMixKey = null
        activeListenedMs = 0L
        lastProgressWallMs = if (_state.value.isPlaying) System.currentTimeMillis() else 0L
        trackStartedAtSec = System.currentTimeMillis() / 1000L
        nowPlayingSentKey = null
        scrobbledKey = null
        scope.launch {
            listeningRecorder.onTrackChanged(track, index, _state.value.isPlaying)
        }

        val queue = _state.value.queue
        val dur = track.durationMs.takeIf { d -> d > 0L } ?: _state.value.durationMs
        val nextTrack = resolveUpcomingTrack(queue, index, _state.value.repeatMode)
        val hasCustomTransition = nextTrack != null && resolveTransitionSpecForPair(index, track, nextTrack) != null
        val knownAltId = if (!track.isLocal && track.localUri == null) {
            streamResolver.getResolvedAlternate(track.videoId)
        } else null
        _state.update {
            it.copy(
                currentTrack = track,
                currentIndex = index,
                durationMs = dur,
                lyrics = null,
                isLoadingLyrics = true,
                errorMessage = null,
                isResyncingController = false,
                streamNotice = if (!knownAltId.isNullOrBlank() && knownAltId != track.videoId) {
                    ALTERNATE_VERSION_NOTICE
                } else null,
                statsForNerds = buildStatsForNerds(track, SonoraPlaybackBridge.liveTelemetry.value),
                autoMixStatus = computeNextAutoMixStatus(
                    enabled = autoMixEnabled || crossfadeSeconds != 0 || hasCustomTransition,
                    currentTrack = track,
                    queue = queue,
                    currentIndex = index,
                    durationMs = dur
                )
            )
        }
        persistQueueStateAsync()
        SonoraMediaWidgetProvider.updateWidgets(
            context = context,
            title = track.title,
            artist = track.artist,
            artworkUrl = track.artworkUrl,
            isPlaying = _state.value.isPlaying
        )

        integrationsManager?.updateDiscordPresence(latestSettings, track, _state.value.isPlaying)
        if (_state.value.isPlaying) {
            val activeKey = "$index:${track.videoId}"
            nowPlayingSentKey = activeKey
            scope.launch { integrationsManager?.dispatchNowPlaying(latestSettings, track) }
        }

        extractArtworkColors(track.artworkUrl)

        metadataJob?.cancel()
        metadataJob = scope.launch {
            // Resolve stream badge & alternate-version notice on automatic queue transitions
            if (playJob?.isActive != true && !track.isLocal && track.localUri == null) {
                launch {
                    val altBefore = streamResolver.getResolvedAlternate(track.videoId)
                    if (!altBefore.isNullOrBlank() && altBefore != track.videoId &&
                        _state.value.currentTrack?.videoId == track.videoId
                    ) {
                        _state.update { it.copy(streamNotice = ALTERNATE_VERSION_NOTICE) }
                    } else {
                        val stream = streamResolver.getCachedStream(track.videoId)
                            ?: streamResolver.resolveAudioStream(track.videoId).getOrNull()
                        val altAfter = stream?.sourceVideoId?.takeIf { it.isNotBlank() && it != track.videoId }
                            ?: streamResolver.getResolvedAlternate(track.videoId)
                        if (_state.value.currentTrack?.videoId == track.videoId) {
                            _state.update {
                                it.copy(
                                    codecLabel = if (stream != null) "${stream.codecLabel} • Sin anuncios" else it.codecLabel,
                                    streamNotice = if (!altAfter.isNullOrBlank() && altAfter != track.videoId) {
                                        ALTERNATE_VERSION_NOTICE
                                    } else it.streamNotice,
                                    statsForNerds = buildStatsForNerds(track, SonoraPlaybackBridge.liveTelemetry.value, stream)
                                )
                            }
                        }
                    }
                }
            }

            val contextForTrack = pendingSmartContextSongs
                ?.takeIf { it.first == track.videoId }
                ?.second
                .orEmpty()
            if (pendingSmartContextSongs?.first == track.videoId) {
                pendingSmartContextSongs = null
            }

            // Build intelligent artist + song-seeded radio queue when playing a single/searched online song
            val lyricsBrowseId: String? = if (!track.isLocal && track.localUri == null && autoplayRadioEnabled && _state.value.queue.size <= 1) {
                val (smartQueue, lyricsId) = repository.buildSmartRelatedQueueWithLyrics(
                    seedTrack = track,
                    contextSongs = contextForTrack
                )
                if (smartQueue.size > 1 && _state.value.currentTrack?.videoId == track.videoId) {
                    streamResolver.registerTracksMetadata(smartQueue)
                    _state.update {
                        it.copy(
                            queue = smartQueue,
                            currentIndex = 0,
                            autoMixStatus = computeNextAutoMixStatus(
                                enabled = autoMixEnabled,
                                currentTrack = track,
                                queue = smartQueue,
                                currentIndex = 0,
                                durationMs = it.durationMs
                            )
                        )
                    }
                    persistQueueStateAsync()
                    mediaController?.let { controller ->
                        val extras = smartQueue.drop(1).map { it.toMediaItem() }
                        if (extras.isNotEmpty() && controller.mediaItemCount == 1) {
                            controller.addMediaItems(extras)
                            if (controller.playbackState == Player.STATE_READY) {
                                prefetchNextTrack(smartQueue, 0)
                            }
                        }
                    }
                }
                lyricsId
            } else if (!track.isLocal && track.localUri == null) {
                if (autoplayRadioEnabled &&
                    _state.value.repeatMode == RepeatMode.OFF &&
                    _state.value.queue.size > 1 &&
                    index >= _state.value.queue.lastIndex - 1
                ) {
                    extendQueueAtTail(advanceToNextAfterAppend = false)
                }
                val (_, lyricsId) = repository.getUpNextAndLyricsId(track.videoId)
                if (_state.value.queue.size > index + 1 && playJob?.isActive != true) {
                    prefetchNextTrack(_state.value.queue, index)
                }
                lyricsId
            } else {
                null
            }

            val lyricsData = repository.getTrackLyrics(track, lyricsBrowseId)

            _state.update {
                it.copy(
                    lyrics = lyricsData,
                    isLoadingLyrics = false
                )
            }
        }
    }

    private fun extendQueueAtTail(advanceToNextAfterAppend: Boolean) {
        val snapshot = _state.value
        val existingQueue = snapshot.queue
        if (existingQueue.isEmpty()) return
        val seedTrack = snapshot.currentTrack ?: existingQueue.lastOrNull() ?: return
        val tailKey = "${existingQueue.size}:${seedTrack.videoId}"
        if (!advanceToNextAfterAppend && lastTailExtendedKey == tailKey) return
        if (tailExtendJob?.isActive == true && !advanceToNextAfterAppend) return

        lastTailExtendedKey = tailKey
        tailExtendJob?.cancel()
        tailExtendJob = scope.launch {
            val additions = runCatching {
                repository.buildTailExtensionTracks(
                    seedTrack = seedTrack,
                    existingQueue = existingQueue,
                    limit = 15
                )
            }.getOrDefault(emptyList())

            if (additions.isEmpty()) {
                return@launch
            }

            val currentSnapshot = _state.value
            val currentIds = currentSnapshot.queue.map { it.videoId }.toSet()
            val uniqueAdditions = additions.filter { it.videoId !in currentIds }
            if (uniqueAdditions.isEmpty()) return@launch

            val firstNewIndex = currentSnapshot.queue.size
            val updatedQueue = currentSnapshot.queue + uniqueAdditions
            streamResolver.registerTracksMetadata(uniqueAdditions)
            _state.update {
                it.copy(
                    queue = updatedQueue,
                    autoMixStatus = computeNextAutoMixStatus(
                        enabled = autoMixEnabled,
                        currentTrack = it.currentTrack,
                        queue = updatedQueue,
                        currentIndex = it.currentIndex,
                        durationMs = it.durationMs
                    )
                )
            }
            mediaController?.addMediaItems(uniqueAdditions.map { it.toMediaItem() })
            if (advanceToNextAfterAppend) {
                skipToQueueIndex(firstNewIndex)
            } else {
                prefetchNextTrack(updatedQueue, currentSnapshot.currentIndex)
            }
        }
    }

    private fun extractArtworkColors(artworkUrl: String) {
        if (artworkUrl.isBlank()) return
        paletteJob?.cancel()
        paletteJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .allowHardware(false)
                    .size(200)
                    .build()
                val result = imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.let { it as? BitmapDrawable }?.bitmap
                if (bitmap != null) {
                    val palette = Palette.from(bitmap).generate()
                    val vibrant = palette.vibrantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                    val dark = palette.darkMutedSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                    _state.update {
                        it.copy(
                            ambientPrimary = vibrant?.let { Color(it) } ?: SonoraCrimson,
                            ambientSecondary = dark?.let { Color(it) } ?: SonoraPlum
                        )
                    }
                }
            }
        }
    }

    private fun resolveTrackIndexInQueue(
        queue: List<Track>,
        mediaId: String?,
        controllerIndex: Int
    ): Int {
        if (mediaId.isNullOrBlank() || queue.isEmpty()) return -1
        var bestIdx = -1
        var bestDistance = Int.MAX_VALUE
        for (i in queue.indices) {
            if (queue[i].videoId == mediaId) {
                val dist = if (controllerIndex >= 0) kotlin.math.abs(i - controllerIndex) else i
                if (dist < bestDistance) {
                    bestDistance = dist
                    bestIdx = i
                }
            }
        }
        return bestIdx
    }

    private fun controllerMatchesQueue(controller: Player, queue: List<Track>): Boolean {
        val count = controller.mediaItemCount
        if (count != queue.size) return false
        for (i in 0 until count) {
            if (controller.getMediaItemAt(i).mediaId != queue[i].videoId) return false
        }
        return true
    }

    private fun resolveCurrentIndexFromController(controller: Player?, queue: List<Track>): Int {
        if (controller == null || queue.isEmpty()) return _state.value.currentIndex
        val controllerIdx = controller.currentMediaItemIndex
        val controllerMediaId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
            ?: if (controllerIdx in 0 until controller.mediaItemCount) {
                controller.getMediaItemAt(controllerIdx).mediaId.takeIf { it.isNotBlank() }
            } else null
        if (controllerMediaId != null) {
            val resolved = resolveTrackIndexInQueue(queue, controllerMediaId, controllerIdx)
            if (resolved >= 0) return resolved
        }
        if (controllerIdx in queue.indices) return controllerIdx
        return _state.value.currentIndex
    }

    private fun readPersistedQueueSync(): PersistedQueueState? {
        return runCatching {
            if (!queueStateFile.exists()) return@runCatching null
            val raw = queueStateFile.readText()
            if (raw.isBlank()) return@runCatching null
            queueJson.decodeFromString<PersistedQueueState>(raw)
        }.getOrNull()
    }

    private fun mediaItemToTrack(
        item: MediaItem,
        knownById: Map<String, Track>,
        persistedById: Map<String, Track>,
        measuredDurationMs: Long
    ): Track? {
        val uri = item.localConfiguration?.uri
        val id = item.mediaId.takeIf { it.isNotBlank() }
            ?: uri?.let { u ->
                if (u.scheme == "sonora") u.host ?: u.lastPathSegment else u.toString()
            }
            ?: return null

        val existing = knownById[id] ?: persistedById[id]
        if (existing != null) {
            return if (existing.durationMs <= 0L && measuredDurationMs > 0L) {
                existing.copy(durationMs = measuredDurationMs)
            } else {
                existing
            }
        }

        val hint = streamResolver.getTrackMetadataHint(id)
        val meta = item.mediaMetadata
        val isLocalItem = id.startsWith(LocalMusicRepository.LOCAL_ID_PREFIX) ||
            uri?.scheme.equals("content", ignoreCase = true)
        return Track(
            videoId = id,
            title = hint?.title?.takeIf { it.isNotBlank() }
                ?: meta.title?.toString()?.takeIf { it.isNotBlank() }
                ?: "Pista",
            artist = hint?.artist?.takeIf { it.isNotBlank() }
                ?: meta.artist?.toString()?.takeIf { it.isNotBlank() }
                ?: "Artista desconocido",
            album = meta.albumTitle?.toString()?.takeIf { it.isNotBlank() },
            artworkUrl = meta.artworkUri?.toString().orEmpty(),
            durationMs = hint?.durationMs?.takeIf { it > 0L } ?: measuredDurationMs.coerceAtLeast(0L),
            localUri = uri?.toString()?.takeIf { isLocalItem },
            isLocal = isLocalItem
        )
    }

    private fun reconstructQueueFromControllerOrPersisted(
        controller: Player,
        targetMediaId: String?,
        controllerIndex: Int
    ): Pair<List<Track>, Int> {
        val knownById = _state.value.queue.associateBy { it.videoId }
        val persistedState = readPersistedQueueSync()
        val persistedById = persistedState?.queue?.associateBy { it.videoId }.orEmpty()
        val controllerDuration = controller.duration.takeIf { it > 0L } ?: 0L

        val count = controller.mediaItemCount
        if (count > 0) {
            val controllerTracks = (0 until count).mapNotNull { idx ->
                val dur = if (idx == controllerIndex) controllerDuration else 0L
                mediaItemToTrack(controller.getMediaItemAt(idx), knownById, persistedById, dur)
            }
            if (controllerTracks.isNotEmpty()) {
                val resolvedIdx = if (!targetMediaId.isNullOrBlank()) {
                    resolveTrackIndexInQueue(controllerTracks, targetMediaId, controllerIndex)
                } else {
                    controllerIndex.coerceIn(0, controllerTracks.lastIndex)
                }
                if (resolvedIdx >= 0) {
                    return controllerTracks to resolvedIdx
                }
            }
        }

        if (persistedState != null && persistedState.queue.isNotEmpty() && !targetMediaId.isNullOrBlank()) {
            val persistedIdx = resolveTrackIndexInQueue(persistedState.queue, targetMediaId, controllerIndex)
            if (persistedIdx >= 0) {
                return persistedState.queue to persistedIdx
            }
        }

        val currentItem = controller.currentMediaItem
        if (currentItem != null) {
            val singleTrack = mediaItemToTrack(currentItem, knownById, persistedById, controllerDuration)
            if (singleTrack != null) {
                return listOf(singleTrack) to 0
            }
        }

        return emptyList<Track>() to -1
    }

    internal fun seedStateForTest(
        queue: List<Track>,
        currentIndex: Int,
        positionMs: Long = 0L,
        isPlaying: Boolean = false
    ) {
        val safeIdx = currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val track = queue.getOrNull(safeIdx)
        val clampedPos = positionMs.coerceAtLeast(0L)
        _positionMs.value = clampedPos
        _state.update {
            it.copy(
                currentTrack = track,
                queue = queue,
                currentIndex = if (track != null) safeIdx else -1,
                positionMs = clampedPos,
                durationMs = track?.durationMs ?: 0L,
                isPlaying = isPlaying,
                isResyncingController = false
            )
        }
    }

    internal fun syncFromController(controller: Player) {
        val mappedRepeat = when (controller.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
        if (playJob?.isActive == true || pendingAction != null) {
            _state.update {
                it.copy(
                    shuffleEnabled = controller.shuffleModeEnabled,
                    repeatMode = mappedRepeat,
                    isResyncingController = false
                )
            }
            return
        }
        val hasControllerMedia = controller.mediaItemCount > 0 || controller.currentMediaItem != null
        if (!hasControllerMedia && _state.value.currentTrack != null) {
            _state.update {
                it.copy(
                    shuffleEnabled = controller.shuffleModeEnabled,
                    repeatMode = mappedRepeat,
                    isResyncingController = false
                )
            }
            return
        }
        if (!hasControllerMedia) {
            val pos = controller.currentPosition.coerceAtLeast(0L)
            _positionMs.value = pos
            _state.update {
                it.copy(
                    isPlaying = controller.isPlaying,
                    isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                    positionMs = pos,
                    durationMs = controller.duration.takeIf { d -> d > 0L } ?: it.durationMs,
                    shuffleEnabled = controller.shuffleModeEnabled,
                    repeatMode = mappedRepeat,
                    isResyncingController = false
                )
            }
            return
        }

        val snapshot = _state.value
        val controllerIndex = controller.currentMediaItemIndex
        val controllerMediaId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
            ?: if (controllerIndex in 0 until controller.mediaItemCount) {
                controller.getMediaItemAt(controllerIndex).mediaId.takeIf { it.isNotBlank() }
            } else null

        var targetQueue = snapshot.queue
        var resolvedIndex = if (controllerMediaId != null) {
            resolveTrackIndexInQueue(targetQueue, controllerMediaId, controllerIndex)
        } else -1

        if (resolvedIndex < 0 || (controller.mediaItemCount > 0 && !controllerMatchesQueue(controller, targetQueue))) {
            val (reconstructed, reconstructedIdx) = reconstructQueueFromControllerOrPersisted(
                controller = controller,
                targetMediaId = controllerMediaId,
                controllerIndex = controllerIndex
            )
            if (reconstructed.isNotEmpty()) {
                targetQueue = reconstructed
                resolvedIndex = reconstructedIdx
                streamResolver.registerTracksMetadata(reconstructed)
            }
        }

        val resolvedTrack = targetQueue.getOrNull(resolvedIndex)
        val controllerDurationMs = controller.duration.takeIf { d -> d > 0L }
        val resolvedDurationMs = controllerDurationMs
            ?: resolvedTrack?.durationMs?.takeIf { it > 0L }
            ?: snapshot.durationMs

        val trackChanged = resolvedTrack != null && (
            snapshot.currentTrack?.videoId != resolvedTrack.videoId ||
                snapshot.currentIndex != resolvedIndex ||
                metadataJob == null
            )
        val queueChanged = targetQueue != snapshot.queue
        val pos = controller.currentPosition.coerceAtLeast(0L)
        _positionMs.value = pos

        _state.update {
            it.copy(
                queue = targetQueue,
                isPlaying = controller.isPlaying,
                isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                positionMs = pos,
                durationMs = resolvedDurationMs,
                shuffleEnabled = controller.shuffleModeEnabled,
                repeatMode = mappedRepeat,
                isResyncingController = if (trackChanged) it.isResyncingController else false
            )
        }

        if (resolvedTrack != null && trackChanged) {
            onActiveTrackChanged(resolvedTrack, resolvedIndex)
            if (controllerDurationMs != null && controllerDurationMs > 0L && _state.value.durationMs != controllerDurationMs) {
                _state.update { it.copy(durationMs = controllerDurationMs) }
            }
        } else if (queueChanged && resolvedTrack != null) {
            _state.update {
                it.copy(
                    currentIndex = resolvedIndex,
                    isResyncingController = false,
                    autoMixStatus = computeNextAutoMixStatus(
                        enabled = autoMixEnabled || crossfadeSeconds != 0 || hasActivePlaylistOrTransitionContext(),
                        currentTrack = resolvedTrack,
                        queue = targetQueue,
                        currentIndex = resolvedIndex,
                        durationMs = it.durationMs
                    )
                )
            }
        } else if (_state.value.isResyncingController) {
            _state.update { it.copy(isResyncingController = false) }
        }

        if (controller.isPlaying) startProgressLoop()
    }

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        lastProgressWallMs = System.currentTimeMillis()
        var lastMediaIdSyncCheckMs = 0L
        progressJob = scope.launch {
            while (isActive) {
                val controller = mediaController
                if (controller != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastMediaIdSyncCheckMs >= 1_000L) {
                        lastMediaIdSyncCheckMs = now
                        if (playJob?.isActive != true) {
                            val controllerMediaId = controller.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                            if (controllerMediaId != null && controllerMediaId != _state.value.currentTrack?.videoId) {
                                syncFromController(controller)
                            }
                        }
                    }

                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val dur = controller.duration.takeIf { it > 0L }
                        ?: _state.value.currentTrack?.durationMs
                        ?: 0L
                    _positionMs.value = pos
                    if (_state.value.durationMs != dur) {
                        _state.update { it.copy(durationMs = dur) }
                    }

                    if (controller.isPlaying) {
                        val delta = if (lastProgressWallMs > 0L) (now - lastProgressWallMs).coerceIn(0L, 600L) else 0L
                        lastProgressWallMs = now
                        activeListenedMs += delta

                        val track = _state.value.currentTrack
                        val idx = _state.value.currentIndex
                        if (track != null) {
                            listeningRecorder.onSampleTick(
                                track = track,
                                queueIndex = idx,
                                isPlaying = true,
                                measuredDurationMs = dur
                            )
                            val activeKey = "$idx:${track.videoId}"
                            val thresholdMs = maxOf(15_000L, minOf((dur / 2L).coerceAtLeast(30_000L), 240_000L))
                            if (activeListenedMs >= thresholdMs && scrobbledKey != activeKey) {
                                scrobbledKey = activeKey
                                val stamp = trackStartedAtSec.takeIf { it > 0L } ?: (now / 1000L)
                                scope.launch { integrationsManager?.dispatchScrobble(latestSettings, track, stamp) }
                            }
                        }

                        if (now - lastQueuePersistWallMs >= 5_000L) {
                            lastQueuePersistWallMs = now
                            persistQueueStateAsync()
                        }
                    }

                    maybeTriggerAutoMix(controller, pos, dur)
                }
                delay(120L)
            }
        }
    }

    private fun resolveTransitionSpecForPair(
        currentIndex: Int,
        currentTrack: Track,
        nextTrack: Track
    ): PlaylistTransitionSpec? {
        return customFragmentPlans[currentIndex]
            ?: activePlaylistTransitions[currentTrack.videoId to nextTrack.videoId]
    }

    private fun maybeTriggerAutoMix(controller: Player, pos: Long, dur: Long) {
        val snapshot = _state.value
        val currentTrack = snapshot.currentTrack ?: return
        if (snapshot.repeatMode == RepeatMode.ONE) return
        if (dur <= 18_000L || !controller.isPlaying) return
        val nextIndex = resolveUpcomingIndex(snapshot.queue, snapshot.currentIndex, snapshot.repeatMode)
            ?: return
        val nextTrack = snapshot.queue.getOrNull(nextIndex) ?: return

        val customSpec = resolveTransitionSpecForPair(snapshot.currentIndex, currentTrack, nextTrack)
        val crossfadeActive = autoMixEnabled || crossfadeSeconds != 0 || customSpec != null
        if (!crossfadeActive) return

        val key = "${snapshot.currentIndex}:${currentTrack.videoId}->${nextTrack.videoId}"

        val plan = AutoMixEngine.computeTransition(
            currentTrack = currentTrack,
            nextTrack = nextTrack,
            measuredDurationMs = dur,
            configuredOverlapSec = if (autoMixEnabled) autoMixOverlapSeconds else 0,
            crossfadeSeconds = crossfadeSeconds,
            customSpec = customSpec
        )

        val triggerAt = plan.triggerAtPositionMs
        if (triggerAt != null) {
            // Fragment mixer early-exit transition based on exact exit timestamp
            val msUntilTrigger = triggerAt - pos
            if (preCuedAutoMixKey != key && msUntilTrigger in 100L..10_000L) {
                preCuedAutoMixKey = key
                SonoraPlaybackBridge.requestPreCueIncoming(nextTrack, plan.incomingStartPositionMs)
            }
            if (triggeredAutoMixKey != key && pos >= triggerAt && pos < triggerAt + 3_500L) {
                triggeredAutoMixKey = key
                SonoraPlaybackBridge.requestAutoMixTransition(
                    AutoMixTransitionRequest(
                        id = System.nanoTime(),
                        outgoingTrack = currentTrack,
                        incomingTrack = nextTrack,
                        nextQueueIndex = nextIndex,
                        outgoingPositionMs = pos,
                        plan = plan,
                        incomingStartPositionMs = plan.incomingStartPositionMs,
                        curveType = plan.curveType
                    )
                )
            }
            return
        }

        val remaining = dur - pos
        // Pre-cue incoming track silently ~9 seconds before the crossfade point so it buffers in advance
        if (preCuedAutoMixKey != key && remaining in (plan.triggerBeforeEndMs + 100L)..(plan.triggerBeforeEndMs + 9_500L)) {
            preCuedAutoMixKey = key
            SonoraPlaybackBridge.requestPreCueIncoming(nextTrack, plan.incomingStartPositionMs)
        }

        if (triggeredAutoMixKey == key) return
        if (remaining in 450L..plan.triggerBeforeEndMs) {
            triggeredAutoMixKey = key
            SonoraPlaybackBridge.requestAutoMixTransition(
                AutoMixTransitionRequest(
                    id = System.nanoTime(),
                    outgoingTrack = currentTrack,
                    incomingTrack = nextTrack,
                    nextQueueIndex = nextIndex,
                    outgoingPositionMs = pos,
                    plan = plan,
                    incomingStartPositionMs = plan.incomingStartPositionMs,
                    curveType = plan.curveType
                )
            )
        }
    }

    private fun resolveUpcomingIndex(
        queue: List<Track>,
        currentIndex: Int,
        repeatMode: RepeatMode
    ): Int? {
        if (queue.isEmpty() || currentIndex !in queue.indices) return null
        val nextIdx = currentIndex + 1
        return when {
            nextIdx in queue.indices -> nextIdx
            repeatMode == RepeatMode.ALL && queue.size > 1 -> 0
            else -> null
        }
    }

    private fun resolveUpcomingTrack(
        queue: List<Track>,
        currentIndex: Int,
        repeatMode: RepeatMode
    ): Track? {
        val nextIdx = resolveUpcomingIndex(queue, currentIndex, repeatMode) ?: return null
        return queue.getOrNull(nextIdx)
    }

    private fun computeNextAutoMixStatus(
        enabled: Boolean,
        currentTrack: Track?,
        queue: List<Track>,
        currentIndex: Int,
        durationMs: Long
    ): String? {
        if (!enabled || currentTrack == null) return null
        val nextTrack = resolveUpcomingTrack(queue, currentIndex, _state.value.repeatMode)
            ?: return if (autoMixEnabled) "AutoMix activo" else "Fundido activo"
        val customSpec = resolveTransitionSpecForPair(currentIndex, currentTrack, nextTrack)
        return AutoMixEngine.computeTransition(
            currentTrack = currentTrack,
            nextTrack = nextTrack,
            measuredDurationMs = durationMs,
            configuredOverlapSec = if (autoMixEnabled) autoMixOverlapSeconds else 0,
            crossfadeSeconds = crossfadeSeconds,
            customSpec = customSpec
        ).transitionStyleLabel
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun Track.toMediaItem(resolvedStream: com.example.model.ResolvedStream? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artworkUrl.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .build()
        val builder = MediaItem.Builder()
            .setMediaId(videoId)
            .setMediaMetadata(metadata)

        if (resolvedStream != null) {
            builder.setUri(Uri.parse(resolvedStream.url))
            val isHls = resolvedStream.mimeType.contains("mpegURL", ignoreCase = true) ||
                resolvedStream.url.contains(".m3u8", ignoreCase = true)
            if (isHls) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            } else {
                val baseMime = resolvedStream.mimeType.substringBefore(";").trim()
                if (baseMime.isNotBlank() && baseMime != "audio/*") {
                    builder.setMimeType(baseMime)
                }
                val isHttp = resolvedStream.url.startsWith("http", ignoreCase = true)
                if (!isLocal && isHttp && videoId.isNotBlank()) {
                    val altSuffix = resolvedStream.sourceVideoId?.takeIf { it.isNotBlank() && it != videoId }
                    val cacheKey = if (altSuffix != null) {
                        "sonora_audio_${videoId}_alt_$altSuffix"
                    } else {
                        "sonora_audio_$videoId"
                    }
                    builder.setCustomCacheKey(cacheKey)
                }
            }
        } else if (!localUri.isNullOrBlank()) {
            builder.setUri(Uri.parse(localUri))
        } else {
            builder.setUri(Uri.parse("sonora://$videoId"))
        }
        return builder.build()
    }

    private fun buildStatsForNerds(
        track: Track,
        telemetry: LiveAudioTelemetry,
        streamOverride: ResolvedStream? = null
    ): StatsForNerdsData {
        val stream = streamOverride ?: streamResolver.getCachedStream(track.videoId)
        val sourceId = streamResolver.getCachedSourceId(track.videoId)
        val sourceLabel = when {
            track.isLocal || track.localUri != null -> "Archivo local (SAF)"
            stream?.codecLabel == "Descargada" -> "Descarga sin conexión"
            sourceId != null -> sourceId
            else -> "Streaming adaptativo"
        }
        val host = when {
            track.isLocal || track.localUri != null -> "Almacenamiento del dispositivo"
            stream != null -> runCatching { URI(stream.url).host }.getOrNull() ?: "--"
            else -> "--"
        }
        val bitrateKbps = when {
            telemetry.bitrateBps > 0 -> telemetry.bitrateBps / 1000
            stream != null && stream.bitrate > 0 -> stream.bitrate / 1000
            else -> 0
        }
        val depthLabel = when (telemetry.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> "16-bit PCM"
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit PCM"
            C.ENCODING_PCM_32BIT -> "32-bit PCM"
            C.ENCODING_PCM_FLOAT -> "32-bit Float"
            else -> "Estándar"
        }
        return StatsForNerdsData(
            videoId = track.videoId,
            sourceLabel = sourceLabel,
            codec = telemetry.codec.takeIf { it != "--" } ?: stream?.codecLabel ?: "--",
            mimeType = telemetry.sampleMimeType.takeIf { it != "--" } ?: stream?.mimeType ?: "--",
            bitrateKbps = bitrateKbps,
            sampleRateHz = telemetry.sampleRateHz,
            channelCount = telemetry.channelCount,
            bitDepthLabel = depthLabel,
            bufferedMs = telemetry.bufferedMs,
            bufferPercent = telemetry.bufferPercent,
            audioSessionId = telemetry.audioSessionId,
            playbackSpeed = telemetry.playbackSpeed,
            playbackPitch = telemetry.playbackPitch,
            streamHost = host
        )
    }

    private fun resolveEffectivePositionForSave(snapshot: PlaybackUiState): Long {
        val controller = mediaController
        val hasLoadedController = controller != null && controller.mediaItemCount > 0
        if (hasLoadedController) {
            return controller!!.currentPosition.coerceAtLeast(0L)
        }
        return (pendingRestoredPositionMs ?: _positionMs.value.takeIf { it > 0L } ?: snapshot.positionMs).coerceAtLeast(0L)
    }

    private fun persistQueueStateAsync() {
        val snapshot = _state.value
        if (snapshot.queue.isEmpty() || snapshot.currentTrack == null) return
        val (windowQueue, windowIdx) = normalizePersistedQueueWindow(snapshot.queue, snapshot.currentIndex)
        val payload = PersistedQueueState(
            queue = windowQueue,
            currentIndex = windowIdx,
            positionMs = resolveEffectivePositionForSave(snapshot),
            savedAtMs = System.currentTimeMillis(),
            wasPlaying = snapshot.isPlaying,
            sourcePlaylistId = activeSourcePlaylistId,
            origin = snapshot.playbackOrigin
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                queueStateFile.writeText(queueJson.encodeToString(payload))
            }
        }
    }

    private fun persistQueueStateSync() {
        val snapshot = _state.value
        if (snapshot.queue.isEmpty() || snapshot.currentTrack == null) return
        val (windowQueue, windowIdx) = normalizePersistedQueueWindow(snapshot.queue, snapshot.currentIndex)
        val payload = PersistedQueueState(
            queue = windowQueue,
            currentIndex = windowIdx,
            positionMs = resolveEffectivePositionForSave(snapshot),
            savedAtMs = System.currentTimeMillis(),
            wasPlaying = snapshot.isPlaying,
            sourcePlaylistId = activeSourcePlaylistId,
            origin = snapshot.playbackOrigin
        )
        runCatching {
            queueStateFile.writeText(queueJson.encodeToString(payload))
        }
    }

    private fun restorePersistedQueueOnStartup() {
        scope.launch(Dispatchers.IO) {
            val restored = runCatching {
                if (!queueStateFile.exists()) return@runCatching null
                val raw = queueStateFile.readText()
                if (raw.isBlank()) return@runCatching null
                queueJson.decodeFromString<PersistedQueueState>(raw)
            }.getOrNull() ?: return@launch

            if (restored.queue.isEmpty()) return@launch
            val safeIdx = restored.currentIndex.coerceIn(0, restored.queue.lastIndex)
            val track = restored.queue[safeIdx]
            val playlistId = restored.sourcePlaylistId
            val playlistTransitions = if (playlistId != null) {
                runCatching { repository.getPlaylistTransitionsOnce(playlistId) }
                    .getOrDefault(emptyList())
                    .associateBy { it.fromVideoId to it.toVideoId }
            } else {
                emptyMap()
            }

            withContext(Dispatchers.Main.immediate) {
                if (_state.value.currentTrack != null) return@withContext
                activeSourcePlaylistId = playlistId
                activePlaylistTransitions = playlistTransitions
                val restoredPos = restored.positionMs.coerceAtLeast(0L)
                pendingRestoredPositionMs = restoredPos
                _positionMs.value = restoredPos
                streamResolver.registerTracksMetadata(restored.queue)
                _state.update {
                    it.copy(
                        currentTrack = track,
                        queue = restored.queue,
                        currentIndex = safeIdx,
                        positionMs = restoredPos,
                        durationMs = track.durationMs,
                        isPlaying = false,
                        isBuffering = false,
                        codecLabel = if (track.isLocal || track.localUri != null) "Audio Local" else "Pausado",
                        statsForNerds = buildStatsForNerds(track, SonoraPlaybackBridge.liveTelemetry.value),
                        playbackOrigin = restored.origin,
                        autoMixStatus = computeNextAutoMixStatus(
                            enabled = autoMixEnabled || crossfadeSeconds != 0 || playlistTransitions.isNotEmpty(),
                            currentTrack = track,
                            queue = restored.queue,
                            currentIndex = safeIdx,
                            durationMs = track.durationMs
                        )
                    )
                }
                SonoraMediaWidgetProvider.updateWidgets(
                    context = context,
                    title = track.title,
                    artist = track.artist,
                    artworkUrl = track.artworkUrl,
                    isPlaying = false
                )
                extractArtworkColors(track.artworkUrl)
                val lyricsData = repository.getTrackLyrics(track, null)
                if (_state.value.currentTrack?.videoId == track.videoId) {
                    _state.update { it.copy(lyrics = lyricsData, isLoadingLyrics = false) }
                }
            }
        }
    }

    companion object {
        const val ALTERNATE_VERSION_NOTICE = "Reproduciendo otra versión de esta canción"

        fun normalizePersistedQueueWindow(
            queue: List<Track>,
            currentIndex: Int,
            maxSize: Int = 120
        ): Pair<List<Track>, Int> {
            if (queue.isEmpty()) return emptyList<Track>() to 0
            val safeIdx = currentIndex.coerceIn(0, queue.lastIndex)
            if (queue.size <= maxSize) return queue to safeIdx
            val half = maxSize / 2
            var start = (safeIdx - half).coerceAtLeast(0)
            var end = start + maxSize
            if (end > queue.size) {
                end = queue.size
                start = (end - maxSize).coerceAtLeast(0)
            }
            return queue.subList(start, end) to (safeIdx - start)
        }
    }
}
