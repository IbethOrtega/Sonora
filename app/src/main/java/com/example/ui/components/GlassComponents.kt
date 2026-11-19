package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.local.PlaylistEntity
import com.example.model.BrowseCard
import com.example.model.GlassStyle
import com.example.model.ItemKind
import com.example.model.StatsForNerdsData
import com.example.model.Track
import com.example.player.PlaybackUiState
import androidx.compose.ui.graphics.lerp
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraAccentSecondary
import com.example.ui.theme.LocalSonoraGlassStyle
import com.example.ui.theme.SonoraBackground
import com.example.ui.theme.SonoraGlassBorderBottom
import com.example.ui.theme.SonoraGlassBorderTop
import com.example.ui.theme.SonoraTextPrimary
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary

fun Modifier.sonoraPressScale(
    pressedScale: Float = 0.96f
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "press_scale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun AmbientBackdrop(
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val animatedPrimary by animateColorAsState(
        targetValue = primaryColor,
        animationSpec = tween(durationMillis = 700),
        label = "ambient_primary"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = secondaryColor,
        animationSpec = tween(durationMillis = 700),
        label = "ambient_secondary"
    )

    val glassStyle = LocalSonoraGlassStyle.current
    val baseBg = if (glassStyle == GlassStyle.OBSIDIAN) {
        Color(0xFF040406)
    } else {
        lerp(SonoraBackground, animatedSecondary, 0.24f)
    }
    val bottomBg = if (glassStyle == GlassStyle.OBSIDIAN) {
        Color(0xFF040406)
    } else {
        lerp(SonoraBackground, animatedPrimary, 0.08f)
    }
    val glowFactor = if (glassStyle == GlassStyle.OBSIDIAN) 0.55f else 1.0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(baseBg, bottomBg)))
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedPrimary.copy(alpha = 0.30f * glowFactor),
                            animatedSecondary.copy(alpha = 0.16f * glowFactor),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.22f, size.height * 0.12f),
                        radius = size.maxDimension * 0.78f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedSecondary.copy(alpha = 0.24f * glowFactor),
                            animatedPrimary.copy(alpha = 0.10f * glowFactor),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.88f, size.height * 0.45f),
                        radius = size.maxDimension * 0.62f
                    )
                )
            }
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = Color(0xFF20202E),
    alphaTop: Float = 0.38f,
    alphaBottom: Float = 0.56f,
    borderWidth: Dp = 0.85.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isObsidian = LocalSonoraGlassStyle.current == GlassStyle.OBSIDIAN
    val glassBrush = if (isObsidian) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0E0E13),
                Color(0xFF08080B)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                tint.copy(alpha = alphaTop),
                Color(0xFF12121C).copy(alpha = alphaBottom)
            )
        )
    }
    val sheenBrush = if (isObsidian) {
        null
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.13f),
                Color.White.copy(alpha = 0.02f),
                Color.Transparent,
                Color.White.copy(alpha = 0.04f)
            )
        )
    }
    val borderBrush = Brush.linearGradient(
        colors = if (isObsidian) {
            listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.05f))
        } else {
            listOf(
                Color.White.copy(alpha = 0.42f),
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.24f)
            )
        }
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    Column(
        modifier = modifier
            .clip(shape)
            .background(glassBrush)
            .then(if (sheenBrush != null) Modifier.background(sheenBrush) else Modifier)
            .border(BorderStroke(borderWidth, borderBrush), shape)
            .then(clickModifier),
        content = content
    )
}

@Composable
fun SonoraArtwork(
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    isCircular: Boolean = false
) {
    val context = LocalContext.current
    val highResUrl = remember(url) { upgradeArtworkUrl(url) }
    val imageRequest = remember(highResUrl, context) {
        if (highResUrl.isBlank()) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(highResUrl)
                .crossfade(220)
                .allowHardware(true)
                .build()
        }
    }
    val shape = if (isCircular) CircleShape else RoundedCornerShape(cornerRadius)
    val glassRim = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.34f),
            Color.White.copy(alpha = 0.08f),
            Color.White.copy(alpha = 0.22f)
        )
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            placeholder = painterResource(R.drawable.img_album_fallback),
            error = painterResource(R.drawable.img_album_fallback),
            fallback = painterResource(R.drawable.img_album_fallback),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.14f)
                        )
                    )
                )
                .border(BorderStroke(0.8.dp, glassRim), shape)
        )
    }
}

