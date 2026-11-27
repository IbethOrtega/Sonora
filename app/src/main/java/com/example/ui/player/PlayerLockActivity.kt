package com.example.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.SonoraApplication
import com.example.data.network.CanvasRepository
import com.example.model.CanvasArtwork
import com.example.model.SonoraSettings
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerLockActivity : ComponentActivity() {

    private val app by lazy { application as SonoraApplication }
    private var screenOffRegistered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.playerController.connect()
    }

    override fun onStop() {
        super.onStop()
        app.playerController.release()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOffRegistered = true

        setContent {
            val settings by app.preferences.settingsFlow.collectAsState(initial = SonoraSettings())
            val playbackState by app.playerController.state.collectAsState()
            val likedSongs by app.repository.observeLikedSongs().collectAsState(initial = emptyList())
            val coroutineScope = rememberCoroutineScope()
            val currentTrack = playbackState.currentTrack
            val isLiked = remember(currentTrack?.videoId, likedSongs) {
                currentTrack != null && likedSongs.any { it.videoId == currentTrack.videoId }
            }

            SonoraTheme(
                accentPreset = settings.accentPreset,
                customAccentHex = settings.customAccentHex,
                glassStyle = settings.glassStyle
            ) {
                PlayerLockContent(
                    playbackState = playbackState,
                    positionMsFlow = app.playerController.positionMsFlow,
                    settings = settings,
                    isLiked = isLiked,
                    onTogglePlayPause = { app.playerController.togglePlayPause() },
                    onSkipNext = { app.playerController.skipNext() },
                    onSkipPrevious = { app.playerController.skipPrevious() },
                    onSeekTo = { app.playerController.seekTo(it) },
                    onToggleLike = { track ->
                        coroutineScope.launch { app.repository.toggleLike(track) }
                    },
                    onAdjustLyricsOffset = { delta ->
                        val videoId = currentTrack?.videoId.orEmpty()
                        coroutineScope.launch {
                            val current = settings.lyricsOffsetFor(videoId)
                            val next = (current + delta).coerceIn(-2_000L, 2_000L)
                            if (videoId.isNotBlank()) {
                                app.preferences.setTrackLyricsOffsetMs(videoId, next)
                            } else {
                                app.preferences.setLyricsOffsetMs(next)
                            }
                        }
                    },
                    onResetLyricsOffset = {
                        val videoId = currentTrack?.videoId.orEmpty()
                        coroutineScope.launch {
                            if (videoId.isNotBlank()) {
                                app.preferences.setTrackLyricsOffsetMs(videoId, 0L)
                            }
                            app.preferences.setLyricsOffsetMs(0L)
                        }
                    },
                    onReloadLyrics = { app.playerController.reloadLyrics() },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        if (screenOffRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, PlayerLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}

@Composable
private fun PlayerLockContent(
    playbackState: PlaybackUiState,
    positionMsFlow: StateFlow<Long>,
    settings: SonoraSettings,
    isLiked: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleLike: (Track) -> Unit,
    onAdjustLyricsOffset: (Long) -> Unit,
    onResetLyricsOffset: () -> Unit,
    onReloadLyrics: () -> Unit,
    onDismiss: () -> Unit
) {
    val track = playbackState.currentTrack
    val accent = LocalSonoraAccent.current

    val dismissGesture = Modifier.pointerInput(onDismiss) {
        var totalDrag = 0f
        detectVerticalDragGestures(
            onDragStart = { totalDrag = 0f },
            onVerticalDrag = { _, dragAmount -> totalDrag += dragAmount },
            onDragEnd = {
                if (totalDrag > 95f) onDismiss()
            }
        )
    }

    if (track == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0607))
                .then(dismissGesture)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Sin reproducción activa",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(14.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .sonoraLiquidGlassSurface(shape = CircleShape, surfaceAlpha = 0.40f)
                        .minimumInteractiveComponentSize()
                        .testTag("lock_player_close_button")
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = Color.White)
                }
            }
        }
        return
    }

    val coverGradient = rememberCoverGradientSpec(
        artworkUrl = track.artworkUrl,
        fallbackPrimary = playbackState.ambientPrimary,
        accent = accent
    )
    val driftTransition = rememberInfiniteTransition(label = "lock_grad_drift")
    val driftShift by driftTransition.animateFloat(
        initialValue = -0.05f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lock_grad_shift"
    )

    var canvasArtwork by remember(track.videoId) {
        mutableStateOf<CanvasArtwork?>(CanvasRepository.getCached(track.videoId))
    }
    LaunchedEffect(track.videoId, track.title, track.artist, track.album, settings.animatedArtworkEnabled) {
        canvasArtwork = if (!settings.animatedArtworkEnabled) {
            null
        } else {
            CanvasRepository.fetchCanvas(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                album = track.album
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val h = size.height.coerceAtLeast(1f)
                val midStop = (0.45f + driftShift).coerceIn(0.32f, 0.58f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to coverGradient.topColor,
                        midStop to coverGradient.midColor,
                        1.0f to coverGradient.bottomColor
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            coverGradient.topColor.copy(alpha = 0.28f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * (0.5f + driftShift), h * 0.30f),
                        radius = size.width * 0.9f
                    )
                )
            }
            .testTag("lock_player_root")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Top bar with swipe-down handle and dismiss control
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dismissGesture)
                    .padding(top = 8.dp, bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.36f))
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .sonoraLiquidGlassSurface(shape = CircleShape, surfaceAlpha = 0.40f)
                        .minimumInteractiveComponentSize()
                        .testTag("lock_player_close_button")
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = Color.White)
                }
            }

            // Compact Animated Cover + Track Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dismissGesture)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    CanvasArtworkPlayer(
                        track = track,
                        canvasArtwork = canvasArtwork,
                        animatedArtworkEnabled = settings.animatedArtworkEnabled,
                        allowMobileData = settings.animatedArtworkMobileData,
                        preferPortrait = false,
                        isPlaying = playbackState.isPlaying,
                        isPlayerActive = true,
                        ambientPrimary = playbackState.ambientPrimary,
                        cornerRadius = 14.dp,
                        showBorder = false,
                        showShadow = false,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = coverGradient.artistTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val albumName = track.album
                    if (!albumName.isNullOrBlank()) {
                        Text(
                            text = albumName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.56f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = { onToggleLike(track) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("lock_player_like_button")
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        contentDescription = if (isLiked) "Quitar favorito" else "Favorito",
                        tint = if (isLiked) coverGradient.activeStarTint else Color.White.copy(alpha = 0.80f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Synchronized Lyrics Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LyricsPane(
                    track = track,
                    playbackState = playbackState,
                    positionMsFlow = positionMsFlow,
                    lyricsOffsetMs = settings.lyricsOffsetFor(track.videoId),
                    onAdjustLyricsOffset = onAdjustLyricsOffset,
                    onResetLyricsOffset = onResetLyricsOffset,
                    onReloadLyrics = onReloadLyrics,
                    onSeekTo = onSeekTo,
                    onTogglePlayPause = onTogglePlayPause
                )
            }

            // Bottom Scrub Bar & Transport Controls
            ThinSlider(
                positionMsFlow = positionMsFlow,
                durationMs = playbackState.durationMs,
                crossfadeZoneFraction = 0f,
                codecBadgeText = playbackState.codecLabel,
                isPlaying = playbackState.isPlaying,
                hasActiveAudioFx = settings.dolbyAtmosEnabled || settings.spatialAudioEnabled || settings.eqEnabled,
                accent = accent,
                trackColor = coverGradient.trackBarTint,
                onOpenAudioSheet = {},
                onSeekTo = onSeekTo
            )
            Spacer(Modifier.height(6.dp))
            TransportRow(
                isPlaying = playbackState.isPlaying,
                isBuffering = playbackState.isBuffering,
                accent = accent,
                onSkipPrevious = onSkipPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}
