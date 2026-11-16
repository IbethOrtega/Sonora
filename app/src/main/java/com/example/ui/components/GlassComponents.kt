package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.PlaylistEntity
import com.example.model.BrowseCard
import com.example.model.GlassStyle
import com.example.model.ItemKind
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraGlassStyle
import com.example.ui.theme.SonoraBackground
import com.example.ui.theme.SonoraCrimson
import com.example.ui.theme.SonoraGlassBorderBottom
import com.example.ui.theme.SonoraGlassBorderTop
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary

@Composable
fun AmbientBackdrop(
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val animatedPrimary by animateColorAsState(
        targetValue = primaryColor,
        animationSpec = tween(durationMillis = 900),
        label = "ambient_primary"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = secondaryColor,
        animationSpec = tween(durationMillis = 900),
        label = "ambient_secondary"
    )

    val glassStyle = LocalSonoraGlassStyle.current
    val baseBg = if (glassStyle == GlassStyle.OBSIDIAN) Color(0xFF040406) else SonoraBackground
    val glowFactor = if (glassStyle == GlassStyle.OBSIDIAN) 0.55f else 1.0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBg)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedPrimary.copy(alpha = 0.28f * glowFactor),
                            animatedSecondary.copy(alpha = 0.14f * glowFactor),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.22f, size.height * 0.12f),
                        radius = size.maxDimension * 0.72f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedSecondary.copy(alpha = 0.20f * glowFactor),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.88f, size.height * 0.45f),
                        radius = size.maxDimension * 0.55f
                    )
                )
            }
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = Color(0xFF1A1A24),
    alphaTop: Float = 0.68f,
    alphaBottom: Float = 0.82f,
    borderWidth: Dp = 0.8.dp,
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
                Color(0xFF101017).copy(alpha = alphaBottom)
            )
        )
    }
    val borderBrush = Brush.linearGradient(
        colors = if (isObsidian) {
            listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.05f))
        } else {
            listOf(SonoraGlassBorderTop, SonoraGlassBorderBottom)
        }
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    Column(
        modifier = modifier
            .clip(shape)
            .background(glassBrush)
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
    val shape = if (isCircular) CircleShape else RoundedCornerShape(cornerRadius)
    AsyncImage(
        model = url.ifBlank { null },
        contentDescription = contentDescription,
        placeholder = painterResource(R.drawable.img_album_fallback),
        error = painterResource(R.drawable.img_album_fallback),
        fallback = painterResource(R.drawable.img_album_fallback),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(shape)
            .border(
                width = 0.6.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = shape
            )
    )
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
    color: Color = SonoraCrimson,
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
    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
            .testTag("mini_player_bar"),
        shape = RoundedCornerShape(18.dp),
        tint = ambientTint.copy(alpha = 0.25f).let { Color(0xFF1D1D28) },
        alphaTop = 0.84f,
        alphaBottom = 0.92f,
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
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
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
                colors = ButtonDefaults.buttonColors(containerColor = SonoraCrimson),
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
    onDismiss: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: ((String, String) -> Unit)? = null
) {
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
                iconTint = if (isLiked) SonoraCrimson else SonoraTextPrimary,
                label = if (isLiked) "Quitar de Tus me gusta" else "Agregar a Tus me gusta",
                tag = "sheet_like_button",
                onClick = {
                    onToggleLike()
                    onDismiss()
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
            SheetActionRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Agregar a la cola",
                tag = "sheet_add_queue_button",
                onClick = {
                    onAddToQueue()
                    onDismiss()
                }
            )
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
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = SonoraCrimson)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Nueva playlist...",
                        style = MaterialTheme.typography.titleMedium,
                        color = SonoraCrimson
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
                        focusedBorderColor = SonoraCrimson,
                        focusedLabelColor = SonoraCrimson,
                        cursorColor = SonoraCrimson
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
                        focusedBorderColor = SonoraCrimson,
                        focusedLabelColor = SonoraCrimson,
                        cursorColor = SonoraCrimson
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
                colors = ButtonDefaults.buttonColors(containerColor = SonoraCrimson),
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

fun formatTimeMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val minutes = totalSec / 60L
    val seconds = totalSec % 60L
    return "%d:%02d".format(minutes, seconds)
}
