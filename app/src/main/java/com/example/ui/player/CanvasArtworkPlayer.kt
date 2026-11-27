package com.example.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.model.CanvasArtwork
import com.example.model.Track

@OptIn(UnstableApi::class)
@Composable
fun CanvasArtworkPlayer(
    track: Track,
    canvasArtwork: CanvasArtwork?,
    animatedArtworkEnabled: Boolean,
    allowMobileData: Boolean,
    preferPortrait: Boolean,
    isPlaying: Boolean,
    isPlayerActive: Boolean,
    ambientPrimary: Color,
    modifier: Modifier = Modifier,
    showStaticFallback: Boolean = true,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    showBorder: Boolean = true,
    showShadow: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isOnUnmeteredNetwork by remember(context) { mutableStateOf(checkUnmeteredNetwork(context)) }
    var isLifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(context, allowMobileData) {
        if (allowMobileData) {
            isOnUnmeteredNetwork = true
            return@DisposableEffect onDispose {}
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            isOnUnmeteredNetwork = true
            return@DisposableEffect onDispose {}
        }
        isOnUnmeteredNetwork = checkUnmeteredNetwork(context)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnUnmeteredNetwork = checkUnmeteredNetwork(context)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                isOnUnmeteredNetwork = isUnmeteredCapabilities(networkCapabilities)
            }

            override fun onLost(network: Network) {
                isOnUnmeteredNetwork = checkUnmeteredNetwork(context)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isLifecycleStarted = true
                Lifecycle.Event.ON_STOP -> isLifecycleStarted = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val matchedCanvas = remember(canvasArtwork, track.title, track.artist, track.album) {
        canvasArtwork?.takeIf { it.matches(track.title, track.artist, track.album) }
    }
    val initialStreamUrl = remember(matchedCanvas, preferPortrait) {
        matchedCanvas?.primaryStreamUrl(preferPortrait)
    }
    val backupStreamUrl = remember(matchedCanvas, initialStreamUrl) {
        listOfNotNull(
            matchedCanvas?.fallbackUrl,
            matchedCanvas?.videoUrl?.takeIf { it.isNotBlank() }
        ).firstOrNull { it != initialStreamUrl }
    }

    var activeStreamUrl by remember(initialStreamUrl) { mutableStateOf(initialStreamUrl) }
    val canStreamVideo = animatedArtworkEnabled &&
        !activeStreamUrl.isNullOrBlank() &&
        (allowMobileData || isOnUnmeteredNetwork)

    Box(modifier = modifier) {
        if (showStaticFallback) {
            AnimatedCoverArtwork(
                url = track.artworkUrl,
                contentDescription = track.title,
                isPlaying = isPlaying,
                ambientPrimary = ambientPrimary,
                cornerRadius = cornerRadius,
                showBorder = showBorder,
                showShadow = showShadow,
                modifier = Modifier.fillMaxSize()
            )
        }

        val currentUrl = activeStreamUrl
        if (canStreamVideo && !currentUrl.isNullOrBlank()) {
            var isFirstFrameReady by remember(currentUrl) { mutableStateOf(false) }
            val exoPlayer = remember(context, currentUrl) {
                val cacheFactory = CanvasCache.getCacheDataSourceFactory(context)
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
                    .build()
                    .apply {
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ALL
                        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                        setMediaItem(MediaItem.fromUri(currentUrl))
                        prepare()
                    }
            }

            DisposableEffect(exoPlayer, backupStreamUrl) {
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            isFirstFrameReady = true
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        isFirstFrameReady = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!backupStreamUrl.isNullOrBlank() && activeStreamUrl != backupStreamUrl) {
                            activeStreamUrl = backupStreamUrl
                        } else {
                            activeStreamUrl = null
                        }
                    }
                }
                exoPlayer.addListener(listener)
                onDispose {
                    exoPlayer.removeListener(listener)
                    runCatching { exoPlayer.release() }
                }
            }

            val shouldPlay = isPlaying && isPlayerActive && isLifecycleStarted
            LaunchedEffect(exoPlayer, shouldPlay) {
                exoPlayer.playWhenReady = shouldPlay
            }

            val videoAlpha by animateFloatAsState(
                targetValue = if (isFirstFrameReady) 1f else 0f,
                animationSpec = tween(durationMillis = 360),
                label = "canvas_video_fade"
            )

            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        exoPlayer.setVideoTextureView(this)
                    }
                },
                update = { textureView ->
                    exoPlayer.setVideoTextureView(textureView)
                },
                onRelease = { textureView ->
                    runCatching { exoPlayer.clearVideoTextureView(textureView) }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(videoAlpha)
            )
        }
    }
}

private fun checkUnmeteredNetwork(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val active = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(active) ?: return false
    return isUnmeteredCapabilities(caps)
}

private fun isUnmeteredCapabilities(caps: NetworkCapabilities): Boolean {
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
