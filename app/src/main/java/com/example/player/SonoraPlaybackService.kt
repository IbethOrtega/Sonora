package com.example.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.SonoraApplication
import com.example.data.local.LocalMusicRepository
import com.example.data.network.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class SonoraPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var audioEffectsController: AudioEffectsController? = null
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeJob: Job? = null
    private var primaryPlayerRef: ExoPlayer? = null
    private var preCuedVideoId: String? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var currentDuckMultiplier: Float = 1.0f

    @Volatile
    private var activeFadeMultiplier: Float = 1.0f

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as SonoraApplication
        val streamResolver = app.streamResolver
        val offlineManager = app.offlineDownloadManager
        val localMusicRepo = app.localMusicRepository

        val httpFactory = OkHttpDataSource.Factory(app.okHttpClient)
            .setUserAgent(StreamResolver.BROWSER_USER_AGENT)
        val upstreamFactory = DefaultDataSource.Factory(this, httpFactory)

        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            when (uri.scheme) {
                "content", "file" -> dataSpec
                "sonora" -> {
                    val videoId = uri.host ?: uri.lastPathSegment
                        ?: throw IOException("ID de pista inválido")
                    val localSafTrack = if (videoId.startsWith(LocalMusicRepository.LOCAL_ID_PREFIX)) {
                        runBlocking { localMusicRepo.getLocalTrack(videoId) }
                    } else null

                    if (localSafTrack != null) {
                        dataSpec.buildUpon()
                            .setUri(Uri.parse(localSafTrack.contentUri))
                            .build()
                    } else {
                        val localFile = runBlocking { offlineManager.getLocalAudioFile(videoId) }
                        if (localFile != null) {
                            dataSpec.buildUpon()
                                .setUri(Uri.fromFile(localFile))
                                .build()
                        } else {
                            val resolved = runBlocking {
                                streamResolver.resolveAudioStream(videoId)
                            }.getOrElse { err ->
                                throw IOException(err.message ?: "Error al obtener audio", err)
                            }
                            val mergedHeaders = dataSpec.httpRequestHeaders.toMutableMap().apply {
                                put("User-Agent", resolved.userAgent)
                            }
                            dataSpec.buildUpon()
                                .setUri(Uri.parse(resolved.url))
                                .setHttpRequestHeaders(mergedHeaders)
                                .build()
                        }
                    }
                }
                else -> {
                    val customUa = streamResolver.getUserAgentForUrl(uri.toString())
                        ?: uri.host?.let { streamResolver.getUserAgentForHost(it) }
                    if (customUa != null) {
                        val mergedHeaders = dataSpec.httpRequestHeaders.toMutableMap().apply {
                            put("User-Agent", customUa)
                        }
                        dataSpec.buildUpon()
                            .setHttpRequestHeaders(mergedHeaders)
                            .build()
                    } else {
                        dataSpec
                    }
                }
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(resolvingFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                850,
                1_600
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        primaryPlayerRef = player

        // Ensure audio-only decoding even if a progressive video+audio stream fallback is used
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        // Auxiliary player for seamless equal-power AutoMix outgoing track crossfade
        val auxLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(6_000, 18_000, 500, 1_000)
            .build()
        val auxPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(auxLoadControl)
            .setAudioAttributes(audioAttributes, false) // do NOT steal audio focus from primary
            .build().apply {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            }
        crossfadePlayer = auxPlayer

        val effects = AudioEffectsController(app.preferences)
        audioEffectsController = effects
        effects.attachToSession(player.audioSessionId)

        fun pushTelemetrySnapshot() {
            val fmt = player.audioFormat
            val bufferedAhead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            val params = player.playbackParameters
            SonoraPlaybackBridge.updateLiveTelemetry(
                LiveAudioTelemetry(
                    codec = fmt?.codecs?.takeIf { it.isNotBlank() }
                        ?: fmt?.sampleMimeType?.substringAfter("/")?.uppercase()
                        ?: "--",
                    sampleMimeType = fmt?.sampleMimeType ?: "--",
                    bitrateBps = fmt?.bitrate?.takeIf { it > 0 } ?: 0,
                    sampleRateHz = fmt?.sampleRate?.takeIf { it > 0 } ?: 0,
                    channelCount = fmt?.channelCount?.takeIf { it > 0 } ?: 0,
                    pcmEncoding = fmt?.pcmEncoding ?: 0,
                    audioSessionId = player.audioSessionId,
                    bufferedMs = bufferedAhead,
                    bufferPercent = player.bufferedPercentage.coerceIn(0, 100),
                    playbackSpeed = params.speed,
                    playbackPitch = params.pitch
                )
            )
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                effects.attachToSession(audioSessionId)
                pushTelemetrySnapshot()
            }

            override fun onTracksChanged(tracks: Tracks) {
                pushTelemetrySnapshot()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                pushTelemetrySnapshot()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                pushTelemetrySnapshot()
            }
        })

        serviceScope.launch {
            while (isActive) {
                if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                    pushTelemetrySnapshot()
                }
                delay(900L)
            }
        }

        serviceScope.launch {
            app.preferences.settingsFlow
                .map { it.skipSilence }
                .distinctUntilChanged()
                .collect { skipSilence ->
                    player.skipSilenceEnabled = skipSilence
                }
        }

        serviceScope.launch {
            app.preferences.settingsFlow
                .map { it.playbackSpeed to it.playbackPitch }
                .distinctUntilChanged()
                .collect { (speed, pitch) ->
                    val params = PlaybackParameters(
                        speed.coerceIn(0.5f, 2.0f),
                        pitch.coerceIn(0.5f, 2.0f)
                    )
                    player.playbackParameters = params
                    auxPlayer.playbackParameters = params
                    pushTelemetrySnapshot()
                }
        }

        serviceScope.launch {
            app.preferences.settingsFlow
                .map { it.spatialAudioEnabled || it.dolbyAtmosEnabled }
                .distinctUntilChanged()
                .collect { spatialEnabled ->
                    val behavior = if (spatialEnabled) {
                        C.SPATIALIZATION_BEHAVIOR_AUTO
                    } else {
                        C.SPATIALIZATION_BEHAVIOR_NEVER
                    }
                    val updatedAttrs = AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .setSpatializationBehavior(behavior)
                        .build()
                    player.setAudioAttributes(updatedAttrs, true)
                    auxPlayer.setAudioAttributes(updatedAttrs, false)
                }
        }

        serviceScope.launch {
            SonoraPlaybackBridge.volumeMultiplier.collect { mult ->
                currentDuckMultiplier = mult.coerceIn(0f, 1f)
                player.volume = (currentDuckMultiplier * activeFadeMultiplier).coerceIn(0f, 1f)
            }
        }

        serviceScope.launch {
            SonoraPlaybackBridge.pendingPreCue.collect { preCue ->
                if (preCue != null) {
                    SonoraPlaybackBridge.consumePreCue(preCue.id)
                    preCueIncomingTrack(auxPlayer, preCue.incomingTrack.videoId)
                }
            }
        }

        serviceScope.launch {
            SonoraPlaybackBridge.pendingTransition.collect { request ->
                if (request != null) {
                    SonoraPlaybackBridge.consumePendingTransition(request.id)
                    performAutoMixCrossfade(player, auxPlayer, request)
                }
            }
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    private fun preCueIncomingTrack(aux: ExoPlayer, videoId: String) {
        if (videoId.isBlank() || preCuedVideoId == videoId) return
        if (crossfadeJob?.isActive == true) return
        preCuedVideoId = videoId
        runCatching {
            val incomingItem = MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(Uri.parse("sonora://$videoId"))
                .build()
            aux.pause()
            aux.stop()
            aux.clearMediaItems()
            aux.volume = 0f
            aux.setMediaItem(incomingItem, 0L)
            aux.playWhenReady = false
            aux.prepare()
        }
    }

    private fun performAutoMixCrossfade(
        primary: ExoPlayer,
        aux: ExoPlayer,
        request: AutoMixTransitionRequest
    ) {
        crossfadeJob?.cancel()
        crossfadeJob = serviceScope.launch {
            val overlapMs = request.plan.overlapMs.coerceIn(2_000L, 12_500L)
            var handedOffToPrimary = false
            try {
                SonoraPlaybackBridge.updateAutoMixState(
                    active = true,
                    status = "Mezclando • ${request.plan.transitionStyleLabel}"
                )

                val incomingId = request.incomingTrack.videoId
                if (preCuedVideoId != incomingId || aux.currentMediaItem?.mediaId != incomingId) {
                    preCuedVideoId = incomingId
                    val incomingItem = MediaItem.Builder()
                        .setMediaId(incomingId)
                        .setUri(Uri.parse("sonora://$incomingId"))
                        .build()
                    aux.pause()
                    aux.stop()
                    aux.clearMediaItems()
                    aux.volume = 0f
                    aux.setMediaItem(incomingItem, 0L)
                    aux.playWhenReady = false
                    aux.prepare()
                }

                // Wait for incoming stream to buffer BEFORE dimming outgoing track
                val auxReady = withTimeoutOrNull(2_200L) {
                    while (isActive &&
                        aux.playbackState != Player.STATE_READY &&
                        aux.playbackState != Player.STATE_ENDED
                    ) {
                        delay(25L)
                    }
                    aux.playbackState == Player.STATE_READY
                } ?: false

                if (!auxReady || !primary.playWhenReady) {
                    return@launch
                }

                if (aux.currentPosition > 120L) {
                    aux.seekTo(0L)
                }
                aux.volume = 0f
                aux.play()

                val startTimeMs = System.currentTimeMillis()
                val stepMs = 35L
                while (isActive) {
                    // Abort cleanly if user manually skipped/paused during blend
                    val activeId = primary.currentMediaItem?.mediaId
                    if (!primary.playWhenReady || (activeId != null && activeId != request.outgoingTrack.videoId && primary.playbackState != Player.STATE_ENDED)) {
                        break
                    }
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    val progress = (elapsed.toFloat() / overlapMs.toFloat()).coerceIn(0f, 1f)
                    val (outGain, inGain) = AutoMixEngine.equalPowerGains(progress)

                    if (primary.playbackState != Player.STATE_ENDED) {
                        activeFadeMultiplier = outGain
                        primary.volume = (activeFadeMultiplier * currentDuckMultiplier).coerceIn(0f, 1f)
                    }
                    aux.volume = (inGain * currentDuckMultiplier).coerceIn(0f, 1f)

                    if (progress >= 1f) break
                    delay(stepMs)
                }

                // Handoff incoming track back to primary MediaSession player seamlessly at exact position
                if (isActive && aux.isPlaying) {
                    handedOffToPrimary = true
                    activeFadeMultiplier = 0f
                    primary.volume = 0f

                    val handoffPos = aux.currentPosition.coerceAtLeast(0L) + 45L
                    if (request.nextQueueIndex in 0 until primary.mediaItemCount) {
                        primary.seekTo(request.nextQueueIndex, handoffPos)
                    } else if (primary.hasNextMediaItem()) {
                        primary.seekToNextMediaItem()
                        primary.seekTo(handoffPos)
                    }
                    primary.play()

                    // Keep aux playing until primary buffers to exact handoff point
                    withTimeoutOrNull(1_400L) {
                        while (isActive && primary.playbackState != Player.STATE_READY) {
                            delay(25L)
                        }
                    }

                    // Smooth 200ms micro-handoff from aux to primary so there's zero pop/gap
                    val microSteps = 5
                    for (i in 1..microSteps) {
                        val frac = i.toFloat() / microSteps.toFloat()
                        activeFadeMultiplier = frac
                        primary.volume = (activeFadeMultiplier * currentDuckMultiplier).coerceIn(0f, 1f)
                        aux.volume = ((1f - frac) * currentDuckMultiplier).coerceIn(0f, 1f)
                        delay(40L)
                    }
                }
            } finally {
                runCatching {
                    aux.pause()
                    aux.stop()
                    aux.clearMediaItems()
                }
                preCuedVideoId = null
                activeFadeMultiplier = 1.0f
                primary.volume = currentDuckMultiplier
                if (handedOffToPrimary && !primary.playWhenReady) {
                    primary.play()
                }
                SonoraPlaybackBridge.updateAutoMixState(active = false, status = null)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        crossfadeJob?.cancel()
        runCatching {
            crossfadePlayer?.release()
            crossfadePlayer = null
        }
        primaryPlayerRef = null
        audioEffectsController?.releaseEffects()
        audioEffectsController = null
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
