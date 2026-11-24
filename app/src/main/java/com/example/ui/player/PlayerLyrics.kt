package com.example.ui.player

import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Style
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LyricLine
import com.example.model.Track
import com.example.model.TrackLyrics
import com.example.player.PlaybackUiState
import com.example.ui.components.LyricsShareCardDialog
import com.example.ui.components.SonoraArtwork
import com.example.ui.theme.LocalSonoraAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun LyricsPane(
    track: Track,
    playbackState: PlaybackUiState,
    positionMsFlow: StateFlow<Long>,
    lyricsOffsetMs: Long,
    onAdjustLyricsOffset: (Long) -> Unit,
    onResetLyricsOffset: () -> Unit,
    onReloadLyrics: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberPlayerHaptics()
    val accent = LocalSonoraAccent.current
    val trackLyrics = playbackState.lyrics
    val lines = trackLyrics?.lines.orEmpty()
    val clock by rememberLyricClockState(
        lyrics = trackLyrics,
        positionMsFlow = positionMsFlow,
        durationMs = playbackState.durationMs,
        offsetMs = lyricsOffsetMs
    )
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var autoFollow by remember { mutableStateOf(true) }
    var showLyricsCardDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) autoFollow = false
    }
    LaunchedEffect(autoFollow, track.videoId) {
        if (!autoFollow) {
            delay(4_200L)
            autoFollow = true
        }
    }
    LaunchedEffect(clock.activeIndex, autoFollow, viewportHeightPx) {
        if (autoFollow && clock.activeIndex >= 0 && viewportHeightPx > 0) {
            listState.animateScrollToItem(
                index = clock.activeIndex,
                scrollOffset = -(viewportHeightPx * 0.28f).toInt()
            )
        }
    }

    val progressFraction = if (playbackState.durationMs > 0L) {
        ((clock.effectivePositionMs - lyricsOffsetMs).toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 6.dp)
    ) {
        LyricsHeaderBar(
            track = track,
            trackLyrics = trackLyrics,
            hasLines = lines.isNotEmpty(),
            isPlaying = playbackState.isPlaying,
            onReloadLyrics = {
                haptics.tick()
                onReloadLyrics()
            },
            onShareExcerpt = {
                if (lines.isNotEmpty()) {
                    haptics.tick()
                    val idx = clock.activeIndex.coerceIn(0, lines.lastIndex)
                    val excerpt = lines.subList(idx, (idx + 3).coerceAtMost(lines.size))
                        .joinToString("\n") { "“${it.text}”" }
                    val shareText = "$excerpt\n\n— ${track.title} • ${track.artist} (Sonora)"
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(sendIntent, "Compartir letra"))
                    }
                }
            },
            onTogglePlayPause = {
                haptics.tick()
                onTogglePlayPause()
            }
        )

        if (lines.isNotEmpty()) {
            LyricsSyncAndCardBar(
                lyricsOffsetMs = lyricsOffsetMs,
                accent = accent,
                onOpenCard = {
                    haptics.tick()
                    showLyricsCardDialog = true
                },
                onAdjustLyricsOffset = { delta ->
                    haptics.tick()
                    onAdjustLyricsOffset(delta)
                },
                onResetLyricsOffset = {
                    haptics.tick()
                    onResetLyricsOffset()
                }
            )
        }

        if (showLyricsCardDialog && lines.isNotEmpty()) {
            LyricsShareCardDialog(
                track = track,
                lines = lines,
                initialLineIndex = clock.activeIndex.coerceIn(0, lines.lastIndex),
                ambientPrimary = playbackState.ambientPrimary,
                onDismiss = { showLyricsCardDialog = false }
            )
        }

        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .height(2.5.dp)
                .clip(RoundedCornerShape(50)),
            color = Color.White.copy(alpha = 0.86f),
            trackColor = Color.White.copy(alpha = 0.16f)
        )

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && track.artworkUrl.isNotBlank()) {
                SonoraArtwork(
                    url = track.artworkUrl,
                    contentDescription = "",
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(32.dp)
                        .alpha(0.14f)
                )
            }

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
                        contentPadding = PaddingValues(top = 68.dp, bottom = 140.dp, start = 6.dp, end = 6.dp),
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
                            SyncedLyricRow(
                                index = idx,
                                line = line,
                                distance = abs(idx - clock.activeIndex),
                                isActive = idx == clock.activeIndex,
                                autoFollow = autoFollow,
                                activeLineProgress = clock.activeLineProgress,
                                activeWordIndex = clock.activeWordIndex,
                                activeWordProgress = clock.activeWordProgress,
                                shimmerPulse = shimmerPulse,
                                accent = accent,
                                onClick = {
                                    haptics.tick()
                                    onSeekTo((line.timeMs - lyricsOffsetMs).coerceAtLeast(0L))
                                    autoFollow = true
                                }
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
                                haptics.tick()
                                autoFollow = true
                                if (clock.activeIndex >= 0 && viewportHeightPx > 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(
                                            index = clock.activeIndex,
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
                                Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Centrar letra", tint = Color.White, modifier = Modifier.size(16.dp))
                                Text("Centrar letra", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsHeaderBar(
    track: Track,
    trackLyrics: TrackLyrics?,
    hasLines: Boolean,
    isPlaying: Boolean,
    onReloadLyrics: () -> Unit,
    onShareExcerpt: () -> Unit,
    onTogglePlayPause: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SonoraArtwork(
            url = track.artworkUrl,
            contentDescription = track.title,
            modifier = Modifier.size(46.dp),
            cornerRadius = 10.dp
        )
        Spacer(Modifier.width(10.dp))
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
                    val badgeLabel = trackLyrics.sourceLabel.ifBlank {
                        if (trackLyrics.isSynced) "Sincronizada" else "Auto"
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
        IconButton(
            onClick = onReloadLyrics,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("lyrics_reload_button")
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Recargar letra", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        }
        if (hasLines) {
            IconButton(
                onClick = onShareExcerpt,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("lyrics_share_button")
            ) {
                Icon(Icons.Rounded.Share, contentDescription = "Compartir verso", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
            }
        }
        IconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("lyrics_play_pause_button")
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun LyricsSyncAndCardBar(
    lyricsOffsetMs: Long,
    accent: Color,
    onOpenCard: () -> Unit,
    onAdjustLyricsOffset: (Long) -> Unit,
    onResetLyricsOffset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onOpenCard,
            shape = RoundedCornerShape(50),
            color = accent.copy(alpha = 0.22f),
            border = BorderStroke(0.6.dp, accent.copy(alpha = 0.56f)),
            modifier = Modifier.testTag("lyrics_card_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Rounded.Style, contentDescription = "Crear tarjeta de letra", tint = Color.White, modifier = Modifier.size(14.dp))
                Text("Tarjeta", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            }
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = Color.White.copy(alpha = 0.10f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "-0.5s",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAdjustLyricsOffset(-500L) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("lyrics_offset_minus")
                )
                Text(
                    text = if (lyricsOffsetMs == 0L) "Sincro 0s" else "%+.1fs".format(lyricsOffsetMs / 1000f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (lyricsOffsetMs == 0L) Color.White.copy(alpha = 0.72f) else accent,
                    modifier = Modifier.clickable(onClick = onResetLyricsOffset).testTag("lyrics_offset_reset")
                )
                Text(
                    text = "+0.5s",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAdjustLyricsOffset(500L) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("lyrics_offset_plus")
                )
            }
        }
    }
}

@Composable
private fun SyncedLyricRow(
    index: Int,
    line: LyricLine,
    distance: Int,
    isActive: Boolean,
    autoFollow: Boolean,
    activeLineProgress: Float,
    activeWordIndex: Int,
    activeWordProgress: Float,
    shimmerPulse: Float,
    accent: Color,
    onClick: () -> Unit
) {
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
    } else 0f

    val animatedAlpha by animateFloatAsState(targetAlpha, tween(340), label = "lyric_alpha_$index")
    val animatedScale by animateFloatAsState(
        targetScale,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "lyric_scale_$index"
    )
    val animatedBlur by animateFloatAsState(targetBlur, tween(340), label = "lyric_blur_$index")

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
        sweepCenter to lerp(Color.White, accent, 0.25f),
        (sweepCenter + 0.14f).coerceAtMost(1.0f) to Color.White.copy(alpha = 0.72f),
        1.0f to Color.White.copy(alpha = 0.56f)
    )

    val lineStyle = when {
        isActive && line.words.isNotEmpty() -> baseStyle.copy(
            color = Color.White,
            shadow = Shadow(color = Color.White.copy(alpha = 0.56f * shimmerPulse), offset = Offset.Zero, blurRadius = 22f)
        )
        isActive -> baseStyle.copy(
            brush = activeBrush,
            shadow = Shadow(color = Color.White.copy(alpha = 0.56f * shimmerPulse), offset = Offset.Zero, blurRadius = 22f)
        )
        else -> baseStyle.copy(color = Color.White)
    }

    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && animatedBlur > 0.1f) {
        Modifier.blur(animatedBlur.dp)
    } else Modifier

    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 4.dp, horizontal = 6.dp)
        .graphicsLayer {
            alpha = animatedAlpha
            scaleX = animatedScale
            scaleY = animatedScale
            transformOrigin = TransformOrigin(0f, 0.5f)
        }
        .then(blurModifier)

    if (isActive && line.words.isNotEmpty()) {
        val wordAnnotated = buildAnnotatedString {
            line.words.forEachIndexed { wIdx, w ->
                val color = when {
                    wIdx < activeWordIndex -> Color.White
                    wIdx == activeWordIndex -> lerp(Color.White.copy(alpha = 0.65f), Color.White, activeWordProgress)
                    else -> Color.White.copy(alpha = 0.45f)
                }
                val weight = if (wIdx == activeWordIndex) FontWeight.ExtraBold else FontWeight.Bold
                pushStyle(SpanStyle(color = color, fontWeight = weight))
                append(w.text)
                pop()
            }
        }
        Text(text = wordAnnotated, style = lineStyle, modifier = rowModifier)
    } else {
        Text(text = line.text, style = lineStyle, modifier = rowModifier)
    }
}