@Composable
fun AdFreeGlassBadge(modifier: Modifier = Modifier) {
    val accent = LocalSonoraAccent.current
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.10f),
        border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.22f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = "Sin anuncios • YouTube Music",
                style = MaterialTheme.typography.labelMedium,
                color = SonoraTextPrimary
            )
        }
    }
}

@Composable
fun AnimatedEqualizer(
    isPlaying: Boolean,
    color: Color = LocalSonoraAccent.current,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val bar1 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "b1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(340, easing = LinearEasing), RepeatMode.Reverse),
        label = "b2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "b3"
    )

    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(bar1, bar2, bar3).forEach { factor ->
            val h = if (isPlaying) (16f * factor).dp else 4.dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isPlayingCurrent: Boolean,
    isActuallyPlaying: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    indexLabel: String? = null
) {
    val accent = LocalSonoraAccent.current
    val bgColor = if (isPlayingCurrent) {
        accent.copy(alpha = 0.14f)
    } else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("track_row_${track.videoId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (indexLabel != null) {
            Text(
                text = indexLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (isPlayingCurrent) accent else SonoraTextTertiary,
                modifier = Modifier.width(26.dp)
            )
        }

        Box(modifier = Modifier.size(52.dp)) {
            SonoraArtwork(
                url = track.artworkUrl,
                contentDescription = track.title,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 10.dp
            )
            if (isPlayingCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedEqualizer(isPlaying = isActuallyPlaying, color = accent)
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlayingCurrent) accent else SonoraTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            val subtitle = listOfNotNull(
                track.artist.takeIf { it.isNotBlank() },
                track.album?.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (track.durationText.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = track.durationText,
                style = MaterialTheme.typography.labelSmall,
                color = SonoraTextTertiary
            )
        }

        IconButton(
            onClick = onMoreClick,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("track_more_${track.videoId}")
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "Más opciones para ${track.title}",
                tint = SonoraTextSecondary
            )
        }
    }
}

@Composable
fun BrowseCardItem(
    card: BrowseCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 156.dp
) {
    val isArtist = card.kind == ItemKind.ARTIST
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "browse_card_scale"
    )
    Column(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick
            )
            .padding(4.dp)
            .testTag("browse_card_${card.id}"),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start
    ) {
        SonoraArtwork(
            url = card.artworkUrl,
            contentDescription = card.title,
            modifier = Modifier
                .size(width - 8.dp),
            cornerRadius = 16.dp,
            isCircular = isArtist
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = card.title,
            style = MaterialTheme.typography.labelLarge,
            color = SonoraTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (card.subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MiniPlayerBar(
    playbackState: PlaybackUiState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit = {},
    isDjLiveActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack ?: return
    val progress = if (playbackState.durationMs > 0L) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val ambientTint by animateColorAsState(
        targetValue = playbackState.ambientPrimary,
        animationSpec = tween(600),
        label = "mini_tint"
    )

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .pointerInput(track.videoId) {
                var totalHorizontal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalHorizontal = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalHorizontal += dragAmount },
                    onDragEnd = {
                        if (totalHorizontal < -80f) onSkipNext()
                        else if (totalHorizontal > 80f) onSkipPrevious()
                    }
                )
            }
            .pointerInput(track.videoId) {
                var totalVertical = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalVertical = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount < 0f) totalVertical += dragAmount
                    },
                    onDragEnd = {
                        if (totalVertical < -55f) onExpand()
                    }
                )
            }
            .testTag("mini_player_bar"),
        shape = RoundedCornerShape(22.dp),
        tint = androidx.compose.ui.graphics.lerp(Color(0xFF1E1E2C), ambientTint, 0.24f),
        alphaTop = 0.54f,
        alphaBottom = 0.72f,
        borderWidth = 0.9.dp,
        onClick = onExpand
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SonoraArtwork(
                url = track.artworkUrl,
                contentDescription = track.title,
                modifier = Modifier.size(46.dp),
                cornerRadius = 10.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = SonoraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDjLiveActive) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = LocalSonoraAccent.current.copy(alpha = 0.26f),
                            border = BorderStroke(0.6.dp, LocalSonoraAccent.current.copy(alpha = 0.7f))
                        ) {
                            Text(
                                text = "DJ",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val accent = LocalSonoraAccent.current
            if (playbackState.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(22.dp),
                    color = accent,
                    strokeWidth = 2.2.dp
                )
            } else {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("mini_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                        tint = SonoraTextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("mini_next_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Siguiente canción",
                    tint = SonoraTextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp),
            color = LocalSonoraAccent.current,
            trackColor = Color.White.copy(alpha = 0.10f)
        )
    }
}

