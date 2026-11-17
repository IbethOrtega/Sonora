package com.example.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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

        val httpFactory = OkHttpDataSource.Factory(app.okHttpClient)
            .setUserAgent(StreamResolver.BROWSER_USER_AGENT)
        val upstreamFactory = DefaultDataSource.Factory(this, httpFactory)

        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == "sonora") {
                val videoId = uri.host ?: uri.lastPathSegment
                    ?: throw IOException("ID de pista inválido")
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
            } else {
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

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                effects.attachToSession(audioSessionId)
            }
        })

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

    private fun performAutoMixCrossfade(
        primary: ExoPlayer,
        aux: ExoPlayer,
        request: AutoMixTransitionRequest
    ) {
        crossfadeJob?.cancel()
        crossfadeJob = serviceScope.launch {
            val overlapMs = request.plan.overlapMs.coerceIn(2_000L, 9_000L)
            try {
                SonoraPlaybackBridge.updateAutoMixState(
                    active = true,
                    status = "Mezclando • ${request.plan.transitionStyleLabel}"
                )

                // Stage outgoing track on auxPlayer at current position using cached sonora:// stream
                val outgoingItem = MediaItem.Builder()
                    .setMediaId(request.outgoingTrack.videoId)
                    .setUri(Uri.parse("sonora://${request.outgoingTrack.videoId}"))
                    .build()

                aux.stop()
                aux.clearMediaItems()
                aux.volume = currentDuckMultiplier
                aux.setMediaItem(outgoingItem, request.outgoingPositionMs.coerceAtLeast(0L))
                aux.prepare()
                aux.play()

                // Wait briefly for auxPlayer to cue up so outgoing track continues seamlessly
                val auxReady = withTimeoutOrNull(950L) {
                    while (isActive && aux.playbackState != Player.STATE_READY && aux.playbackState != Player.STATE_ENDED) {
                        delay(25L)
                    }
                    aux.playbackState == Player.STATE_READY
                } ?: false

                // Advance primary MediaSession player to the incoming track quietly
                activeFadeMultiplier = 0.02f
                primary.volume = (currentDuckMultiplier * activeFadeMultiplier).coerceIn(0f, 1f)
                if (primary.hasNextMediaItem()) {
                    primary.seekToNextMediaItem()
                } else if (primary.mediaItemCount > 1 && primary.repeatMode == Player.REPEAT_MODE_ALL) {
                    primary.seekTo(0, 0L)
                }
                primary.play()

                val startTimeMs = System.currentTimeMillis()
                val stepMs = 40L
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    val progress = (elapsed.toFloat() / overlapMs.toFloat()).coerceIn(0f, 1f)
                    val (outGain, inGain) = AutoMixEngine.equalPowerGains(progress)
                    if (auxReady) {
                        aux.volume = (outGain * currentDuckMultiplier).coerceIn(0f, 1f)
                    }
                    activeFadeMultiplier = inGain.coerceAtLeast(0.02f)
                    primary.volume = (activeFadeMultiplier * currentDuckMultiplier).coerceIn(0f, 1f)

                    if (progress >= 1f) break
                    delay(stepMs)
                }
            } finally {
                runCatching {
                    aux.pause()
                    aux.stop()
                    aux.clearMediaItems()
                }
                activeFadeMultiplier = 1.0f
                primary.volume = currentDuckMultiplier
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
