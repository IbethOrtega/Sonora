package com.example.player

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.SonoraApplication
import com.example.data.local.LocalMusicRepository
import com.example.data.network.StreamResolver
import com.example.model.AudioOutputRoute
import com.example.widget.SonoraMediaWidgetProvider
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
import kotlin.math.hypot

class SonoraPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var audioEffectsController: AudioEffectsController? = null
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeJob: Job? = null
    private var primaryPlayerRef: ExoPlayer? = null
    private var preCuedVideoId: String? = null
    private var preCuedStartMs: Long = 0L
    private var visualizer: Visualizer? = null
    private var visualizerSessionId: Int = 0
    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
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

        // Set Sonora's miniature emblem icon for system media controls & lockscreen badge
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply {
                    setSmallIcon(R.drawable.ic_sonora_notification)
                }
        )

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
            .setBufferDurationsMs(15_000, 50_000, 850, 1_600)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        primaryPlayerRef = player

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        val auxLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(6_000, 18_000, 500, 1_000)
            .build()
        val auxPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(auxLoadControl)
            .setAudioAttributes(audioAttributes, false)
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
        attachVisualizerSafe(player.audioSessionId)
        setupAudioRouteObserver(effects)

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
                attachVisualizerSafe(audioSessionId)
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
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    SonoraPlaybackBridge.updateVisualizerBars(List(24) { 0f })
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    SonoraPlaybackBridge.updateVisualizerBars(List(24) { 0f })
                } else {
                    attachVisualizerSafe(player.audioSessionId)
                }
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
                    preCueIncomingTrack(auxPlayer, preCue.incomingTrack.videoId, preCue.startPositionMs)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = primaryPlayerRef
        if (intent != null && player != null) {
            when (intent.action) {
                ACTION_WIDGET_PLAY_PAUSE -> {
                    if (player.mediaItemCount > 0) {
                        if (player.isPlaying) player.pause() else player.play()
                        SonoraMediaWidgetProvider.requestRefreshFromService(this, player.isPlaying)
                    } else {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                        )
                    }
                }
                ACTION_WIDGET_NEXT -> {
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        player.play()
                    }
                }
                ACTION_WIDGET_PREV -> {
                    if (player.currentPosition > 3500L) {
                        player.seekTo(0L)
                    } else if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun setupAudioRouteObserver(effects: AudioEffectsController) {
        val mgr = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = mgr
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                effects.onAudioRouteChanged(detectActiveRoute(mgr))
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                effects.onAudioRouteChanged(detectActiveRoute(mgr))
            }
        }
        audioDeviceCallback = callback
        runCatching {
            mgr.registerAudioDeviceCallback(callback, null)
            effects.onAudioRouteChanged(detectActiveRoute(mgr))
        }
    }

    private fun detectActiveRoute(mgr: AudioManager): AudioOutputRoute {
        val outputs = runCatching {
            mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull().orEmpty()

        val hasBt = outputs.any { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET || dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        if (hasBt) return AudioOutputRoute.BLUETOOTH

        val hasWired = outputs.any { dev ->
            dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        return if (hasWired) AudioOutputRoute.WIRED else AudioOutputRoute.SPEAKER
    }

    private fun attachVisualizerSafe(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == visualizerSessionId) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        releaseVisualizer()
        runCatching {
            val vis = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            val targetSize = range.getOrNull(1)?.coerceAtMost(512) ?: 256
            vis.captureSize = targetSize
            vis.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 8) return
                        val barCount = 24
                        val step = ((fft.size / 2) / barCount).coerceAtLeast(1)
                        val bars = MutableList(barCount) { idx ->
                            val i = (idx * step * 2).coerceIn(2, fft.size - 2)
                            val re = fft[i].toDouble()
                            val im = fft[i + 1].toDouble()
                            val mag = hypot(re, im)
                            (mag / 48.0).toFloat().coerceIn(0.05f, 1f)
                        }
                        SonoraPlaybackBridge.updateVisualizerBars(bars)
                    }
                },
                (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(10000),
                false,
                true
            )
            vis.enabled = true
            visualizer = vis
            visualizerSessionId = audioSessionId
        }
    }

    private fun releaseVisualizer() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        visualizerSessionId = 0
    }

    private fun preCueIncomingTrack(aux: ExoPlayer, videoId: String, startPositionMs: Long = 0L) {
        if (videoId.isBlank()) return
        if (preCuedVideoId == videoId && kotlin.math.abs(preCuedStartMs - startPositionMs) < 500L) return
        if (crossfadeJob?.isActive == true) return
        preCuedVideoId = videoId
        preCuedStartMs = startPositionMs.coerceAtLeast(0L)
        runCatching {
            val incomingItem = MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(Uri.parse("sonora://$videoId"))
                .build()
            aux.pause()
            aux.stop()
            aux.clearMediaItems()
            aux.volume = 0f
            aux.setMediaItem(incomingItem, startPositionMs.coerceAtLeast(0L))
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
            val overlapMs = request.plan.overlapMs.coerceIn(1_500L, 16_000L)
            val entryMs = request.incomingStartPositionMs.coerceAtLeast(0L)
            var handedOffToPrimary = false
            try {
                SonoraPlaybackBridge.updateAutoMixState(
                    active = true,
                    status = "Mezclando • ${request.plan.transitionStyleLabel}"
                )

                val incomingId = request.incomingTrack.videoId
                if (preCuedVideoId != incomingId || aux.currentMediaItem?.mediaId != incomingId) {
                    preCuedVideoId = incomingId
                    preCuedStartMs = entryMs
                    val incomingItem = MediaItem.Builder()
                        .setMediaId(incomingId)
                        .setUri(Uri.parse("sonora://$incomingId"))
                        .build()
                    aux.pause()
                    aux.stop()
                    aux.clearMediaItems()
                    aux.volume = 0f
                    aux.setMediaItem(incomingItem, entryMs)
                    aux.playWhenReady = false
                    aux.prepare()
                } else if (kotlin.math.abs(aux.currentPosition - entryMs) > 400L) {
                    aux.seekTo(entryMs)
                }

                val auxReady = withTimeoutOrNull(2_600L) {
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

                if (kotlin.math.abs(aux.currentPosition - entryMs) > 400L) {
                    aux.seekTo(entryMs)
                }
                aux.volume = 0f
                aux.play()

                val startTimeMs = System.currentTimeMillis()
                val stepMs = 35L
                while (isActive) {
                    val activeId = primary.currentMediaItem?.mediaId
                    if (!primary.playWhenReady || (activeId != null && activeId != request.outgoingTrack.videoId && primary.playbackState != Player.STATE_ENDED)) {
                        break
                    }
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    val progress = (elapsed.toFloat() / overlapMs.toFloat()).coerceIn(0f, 1f)
                    val (outGain, inGain) = AutoMixEngine.curveGains(progress, request.curveType)

                    if (primary.playbackState != Player.STATE_ENDED) {
                        activeFadeMultiplier = outGain
                        primary.volume = (activeFadeMultiplier * currentDuckMultiplier).coerceIn(0f, 1f)
                    }
                    aux.volume = (inGain * currentDuckMultiplier).coerceIn(0f, 1f)

                    if (progress >= 1f) break
                    delay(stepMs)
                }

                if (isActive && aux.isPlaying) {
                    handedOffToPrimary = true
                    activeFadeMultiplier = 0f
                    primary.volume = 0f

                    val handoffPos = aux.currentPosition.coerceAtLeast(entryMs) + 45L
                    if (request.nextQueueIndex in 0 until primary.mediaItemCount) {
                        primary.seekTo(request.nextQueueIndex, handoffPos)
                    } else if (primary.hasNextMediaItem()) {
                        primary.seekToNextMediaItem()
                        primary.seekTo(handoffPos)
                    }
                    primary.play()

                    withTimeoutOrNull(1_400L) {
                        while (isActive && primary.playbackState != Player.STATE_READY) {
                            delay(25L)
                        }
                    }

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
                preCuedStartMs = 0L
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
        releaseVisualizer()
        audioDeviceCallback?.let { cb ->
            runCatching { audioManager?.unregisterAudioDeviceCallback(cb) }
        }
        audioDeviceCallback = null
        audioManager = null
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

    companion object {
        const val ACTION_WIDGET_PLAY_PAUSE = "com.example.sonora.action.WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.example.sonora.action.WIDGET_NEXT"
        const val ACTION_WIDGET_PREV = "com.example.sonora.action.WIDGET_PREV"
    }
}
