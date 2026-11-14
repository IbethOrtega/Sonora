package com.example.player

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.network.StreamResolver
import com.example.data.repository.MusicRepository
import com.example.model.RepeatMode
import com.example.model.Track
import com.example.ui.theme.SonoraCrimson
import com.example.ui.theme.SonoraPlum
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
    val lyrics: String? = null,
    val isLoadingLyrics: Boolean = false,
    val errorMessage: String? = null,
    val ambientPrimary: Color = SonoraCrimson,
    val ambientSecondary: Color = SonoraPlum
)

class PlayerController(
    private val context: Context,
    private val repository: MusicRepository,
    private val streamResolver: StreamResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var progressJob: Job? = null
    private var metadataJob: Job? = null
    private var paletteJob: Job? = null
    private var pendingAction: (() -> Unit)? = null
    private var autoRetriedVideoId: String? = null

    private val imageLoader by lazy { ImageLoader(context) }

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressLoop() else stopProgressLoop()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            val controller = mediaController
            val duration = controller?.duration?.takeIf { it > 0L } ?: _state.value.currentTrack?.durationMs ?: 0L
            if (playbackState == Player.STATE_READY) {
                autoRetriedVideoId = null
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
            val index = controller.currentMediaItemIndex
            val queue = _state.value.queue
            val track = queue.getOrNull(index)
            if (track != null) {
                onActiveTrackChanged(track, index)
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
            val failedTrack = _state.value.currentTrack
            if (failedTrack != null) {
                streamResolver.invalidate(failedTrack.videoId)
                if (autoRetriedVideoId != failedTrack.videoId) {
                    autoRetriedVideoId = failedTrack.videoId
                    _state.update { it.copy(isBuffering = true, errorMessage = null) }
                    mediaController?.let { controller ->
                        controller.prepare()
                        controller.play()
                        return
                    }
                }
            }
            _state.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "No se pudo reproducir este tema. Toca reintentar o elige otro."
                )
            }
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(context, ComponentName(context, SonoraPlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching {
                    val controller = future.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                    syncFromController(controller)
                    pendingAction?.invoke()
                    pendingAction = null
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    fun release() {
        stopProgressLoop()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    fun playTrack(track: Track, contextQueue: List<Track> = emptyList()) {
        val queue = if (contextQueue.isNotEmpty()) {
            if (contextQueue.any { it.videoId == track.videoId }) contextQueue else listOf(track) + contextQueue
        } else {
            listOf(track)
        }
        val startIndex = queue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
        playQueue(queue, startIndex)
    }

    fun playQueue(queue: List<Track>, startIndex: Int = 0) {
        if (queue.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, queue.lastIndex)
        val targetTrack = queue[safeIndex]

        _state.update {
            it.copy(
                currentTrack = targetTrack,
                queue = queue,
                currentIndex = safeIndex,
                isBuffering = true,
                errorMessage = null,
                lyrics = null
            )
        }
        onActiveTrackChanged(targetTrack, safeIndex)

        val action = {
            val controller = mediaController
            if (controller != null) {
                val mediaItems = queue.map { it.toMediaItem() }
                controller.setMediaItems(mediaItems, safeIndex, 0L)
                controller.prepare()
                controller.play()
            }
        }

        if (mediaController != null) {
            action()
        } else {
            pendingAction = action
            connect()
        }
    }

    fun addToQueue(track: Track) {
        val currentQueue = _state.value.queue
        if (currentQueue.isEmpty()) {
            playTrack(track)
            return
        }
        val updated = currentQueue + track
        _state.update { it.copy(queue = updated) }
        mediaController?.addMediaItem(track.toMediaItem())
    }

    fun skipToQueueIndex(index: Int) {
        val queue = _state.value.queue
        val track = queue.getOrNull(index) ?: return
        _state.update {
            it.copy(
                currentTrack = track,
                currentIndex = index,
                isBuffering = true,
                errorMessage = null
            )
        }
        onActiveTrackChanged(track, index)
        mediaController?.let { controller ->
            if (index < controller.mediaItemCount) {
                controller.seekToDefaultPosition(index)
                controller.prepare()
                controller.play()
            } else {
                playQueue(queue, index)
            }
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.playbackState == Player.STATE_IDLE && _state.value.currentTrack != null) {
            controller.prepare()
            controller.play()
            return
        }
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun skipNext() {
        val controller = mediaController ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            controller.play()
        } else {
            val queue = _state.value.queue
            if (queue.isNotEmpty()) skipToQueueIndex(0)
        }
    }

    fun skipPrevious() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 3_500L) {
            controller.seekTo(0L)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
            controller.play()
        } else {
            controller.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        mediaController?.seekTo(positionMs)
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
        val queue = _state.value.queue.ifEmpty { listOf(track) }
        val index = _state.value.currentIndex.coerceIn(0, queue.lastIndex)
        playQueue(queue, index)
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun onActiveTrackChanged(track: Track, index: Int) {
        _state.update {
            it.copy(
                currentTrack = track,
                currentIndex = index,
                durationMs = track.durationMs.takeIf { d -> d > 0L } ?: it.durationMs,
                lyrics = null,
                isLoadingLyrics = true,
                errorMessage = null
            )
        }

        extractArtworkColors(track.artworkUrl)

        metadataJob?.cancel()
        metadataJob = scope.launch {
            repository.recordPlay(track)

            // Resolve stream badge asynchronously
            launch {
                streamResolver.resolveAudioStream(track.videoId).onSuccess { stream ->
                    _state.update { it.copy(codecLabel = "${stream.codecLabel} • Sin anuncios") }
                }
            }

            // Fetch Up Next radio queue if playing a single song, plus Lyrics
            val (upNext, lyricsBrowseId) = repository.getUpNextAndLyricsId(track.videoId)
            if (_state.value.queue.size <= 1 && upNext.isNotEmpty()) {
                val merged = (listOf(track) + upNext.filterNot { it.videoId == track.videoId }).distinctBy { it.videoId }
                _state.update { it.copy(queue = merged, currentIndex = 0) }
                mediaController?.let { controller ->
                    val extras = merged.drop(1).map { it.toMediaItem() }
                    if (extras.isNotEmpty() && controller.mediaItemCount == 1) {
                        controller.addMediaItems(extras)
                    }
                }
            }

            val lyricsText = if (!lyricsBrowseId.isNullOrBlank()) {
                repository.getLyrics(lyricsBrowseId)
            } else null

            _state.update {
                it.copy(
                    lyrics = lyricsText,
                    isLoadingLyrics = false
                )
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

    private fun syncFromController(controller: MediaController) {
        _state.update {
            it.copy(
                isPlaying = controller.isPlaying,
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                durationMs = controller.duration.takeIf { d -> d > 0L } ?: it.durationMs,
                shuffleEnabled = controller.shuffleModeEnabled
            )
        }
        if (controller.isPlaying) startProgressLoop()
    }

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val controller = mediaController
                if (controller != null) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val dur = controller.duration.takeIf { it > 0L }
                        ?: _state.value.currentTrack?.durationMs
                        ?: 0L
                    _state.update { it.copy(positionMs = pos, durationMs = dur) }
                }
                delay(400L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun Track.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artworkUrl.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .build()
        return MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(Uri.parse("sonora://$videoId"))
            .setMediaMetadata(metadata)
            .build()
    }
}