@Composable
fun ErrorStateGlass(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Sin conexión al catálogo",
                style = MaterialTheme.typography.titleLarge,
                color = SonoraTextPrimary
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextSecondary
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = LocalSonoraAccent.current),
                modifier = Modifier.testTag("retry_button")
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reintentar")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsSheet(
    track: Track,
    isLiked: Boolean,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onDismiss: () -> Unit,
    onToggleLike: () -> Unit,
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit,
    onToggleDownload: () -> Unit = {},
    onAddToPlaylist: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onShowStatsForNerds: (() -> Unit)? = null,
    onOpenAlbum: ((String, String) -> Unit)? = null
) {
    val accent = LocalSonoraAccent.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161F),
        contentColor = SonoraTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SonoraArtwork(
                    url = track.artworkUrl,
                    contentDescription = track.title,
                    modifier = Modifier.size(58.dp),
                    cornerRadius = 12.dp
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = SonoraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))

            SheetActionRow(
                icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                iconTint = if (isLiked) accent else SonoraTextPrimary,
                label = if (isLiked) "Quitar de Tus me gusta" else "Agregar a Tus me gusta",
                tag = "sheet_like_button",
                onClick = {
                    onToggleLike()
                    onDismiss()
                }
            )
            SheetActionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                iconTint = accent,
                label = "Reproducir a continuación",
                tag = "sheet_play_next_button",
                onClick = {
                    onPlayNext()
                    onDismiss()
                }
            )
            SheetActionRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Agregar a la cola",
                tag = "sheet_add_queue_button",
                onClick = {
                    onAddToQueue()
                    onDismiss()
                }
            )
            SheetActionRow(
                icon = when {
                    isDownloaded -> Icons.Rounded.DeleteOutline
                    downloadProgress != null -> Icons.Rounded.DownloadDone
                    else -> Icons.Rounded.Download
                },
                iconTint = if (isDownloaded) accent else SonoraTextPrimary,
                label = when {
                    isDownloaded -> "Eliminar descarga sin conexión"
                    downloadProgress != null -> "Descargando (${(downloadProgress * 100).toInt()}%)..."
                    else -> "Descargar para escuchar sin conexión"
                },
                tag = "sheet_download_button",
                onClick = {
                    if (downloadProgress == null) {
                        onToggleDownload()
                        onDismiss()
                    }
                }
            )
            SheetActionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                label = "Agregar a una playlist",
                tag = "sheet_add_playlist_button",
                onClick = {
                    onDismiss()
                    onAddToPlaylist()
                }
            )
            if (onStartRadio != null && !track.isLocal) {
                SheetActionRow(
                    icon = Icons.Rounded.Radio,
                    iconTint = accent,
                    label = "Iniciar radio de esta canción",
                    tag = "sheet_start_radio_button",
                    onClick = {
                        onDismiss()
                        onStartRadio()
                    }
                )
            }
            if (onShowStatsForNerds != null) {
                SheetActionRow(
                    icon = Icons.Rounded.GraphicEq,
                    label = "Estadísticas para geeks (Stats for Nerds)",
                    tag = "sheet_stats_nerds_button",
                    onClick = {
                        onDismiss()
                        onShowStatsForNerds()
                    }
                )
            }
            val albumId = track.albumId
            val albumTitle = track.album
            if (!albumId.isNullOrBlank() && onOpenAlbum != null) {
                SheetActionRow(
                    icon = Icons.Rounded.Album,
                    label = "Ir al álbum",
                    tag = "sheet_open_album_button",
                    onClick = {
                        onDismiss()
                        onOpenAlbum(albumId, albumTitle ?: "Álbum")
                    }
                )
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    iconTint: Color = SonoraTextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    track: Track,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreatePlaylistWithTrack: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161F),
        contentColor = SonoraTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Agregar a playlist",
                style = MaterialTheme.typography.headlineMedium,
                color = SonoraTextPrimary
            )
            Spacer(Modifier.height(12.dp))
            val accent = LocalSonoraAccent.current
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_playlist_from_sheet"),
                shape = RoundedCornerShape(14.dp),
                onClick = { showCreateDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = accent)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Nueva playlist...",
                        style = MaterialTheme.typography.titleMedium,
                        color = accent
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlists, key = { it.id }) { pl ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("select_playlist_${pl.id}"),
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            onSelectPlaylist(pl.id)
                            onDismiss()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SonoraArtwork(
                                url = pl.coverUrl,
                                contentDescription = pl.name,
                                modifier = Modifier.size(46.dp),
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = pl.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, _ ->
                onCreatePlaylistWithTrack(name)
                showCreateDialog = false
                onDismiss()
            }
        )
    }
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    val accent = LocalSonoraAccent.current
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B24),
        titleContentColor = SonoraTextPrimary,
        textContentColor = SonoraTextSecondary,
        title = {
            Text("Nueva playlist", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de la playlist") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        focusedLabelColor = accent,
                        cursorColor = accent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción (opcional)") },
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        focusedLabelColor = accent,
                        cursorColor = accent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_desc_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, description) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.testTag("confirm_create_playlist")
            ) {
                Text("Crear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = SonoraTextSecondary)
            }
        }
    )
}

