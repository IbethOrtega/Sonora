package com.example.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.SonoraApplication
import com.example.data.network.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

class SonoraPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as SonoraApplication
        val streamResolver = app.streamResolver

        val upstreamFactory = OkHttpDataSource.Factory(app.okHttpClient)
            .setUserAgent(StreamResolver.BROWSER_USER_AGENT)

        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == "sonora") {
                val videoId = uri.host ?: uri.lastPathSegment
                    ?: throw IOException("ID de pista inválido")
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

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Ensure audio-only decoding even if a progressive video+audio stream fallback is used
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        serviceScope.launch {
            app.preferences.settingsFlow
                .map { it.skipSilence }
                .distinctUntilChanged()
                .collect { skipSilence ->
                    player.skipSilenceEnabled = skipSilence
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
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
