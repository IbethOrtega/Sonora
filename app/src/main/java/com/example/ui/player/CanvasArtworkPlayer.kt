package com.example.ui.player

import android.content.Context
import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.ui.theme.buildCanvasBottomFadeStops
import com.example.ui.theme.sampleBitmapBottomStripAverageRgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    showShadow: Boolean = true,
    fadeBottomFraction: Float = 0f,
    pauseDarkenAlpha: Float = 0.15f,
    onBottomSampleRgb: (Int?) -> Unit = {},
    onActiveVideoStateChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnBottomSampleRgb by rememberUpdatedState(onBottomSampleRgb)
    val latestOnActiveVideoChanged by rememberUpdatedState(onActiveVideoStateChanged)

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

    val targetPauseDarken = if (!isPlaying && pauseDarkenAlpha > 0f) pauseDarkenAlpha.coerceIn(0f, 0.45f) else 0f
    val animatedPauseDarken by animateFloatAsState(
        targetValue = targetPauseDarken,
        animationSpec = tween(durationMillis = 280),
        label = "canvas_pause_darken"
    )

    val fadeStops = remember(fadeBottomFraction) {
        if (fadeBottomFraction > 0.01f) {
            buildCanvasBottomFadeStops(fadeBottomFraction = fadeBottomFraction, stopCount = 16)
                .map { (pos, alpha) -> pos to Color.Black.copy(alpha = alpha) }
                .toTypedArray()
        } else {
            null
        }
    }

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
                fadeBottomFraction = fadeBottomFraction,
                pausedDarkenAlpha = animatedPauseDarken,
                modifier = Modifier.fillMaxSize()
            )
        }

        val currentUrl = activeStreamUrl
        if (canStreamVideo && !currentUrl.isNullOrBlank()) {
            var isFirstFrameReady by remember(currentUrl) { mutableStateOf(false) }
            var textureViewRef by remember(currentUrl) { mutableStateOf<TextureView?>(null) }

            val exoPlayer = remember(context, currentUrl) {
                val cacheFactory = CanvasCache.getCacheDataSourceFactory(context)
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
                    .build()
                    .apply {
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ALL
                        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                        val item = MediaItem.fromUri(currentUrl)
                        // Queue two items with REPEAT_MODE_ALL to pre-cue the loop boundary without gap/black flash
                        setMediaItems(listOf(item, item))
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
                    latestOnActiveVideoChanged(false)
                    latestOnBottomSampleRgb(null)
                    runCatching { exoPlayer.release() }
                }
            }

            val shouldPlay = isPlaying && isPlayerActive && isLifecycleStarted
            LaunchedEffect(exoPlayer, shouldPlay) {
                exoPlayer.playWhenReady = shouldPlay
            }

            LaunchedEffect(isFirstFrameReady, canStreamVideo) {
                latestOnActiveVideoChanged(isFirstFrameReady && canStreamVideo)
            }

            // Live 400ms bottom-20% (32x32 grid) frame sampling for Apple Music-like color shifts
            LaunchedEffect(textureViewRef, isFirstFrameReady, shouldPlay) {
                val tv = textureViewRef ?: return@LaunchedEffect
                if (!isFirstFrameReady) return@LaunchedEffect

                suspend fun sampleOnce() {
                    val bmp = runCatching {
                        if (tv.isAvailable && tv.width > 0 && tv.height > 0) {
                            tv.getBitmap(32, 160)
                        } else {
                            null
                        }
                    }.getOrNull() ?: return
                    try {
                        val rgb = withContext(Dispatchers.Default) {
                            sampleBitmapBottomStripAverageRgb(
                                bitmap = bmp,
                                stripFraction = 0.20f,
                                targetGrid = 32
                            )
                        }
                        if (rgb != null) {
                            latestOnBottomSampleRgb(rgb)
                        }
                    } finally {
                        bmp.recycle()
                    }
                }

                // Sample immediately when first frame settles, then every 400ms while playing
                sampleOnce()
                while (shouldPlay) {
                    delay(400L)
                    sampleOnce()
                }
            }

            val videoAlpha by animateFloatAsState(
                targetValue = if (isFirstFrameReady) 1f else 0f,
                animationSpec = tween(durationMillis = 360),
                label = "canvas_video_fade"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(videoAlpha)
            ) {
                AndroidView(
                    factory = { ctx ->
                        val tv = TextureView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                        DstInFadeFrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setFadeBottomFraction(fadeBottomFraction)
                            attachTextureView(tv)
                            textureViewRef = tv
                            exoPlayer.setVideoTextureView(tv)
                        }
                    },
                    update = { container ->
                        container.setFadeBottomFraction(fadeBottomFraction)
                        val tv = container.textureView
                        if (tv != null) {
                            textureViewRef = tv
                            exoPlayer.setVideoTextureView(tv)
                        }
                    },
                    onRelease = { container ->
                        val tv = container.textureView
                        if (tv != null) {
                            runCatching { exoPlayer.clearVideoTextureView(tv) }
                        }
                        textureViewRef = null
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Darken paused video frame subtly (~15%) while preserving the bottom DST_IN fade
                if (animatedPauseDarken > 0.005f) {
                    val overlayFadeMod = if (fadeStops != null) {
                        Modifier
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(colorStops = fadeStops),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                    } else {
                        Modifier
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(overlayFadeMod)
                            .background(Color.Black.copy(alpha = animatedPauseDarken))
                    )
                }
            }
        } else {
            LaunchedEffect(track.videoId) {
                latestOnActiveVideoChanged(false)
                latestOnBottomSampleRgb(null)
            }
        }
    }
}