@Composable
fun StatsForNerdsDialog(
    stats: StatsForNerdsData,
    track: Track?,
    onDismiss: () -> Unit
) {
    val accent = LocalSonoraAccent.current
    val rows = remember(stats, track) {
        listOf(
            "ID de pista" to (track?.videoId ?: stats.videoId).ifBlank { "--" },
            "Origen" to stats.sourceLabel,
            "Códec" to stats.codec,
            "Formato MIME" to stats.mimeType,
            "Bitrate" to if (stats.bitrateKbps > 0) "${stats.bitrateKbps} kbps" else "--",
            "Muestreo" to if (stats.sampleRateHz > 0) "${stats.sampleRateHz} Hz" else "--",
            "Canales / Prof." to if (stats.channelCount > 0) "${stats.channelCount} ch • ${stats.bitDepthLabel}" else stats.bitDepthLabel,
            "Velocidad / Tono" to "${"%.2f".format(stats.playbackSpeed)}× / ${"%.2f".format(stats.playbackPitch)}×",
            "Búfer" to "${stats.bufferPercent}% (${(stats.bufferedMs / 1000L)}s)",
            "Sesión de audio" to if (stats.audioSessionId > 0) "#${stats.audioSessionId}" else "--",
            "Servidor / Fuente" to stats.streamHost
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151520),
        titleContentColor = SonoraTextPrimary,
        textContentColor = SonoraTextSecondary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                Column {
                    Text(
                        text = "Estadísticas para geeks",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (track != null) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = SonoraTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = SonoraTextTertiary,
                            modifier = Modifier.weight(0.45f)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.labelMedium,
                            color = SonoraTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.55f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("stats_nerds_close")) {
                Text("Cerrar", color = accent, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

fun formatTimeMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val minutes = totalSec / 60L
    val seconds = totalSec % 60L
    return "%d:%02d".format(minutes, seconds)
}
