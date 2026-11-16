package com.example.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.RepeatMode
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.AnimatedEqualizer
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.formatTimeMs
import com.example.ui.components.upgradeArtworkUrl
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private enum class NowPlayingTab {
    PLAYER,
    LYRICS,
    QUEUE
}

@Composable
fun NowPlayingSheet(
    playbackState: PlaybackUiState,
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
    onRetry: () -> Unit,
    onOpenOptions: (Track) -> Unit
) {
    BackHandler { onCollapse() }

    val track = playbackState.currentTrack ?: return
    var activeTab by remember { mutableStateOf(NowPlayingTab.PLAYER) }
    val context = LocalContext.current

    val primaryGlow by animateColorAsState(
        targetValue = playbackState.ambientPrimary,
        animationSpec = tween(900),
        label = "np_primary"
    )
    val secondaryGlow by animateColorAsState(
        targetValue = playbackState.ambientSecondary,
        animationSpec = tween(900),
        label = "np_secondary"
    )

    // Drifting ambient orb animation (signature Apple Music liquid color atmosphere)
    val infiniteTransition = rememberInfiniteTransition(label = "apple_ambient")
    val orbPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "orb_phase"
    )

    val highResBackdropUrl = remember(track.artworkUrl) { upgradeArtworkUrl(track.artworkUrl) }
    val backdropRequest = remember(highResBackdropUrl, context) {
        if (highResBackdropUrl.isBlank()) null
        else ImageRequest.Builder(context)
            .data(highResBackdropUrl)
            .crossfade(400)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070B))
            .testTag("now_playing_sheet")
    ) {
        if (backdropRequest != null) {
            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.blur(92.dp)
            } else {
                Modifier
            }
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier
                    .fillMaxSize()
                    .then(blurModifier)
                    .alpha(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.56f else 0.24f)
            )
        }

        // Living radial color orbs + depth vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val driftX1 = (0.28f + 0.14f * cos(orbPhase)) * size.width
                    val driftY1 = (0.22f + 0.10f * sin(orbPhase)) * size.height
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryGlow.copy(alpha = 0.50f),
                                primaryGlow.copy(alpha = 0.14f),
                                Color.Transparent
                            ),
                            center = Offset(driftX1, driftY1),
                            radius = size.maxDimension * 0.72f
                        )
                    )

                    val driftX2 = (0.76f + 0.14f * sin(orbPhase * 0.8f)) * size.width
                    val driftY2 = (0.66f + 0.12f * cos(orbPhase * 0.8f)) * size.height
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                secondaryGlow.copy(alpha = 0.46f),
                                secondaryGlow.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            center = Offset(driftX2, driftY2),
                            radius = size.maxDimension * 0.70f
                        )
                    )
                }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.24f),
                            Color(0xFF0A0A10).copy(alpha = 0.42f),
                            Color(0xFF06060A).copy(alpha = 0.86f)
                        )
                    )
                )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val isWide = maxWidth >= 700.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp)
            ) {
                // Apple Music top drag handle + minimal header bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            var totalDrag = 0f
                            detectVerticalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onVerticalDrag = { _, dragAmount ->
                                    if (dragAmount > 0f) totalDrag += dragAmount
                                },
                                onDragEnd = {
                                    if (totalDrag > 90f) onCollapse()
                                }
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.36f))
                            .clickable(onClick = onCollapse)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onCollapse,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("now_playing_collapse_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Minimizar reproductor",
                                tint = Color.White.copy(alpha = 0.90f),
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.11f),
                            border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.20f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (playbackState.isPlaying) {
                                    AnimatedEqualizer(
                                        isPlaying = true,
                                        color = Color.White.copy(alpha = 0.92f),
                                        modifier = Modifier.height(12.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = LocalSonoraAccent.current,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = playbackState.codecLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = Color.White.copy(alpha = 0.90f)
                                )
                            }
                        }

                        IconButton(
                            onClick = { onOpenOptions(track) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("now_playing_more_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = "Opciones de canción",
                                tint = Color.White.copy(alpha = 0.90f)
                            )
                        }
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PlayerMainContent(
                                track = track,
                                playbackState = playbackState,
                                isLiked = isLiked,
                                compactArtwork = true,
                                onTogglePlayPause = onTogglePlayPause,
                                onSkipNext = onSkipNext,
                                onSkipPrevious = onSkipPrevious,
                                onSeekTo = onSeekTo,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                onToggleLike = { onToggleLike(track) },
                                onOpenOptions = { onOpenOptions(track) },
                                onRetry = onRetry
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(vertical = 8.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(Color.Black.copy(alpha = 0.18f))
                                .border(
                                    width = 0.6.dp,
                                    color = Color.White.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(26.dp)
                                )
                        ) {
                            if (activeTab == NowPlayingTab.QUEUE) {
                                QueuePane(
                                    playbackState = playbackState,
                                    onSkipToIndex = onSkipToQueueIndex
                                )
                            } else {
                                LyricsPane(
                                    track = track,
                                    playbackState = playbackState,
                                    onSeekTo = onSeekTo,
                                    onTogglePlayPause = onTogglePlayPause
                                )
                            }
                        }
                    }
                } else {
                    AnimatedContent(
                        targetState = activeTab,
                        transitionSpec = {
                            fadeIn(tween(240)) togetherWith fadeOut(tween(180))
                        },
                        modifier = Modifier.weight(1f),
                        label = "np_tab_transition"
                    ) { tab ->
                        when (tab) {
                            NowPlayingTab.PLAYER -> {
                                PlayerMainContent(
                                    track = track,
                                    playbackState = playbackState,
                                    isLiked = isLiked,
                                    compactArtwork = false,
                                    onTogglePlayPause = onTogglePlayPause,
                                    onSkipNext = onSkipNext,
                                    onSkipPrevious = onSkipPrevious,
                                    onSeekTo = onSeekTo,
                                    onToggleShuffle = onToggleShuffle,
                                    onCycleRepeat = onCycleRepeat,
                                    onToggleLike = { onToggleLike(track) },
                                    onOpenOptions = { onOpenOptions(track) },
                                    onRetry = onRetry
                                )
                            }

                            NowPlayingTab.LYRICS -> {
                                LyricsPane(
                                    track = track,
                                    playbackState = playbackState,
                                    onSeekTo = onSeekTo,
                                    onTogglePlayPause = onTogglePlayPause
                                )
                            }

                            NowPlayingTab.QUEUE -> {
                                GlassCard(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 6.dp),
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    QueuePane(
                                        playbackState = playbackState,
                                        onSkipToIndex = onSkipToQueueIndex
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom frosted Apple Music mode pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassModeButton(
                        icon = Icons.Rounded.Lyrics,
                        label = "Letras",
                        selected = activeTab == NowPlayingTab.LYRICS,
                        tag = "tab_lyrics_button",
                        onClick = {
                            activeTab = if (activeTab == NowPlayingTab.LYRICS) NowPlayingTab.PLAYER else NowPlayingTab.LYRICS
                        }
                    )
                    GlassModeButton(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        label = "Playlist",
                        selected = false,
                        tag = "np_add_playlist_button",
                        onClick = { onAddToPlaylist(track) }
                    )
                    GlassModeButton(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        label = "Cola (${playbackState.queue.size})",
                        selected = activeTab == NowPlayingTab.QUEUE,
                        tag = "tab_queue_button",
                        onClick = {
                            activeTab = if (activeTab == NowPlayingTab.QUEUE) NowPlayingTab.PLAYER else NowPlayingTab.QUEUE
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerMainContent(
    track: Track,
    playbackState: PlaybackUiState,
    isLiked: Boolean,
    compactArtwork: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenOptions: () -> Unit,
    onRetry: () -> Unit
) {
    val coverScale by animateFloatAsState(
        targetValue = if (playbackState.isPlaying) 1.0f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cover_scale"
    )

    val haloPulseTransition = rememberInfiniteTransition(label = "cover_halo")
    val haloAlpha by haloPulseTransition.animateFloat(
        initialValue = 0.24f,
        targetValue = 0.44f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200),
            repeatMode = AnimRepeatMode.Reverse
        ),
        label = "halo_alpha"
    )

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val duration = playbackState.durationMs.coerceAtLeast(1L)
    val liveFraction = (playbackState.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val sliderValue = if (isScrubbing) scrubFraction else liveFraction
    val displayPosMs = if (isScrubbing) (scrubFraction * duration).toLong() else playbackState.positionMs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(2.dp))

        // Crisp high-resolution full square artwork with gentle Apple Music living aura
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(vertical = 8.dp)
                .drawBehind {
                    val auraStrength = if (playbackState.isPlaying) haloAlpha else 0.14f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                playbackState.ambientPrimary.copy(alpha = auraStrength),
                                playbackState.ambientSecondary.copy(alpha = auraStrength * 0.45f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.maxDimension * 0.70f
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            SonoraArtwork(
                url = track.artworkUrl,
                contentDescription = track.title,
                modifier = Modifier
                    .size(if (compactArtwork) 248.dp else 326.dp)
                    .scale(coverScale)
                    .shadow(
                        elevation = if (playbackState.isPlaying) 32.dp else 10.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = playbackState.ambientPrimary,
                        spotColor = playbackState.ambientPrimary
                    ),
                cornerRadius = 22.dp
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (playbackState.errorMessage != null) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = playbackState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = LocalSonoraAccent.current),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Reintentar")
                        }
                    }
                }
            }

            // Apple Music track title + artist + frosted favorite & more buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onToggleLike,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_like_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isLiked) "Quitar me gusta" else "Me gusta",
                            tint = if (isLiked) LocalSonoraAccent.current else Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onOpenOptions,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_quick_options_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = "Más opciones",
                            tint = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            AppleMusicScrubber(
                fraction = sliderValue,
                isScrubbing = isScrubbing,
                onScrubStart = { f ->
                    isScrubbing = true
                    scrubFraction = f
                },
                onScrubChange = { f ->
                    isScrubbing = true
                    scrubFraction = f
                },
                onScrubEnd = { f ->
                    onSeekTo((f * duration).toLong())
                    isScrubbing = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("now_playing_slider")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimeMs(displayPosMs),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.Medium
                    ),
                    color = if (isScrubbing) Color.White else Color.White.copy(alpha = 0.56f)
                )
                Text(
                    text = "-${formatTimeMs((duration - displayPosMs).coerceAtLeast(0L))}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.Medium
                    ),
                    color = if (isScrubbing) Color.White else Color.White.copy(alpha = 0.56f)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Clean Apple Music borderless transport controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Aleatorio",
                        tint = if (playbackState.shuffleEnabled) LocalSonoraAccent.current else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier
                        .size(60.dp)
                        .testTag("now_playing_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.24f),
                                    Color.White.copy(alpha = 0.12f)
                                )
                            )
                        )
                        .border(0.8.dp, Color.White.copy(alpha = 0.26f), CircleShape)
                        .clickable(onClick = onTogglePlayPause)
                        .testTag("now_playing_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (playbackState.isBuffering) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 2.8.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier
                        .size(60.dp)
                        .testTag("now_playing_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }

                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_repeat_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == RepeatMode.ONE) {
                            Icons.Rounded.RepeatOne
                        } else Icons.Rounded.Repeat,
                        contentDescription = "Repetir",
                        tint = if (playbackState.repeatMode != RepeatMode.OFF) LocalSonoraAccent.current else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun AppleMusicScrubber(
    fraction: Float,
    isScrubbing: Boolean,
    onScrubStart: (Float) -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val barHeight by animateDpAsState(
        targetValue = if (isScrubbing) 9.dp else 5.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scrubber_height"
    )
    var widthPx by remember { mutableIntStateOf(1) }
    var activeFraction by remember { mutableFloatStateOf(fraction) }

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val tapped = (offset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                    onScrubEnd(tapped)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { startOffset ->
                        val f = (startOffset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        activeFraction = f
                        onScrubStart(f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        activeFraction = f
                        onScrubChange(f)
                    },
                    onDragEnd = {
                        onScrubEnd(activeFraction)
                    },
                    onDragCancel = {
                        onScrubEnd(activeFraction)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val clamped = fraction.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.24f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isScrubbing) 0.92f else 0.78f),
                                Color.White
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun LyricsPane(
    track: Track,
    playbackState: PlaybackUiState,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit
) {
    val trackLyrics = playbackState.lyrics
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var autoFollow by remember { mutableStateOf(true) }

    val shimmerTransition = rememberInfiniteTransition(label = "lyrics_glow")
    val shimmerPulse by shimmerTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Reverse
        ),
        label = "shimmer_pulse"
    )

    // Detect manual scrolling by the user and pause auto-center temporarily
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            autoFollow = false
        }
    }
    LaunchedEffect(autoFollow, track.videoId) {
        if (!autoFollow) {
            delay(4_200L)
            autoFollow = true
        }
    }

    val lines = trackLyrics?.lines.orEmpty()
    val activeIndex = remember(lines, playbackState.positionMs) {
        if (lines.isEmpty()) -1
        else {
            var idx = 0
            for (i in lines.indices) {
                if (lines[i].timeMs <= playbackState.positionMs + 180L) {
                    idx = i
                } else {
                    break
                }
            }
            idx
        }
    }

    val activeLineProgress = remember(lines, activeIndex, playbackState.positionMs, playbackState.durationMs) {
        if (activeIndex !in lines.indices) 0f
        else {
            val startMs = lines[activeIndex].timeMs
            val nextMs = lines.getOrNull(activeIndex + 1)?.timeMs
                ?: playbackState.durationMs.takeIf { it > startMs }
                ?: (startMs + 4_500L)
            val span = (nextMs - startMs).coerceAtLeast(600L)
            ((playbackState.positionMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(activeIndex, autoFollow, viewportHeightPx) {
        if (autoFollow && activeIndex >= 0 && viewportHeightPx > 0) {
            val centerOffset = -(viewportHeightPx * 0.28f).toInt()
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = centerOffset
            )
        }
    }

    val progressFraction = if (playbackState.durationMs > 0L) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 6.dp)
    ) {
        // Compact Apple Music Lyrics track mini-header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SonoraArtwork(
                url = track.artworkUrl,
                contentDescription = track.title,
                modifier = Modifier.size(48.dp),
                cornerRadius = 10.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (trackLyrics != null) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = if (trackLyrics.isSynced) "Sincronizada" else "Auto",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("lyrics_play_pause_button")
            ) {
                Icon(
                    imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(2.5.dp)
                .clip(RoundedCornerShape(50)),
            color = Color.White.copy(alpha = 0.86f),
            trackColor = Color.White.copy(alpha = 0.16f)
        )

        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                playbackState.isLoadingLyrics -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                lines.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No hay letras disponibles para esta canción.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 68.dp, bottom = 140.dp, start = 4.dp, end = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportHeightPx = it.height }
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.0f to Color.Transparent,
                                        0.10f to Color.Black,
                                        0.86f to Color.Black,
                                        1.0f to Color.Transparent
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                            .testTag("lyrics_list")
                    ) {
                        itemsIndexed(lines, key = { idx, line -> "${idx}_${line.timeMs}" }) { idx, line ->
                            val distance = abs(idx - activeIndex)
                            val isActive = distance == 0
                            val targetAlpha = when {
                                !autoFollow -> if (isActive) 1.0f else 0.72f
                                isActive -> 1.0f
                                distance == 1 -> 0.48f
                                distance == 2 -> 0.27f
                                else -> 0.15f
                            }
                            val targetScale = if (isActive) 1.0f else 0.94f
                            val targetBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && autoFollow) {
                                when (distance) {
                                    0 -> 0f
                                    1 -> 1.3f
                                    2 -> 2.6f
                                    else -> 4.0f
                                }
                            } else {
                                0f
                            }

                            val animatedAlpha by animateFloatAsState(
                                targetValue = targetAlpha,
                                animationSpec = tween(durationMillis = 360),
                                label = "lyric_alpha_$idx"
                            )
                            val animatedScale by animateFloatAsState(
                                targetValue = targetScale,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "lyric_scale_$idx"
                            )
                            val animatedBlur by animateFloatAsState(
                                targetValue = targetBlur,
                                animationSpec = tween(durationMillis = 360),
                                label = "lyric_blur_$idx"
                            )

                            val baseStyle = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = if (isActive) 28.sp else 25.sp,
                                lineHeight = if (isActive) 36.sp else 33.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp
                            )

                            val sweepCenter = (activeLineProgress * 1.12f).coerceIn(0.05f, 0.98f)
                            val activeBrush = Brush.horizontalGradient(
                                0.0f to Color.White,
                                (sweepCenter - 0.10f).coerceAtLeast(0.0f) to Color.White,
                                sweepCenter to Color(0xFFFFFFFF),
                                (sweepCenter + 0.14f).coerceAtMost(1.0f) to Color.White.copy(alpha = 0.74f),
                                1.0f to Color.White.copy(alpha = 0.62f)
                            )

                            val lineStyle = if (isActive) {
                                baseStyle.copy(
                                    brush = activeBrush,
                                    shadow = Shadow(
                                        color = Color.White.copy(alpha = 0.56f * shimmerPulse),
                                        offset = Offset.Zero,
                                        blurRadius = 22f
                                    )
                                )
                            } else {
                                baseStyle.copy(color = Color.White)
                            }

                            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && animatedBlur > 0.1f) {
                                Modifier.blur(animatedBlur.dp)
                            } else {
                                Modifier
                            }

                            Text(
                                text = line.text,
                                style = lineStyle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        onSeekTo(line.timeMs)
                                        autoFollow = true
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp)
                                    .graphicsLayer {
                                        alpha = animatedAlpha
                                        scaleX = animatedScale
                                        scaleY = animatedScale
                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                    }
                                    .then(blurModifier)
                            )
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !autoFollow,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                    ) {
                        Surface(
                            onClick = {
                                autoFollow = true
                                if (activeIndex >= 0 && viewportHeightPx > 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(
                                            index = activeIndex,
                                            scrollOffset = -(viewportHeightPx * 0.28f).toInt()
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.22f),
                            border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.36f)),
                            modifier = Modifier.testTag("center_lyrics_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CenterFocusStrong,
                                    contentDescription = "Centrar letra",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Centrar letra",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePane(
    playbackState: PlaybackUiState,
    onSkipToIndex: (Int) -> Unit
) {
    val accent = LocalSonoraAccent.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "A continuación",
            style = MaterialTheme.typography.headlineMedium,
            color = SonoraTextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(playbackState.queue) { idx, item ->
                val isCurrent = idx == playbackState.currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isCurrent) accent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { onSkipToIndex(idx) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SonoraArtwork(
                        url = item.artworkUrl,
                        contentDescription = item.title,
                        modifier = Modifier.size(46.dp),
                        cornerRadius = 10.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isCurrent) accent else SonoraTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.durationText.isNotBlank()) {
                        Text(
                            text = item.durationText,
                            style = MaterialTheme.typography.labelSmall,
                            color = SonoraTextTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.09f),
        border = BorderStroke(
            0.7.dp,
            if (selected) Color.White.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.16f)
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.78f)
            )
        }
    }
}