/**
 * Container applying a hardware DST_IN bottom-fade mask directly to the child TextureView.
 * Uses RenderEffect.createShaderEffect on API 33+ and saveLayer + PorterDuff.Mode.DST_IN on < API 33.
 */
private class DstInFadeFrameLayout(context: Context) : FrameLayout(context) {
    var textureView: TextureView? = null
        private set

    private var fadeBottomFraction: Float = 0f
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cachedFadeFraction: Float = -1f
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    init {
        setWillNotDraw(false)
    }

    fun attachTextureView(tv: TextureView) {
        removeAllViews()
        textureView = tv
        addView(tv)
        cachedFadeFraction = -1f
        applyMaskIfNeeded(width, height)
    }

    fun setFadeBottomFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 0.85f)
        if (kotlin.math.abs(clamped - fadeBottomFraction) < 0.001f) return
        fadeBottomFraction = clamped
        cachedFadeFraction = -1f
        applyMaskIfNeeded(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedFadeFraction = -1f
        applyMaskIfNeeded(w, h)
    }

    private fun buildMaskShader(w: Int, h: Int): LinearGradient? {
        if (w <= 0 || h <= 0 || fadeBottomFraction <= 0.01f) return null
        val stops = buildCanvasBottomFadeStops(fadeBottomFraction = fadeBottomFraction, stopCount = 16)
        val positions = FloatArray(stops.size)
        val colors = IntArray(stops.size)
        for (i in stops.indices) {
            positions[i] = stops[i].first.coerceIn(0f, 1f)
            val a = (stops[i].second.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            colors[i] = AndroidColor.argb(a, 0, 0, 0)
        }
        return LinearGradient(0f, 0f, 0f, h.toFloat(), colors, positions, Shader.TileMode.CLAMP)
    }

    private fun applyMaskIfNeeded(w: Int, h: Int) {
        val tv = textureView ?: return
        if (cachedWidth == w && cachedHeight == h && cachedFadeFraction == fadeBottomFraction) return
        cachedWidth = w
        cachedHeight = h
        cachedFadeFraction = fadeBottomFraction

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = buildMaskShader(w, h)
            if (shader != null) {
                val offsetEffect = RenderEffect.createOffsetEffect(0f, 0f)
                val shaderEffect = RenderEffect.createShaderEffect(shader)
                tv.setRenderEffect(
                    RenderEffect.createBlendModeEffect(
                        offsetEffect,
                        shaderEffect,
                        AndroidBlendMode.DST_IN
                    )
                )
            } else {
                tv.setRenderEffect(null)
            }
        } else {
            maskPaint.shader = buildMaskShader(w, h)
        }
    }

    override fun dispatchDraw(canvas: AndroidCanvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || fadeBottomFraction <= 0.01f) {
            super.dispatchDraw(canvas)
            return
        }
        applyMaskIfNeeded(width, height)
        val shader = maskPaint.shader
        if (shader == null || width <= 0 || height <= 0) {
            super.dispatchDraw(canvas)
            return
        }
        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layerId)
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
