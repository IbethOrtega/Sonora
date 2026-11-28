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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MotionPhotosAuto
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import com.example.model.RepeatMode as PlayerRepeatMode
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
import com.example.model.ArtistCredit
import com.example.model.BrowseCard
import com.example.model.GlassStyle
import com.example.model.ItemKind
import com.example.model.StatsForNerdsData
import com.example.model.Track
import com.example.model.splitArtistNames
import com.example.player.PlaybackUiState
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.lerp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.SuccessResult
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraAccentSecondary
import com.example.ui.theme.LocalSonoraGlassStyle
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraBackground
import com.example.ui.theme.SonoraGlassBorderBottom
import com.example.ui.theme.SonoraGlassBorderTop
import com.example.ui.theme.SonoraSwitchActive
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.computeDominantTopColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.viewmodel.TrackOptionsMetadataState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    val palette = LocalSonoraPalette.current
    val glassStyle = LocalSonoraGlassStyle.current
    val topColor by animateColorAsState(
        targetValue = palette.ambientTop,
        animationSpec = tween(durationMillis = 600),
        label = "ambient_top"
    )
    val driftTransition = rememberInfiniteTransition(label = "ambient_drift")
    val driftProgress by driftTransition.animateFloat(
        initialValue = -0.06f,
        targetValue = 0.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambient_drift_offset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .then(
                if (glassStyle == GlassStyle.LIQUID) {
                    Modifier.drawBehind {
                        val h = size.height.coerceAtLeast(1f)
                        val w = size.width.coerceAtLeast(1f)
                        val fadeEndY = h * (0.38f + driftProgress * 0.5f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    topColor,
                                    topColor.copy(alpha = topColor.alpha * 0.45f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = fadeEndY
                            )
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    topColor.copy(alpha = 0.22f),
                                    Color.Transparent
                                ),
                                center = Offset(w * (0.5f + driftProgress), h * 0.06f),
                                radius = w * 0.92f
                            )
                        )
                    }
                } else {
                    Modifier
                }
            )
    )
}

@Composable
fun DominantCoverTopGradient(
    artworkUrl: String,
    modifier: Modifier = Modifier,
    fallbackColor: Color? = null
) {
    val context = LocalContext.current
    val palette = LocalSonoraPalette.current
    val defaultTop = fallbackColor ?: palette.ambientTop.copy(alpha = 1f)
    var extractedColor by remember(artworkUrl) { mutableStateOf(defaultTop) }

    LaunchedEffect(artworkUrl, context) {
        if (artworkUrl.isBlank()) {
            extractedColor = defaultTop
            return@LaunchedEffect
        }
        val dominant = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(upgradeArtworkUrl(artworkUrl))
                    .size(96, 96)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                val bmp = (result?.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
                val pal = Palette.from(bmp).maximumColorCount(8).generate()
                val rgb = pal.dominantSwatch?.rgb
                    ?: pal.vibrantSwatch?.rgb
                    ?: pal.mutedSwatch?.rgb
                    ?: return@runCatching null
                computeDominantTopColor(Color(rgb))
            }.getOrNull()
        }
        extractedColor = dominant ?: defaultTop
    }

    val animatedTop by animateColorAsState(
        targetValue = extractedColor,
        animationSpec = tween(durationMillis = 600),
        label = "dominant_cover_top"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .drawBehind {
                val fadeEndY = size.height.coerceAtLeast(1f) * 0.55f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            animatedTop.copy(alpha = 0.88f),
                            animatedTop.copy(alpha = 0.42f),
                            palette.background
                        ),
                        startY = 0f,
                        endY = fadeEndY
                    )
                )
            }
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color? = null,
    alphaTop: Float = 1f,
    alphaBottom: Float = 1f,
    borderWidth: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalSonoraPalette.current
    val surfaceColor = tint ?: palette.surface
    val cardBg = if (alphaTop >= 0.99f && alphaBottom >= 0.99f) {
        surfaceColor
    } else {
        surfaceColor.copy(alpha = ((alphaTop + alphaBottom) * 0.5f).coerceIn(0.4f, 1f))
    }
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier
    val borderMod = if (borderWidth > 0.dp) {
        Modifier.border(BorderStroke(borderWidth, Color.White.copy(alpha = 0.10f)), shape)
    } else Modifier

    Column(
        modifier = modifier
            .clip(shape)
            .background(cardBg)
            .then(borderMod)
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
    }
}

@Composable
fun SonoraIosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) SonoraSwitchActive else Color.White.copy(alpha = 0.22f),
        animationSpec = tween(220),
        label = "ios_switch_track"
    )
    val thumbFraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
        label = "ios_switch_thumb"
    )
    Box(
        modifier = modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(CircleShape)
            .background(if (enabled) trackColor else trackColor.copy(alpha = 0.45f))
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset((thumbFraction * 20.dp.toPx()).roundToInt(), 0) }
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun SonoraLogoGlassBadge(modifier: Modifier = Modifier) {
    val accent = LocalSonoraAccent.current
    Box(
        modifier = modifier.sonoraLiquidGlassSurface(
            shape = RoundedCornerShape(50),
            blurRadius = 10.dp,
            refractionHeight = 6.dp,
            refractionAmount = 10.dp,
            surfaceAlpha = 0.44f
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Canvas(modifier = Modifier.size(16.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.78f, h * 0.22f)
                    cubicTo(w * 0.45f, h * 0.05f, w * 0.14f, h * 0.26f, w * 0.50f, h * 0.50f)
                    cubicTo(w * 0.86f, h * 0.74f, w * 0.55f, h * 0.95f, w * 0.22f, h * 0.78f)
                }
                drawPath(
                    path = path,
                    color = accent,
                    style = Stroke(width = w * 0.20f, cap = StrokeCap.Round)
                )
            }
            Text(
                text = "Sonora",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp
                ),
                color = SonoraTextPrimary
            )
        }
    }
}

@Composable
fun AdFreeGlassBadge(modifier: Modifier = Modifier) = SonoraLogoGlassBadge(modifier = modifier)

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
    indexLabel: String? = null,
    showDivider: Boolean = false
) {
    val accent = LocalSonoraAccent.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Transparent)
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
                            .background(Color.Black.copy(alpha = 0.52f)),
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
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(
                    start = if (indexLabel != null) 100.dp else 74.dp,
                    end = 12.dp
                ),
                color = Color.White.copy(alpha = 0.08f)
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
            cornerRadius = 11.dp,
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
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val track = playbackState.currentTrack ?: return
    val progress = if (playbackState.durationMs > 0L) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            .sonoraLiquidGlassSurface(
                shape = RoundedCornerShape(if (compact) 50 else 24),
                blurRadius = 8.dp,
                refractionHeight = 10.dp,
                refractionAmount = 16.dp,
                surfaceAlpha = 0.56f
            )
            .clickable(onClick = onExpand)
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
            .testTag("mini_player_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 6.dp else 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SonoraArtwork(
                url = track.artworkUrl,
                contentDescription = track.title,
                modifier = Modifier.size(if (compact) 36.dp else 44.dp),
                cornerRadius = if (compact) 18.dp else 10.dp
            )
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.title,
                        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                        color = SonoraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDjLiveActive && !compact) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = LocalSonoraAccent.current.copy(alpha = 0.26f)
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
                if (!compact) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val accent = LocalSonoraAccent.current
            if (playbackState.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = if (compact) 6.dp else 12.dp)
                        .size(20.dp),
                    color = accent,
                    strokeWidth = 2.2.dp
                )
            } else {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .then(if (compact) Modifier.size(38.dp) else Modifier.minimumInteractiveComponentSize())
                        .testTag("mini_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                        tint = SonoraTextPrimary,
                        modifier = Modifier.size(if (compact) 22.dp else 28.dp)
                    )
                }
            }
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier
                    .then(if (compact) Modifier.size(38.dp) else Modifier.minimumInteractiveComponentSize())
                    .testTag("mini_next_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.FastForward,
                    contentDescription = "Siguiente canción",
                    tint = SonoraTextPrimary,
                    modifier = Modifier.size(if (compact) 21.dp else 26.dp)
                )
            }
        }
        if (!compact) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = LocalSonoraAccent.current,
                trackColor = Color.White.copy(alpha = 0.10f)
            )
        }
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

data class NowPlayingMenuActions(
    val animatedArtworkEnabled: Boolean,
    val canvasStatusText: String = "",
    val autoMixEnabled: Boolean,
    val shuffleEnabled: Boolean,
    val repeatMode: PlayerRepeatMode,
    val djLiveActive: Boolean,
    val onToggleAnimatedArtwork: (Boolean) -> Unit,
    val onToggleAutoMix: (Boolean) -> Unit,
    val onToggleShuffle: () -> Unit,
    val onCycleRepeat: () -> Unit,
    val onOpenVoiceDj: () -> Unit,
    val onOpenDrivingMode: () -> Unit,
    val onOpenEqualizer: () -> Unit,
    val onOpenAudioOutput: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsSheet(
    track: Track,
    isLiked: Boolean,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    metadataState: TrackOptionsMetadataState = TrackOptionsMetadataState(
        videoId = track.videoId,
        artistId = track.artistId,
        album = track.album,
        albumId = track.albumId
    ),
    canRemoveFromPlaylist: Boolean = false,
    canRemoveFromQueue: Boolean = false,
    nowPlayingActions: NowPlayingMenuActions? = null,
    onDismiss: () -> Unit,
    onToggleLike: () -> Unit,
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit,
    onToggleDownload: () -> Unit = {},
    onAddToPlaylist: () -> Unit,
    onMixWithAnother: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onShowStatsForNerds: (() -> Unit)? = null,
    onOpenArtist: ((String, String?) -> Unit)? = null,
    onOpenAlbum: ((String, String) -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null
) {
    val accent = LocalSonoraAccent.current
    var showArtistPicker by remember(track.videoId) { mutableStateOf(false) }

    val isYouTubeTrack = !track.isLocal && !track.videoId.startsWith("local_") && track.videoId.isNotBlank()
    val matchedMeta = metadataState.takeIf { it.videoId == track.videoId }
    val isResolvingMeta = matchedMeta?.isResolving == true
    val splitNames = remember(track.artist) { splitArtistNames(track.artist) }
    val effectiveArtistId = matchedMeta?.artistId?.takeIf { it.isNotBlank() }
        ?: track.artistId?.takeIf { it.isNotBlank() }
    val artistCredits = remember(track.videoId, matchedMeta, splitNames, effectiveArtistId) {
        val fromMeta = matchedMeta?.artists.orEmpty()
        when {
            fromMeta.isNotEmpty() -> fromMeta
            splitNames.isNotEmpty() -> splitNames.mapIndexed { idx, name ->
                ArtistCredit(name = name, browseId = if (idx == 0) effectiveArtistId else null)
            }
            else -> emptyList()
        }
    }
    val hasArtistTarget = effectiveArtistId != null || artistCredits.any { !it.browseId.isNullOrBlank() }

    val effectiveAlbumId = matchedMeta?.albumId?.takeIf { it.isNotBlank() }
        ?: track.albumId?.takeIf { it.isNotBlank() }
    val effectiveAlbumTitle = matchedMeta?.album?.takeIf { it.isNotBlank() }
        ?: track.album?.takeIf { it.isNotBlank() }

    SonoraLiquidModalSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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

            // 1. Reproducir a continuación
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
            // 2. Agregar a la cola
            SheetActionRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Agregar a la cola",
                tag = "sheet_add_queue_button",
                onClick = {
                    onAddToQueue()
                    onDismiss()
                }
            )
            // 3. Iniciar radio
            if (onStartRadio != null && isYouTubeTrack) {
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
            // 4. Agregar a una playlist
            SheetActionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                label = "Agregar a una playlist",
                tag = "sheet_add_playlist_button",
                onClick = {
                    onDismiss()
                    onAddToPlaylist()
                }
            )
            // 5. Tus me gusta
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
            // 6. Descargar
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
            // 7. Ver artista
            if (isYouTubeTrack && onOpenArtist != null && (hasArtistTarget || isResolvingMeta)) {
                val showSpinner = isResolvingMeta && !hasArtistTarget
                SheetActionRow(
                    icon = Icons.Rounded.Person,
                    label = "Ver artista",
                    tag = "sheet_open_artist_button",
                    enabled = !showSpinner,
                    isLoading = showSpinner,
                    onClick = {
                        if (artistCredits.size > 1) {
                            showArtistPicker = true
                        } else {
                            val single = artistCredits.firstOrNull()
                            val name = single?.name?.ifBlank { track.artist } ?: track.artist
                            val id = single?.browseId ?: effectiveArtistId
                            onDismiss()
                            onOpenArtist(name, id)
                        }
                    }
                )
            }
            // 8. Ir al álbum
            if (isYouTubeTrack && onOpenAlbum != null && (effectiveAlbumId != null || isResolvingMeta)) {
                val showSpinner = isResolvingMeta && effectiveAlbumId == null
                SheetActionRow(
                    icon = Icons.Rounded.Album,
                    label = "Ir al álbum",
                    tag = "sheet_open_album_button",
                    enabled = !showSpinner && effectiveAlbumId != null,
                    isLoading = showSpinner,
                    onClick = {
                        val id = effectiveAlbumId ?: return@SheetActionRow
                        onDismiss()
                        onOpenAlbum(id, effectiveAlbumTitle ?: "Álbum")
                    }
                )
            }
            // 9. Compartir
            if (isYouTubeTrack && onShare != null) {
                SheetActionRow(
                    icon = Icons.Rounded.Share,
                    label = "Compartir",
                    tag = "sheet_share_button",
                    onClick = {
                        onDismiss()
                        onShare()
                    }
                )
            }
            // 10. Mezclar con otra canción
            if (onMixWithAnother != null) {
                SheetActionRow(
                    icon = Icons.Rounded.GraphicEq,
                    iconTint = accent,
                    label = "Mezclar con otra canción",
                    tag = "sheet_mix_track_button",
                    onClick = {
                        onDismiss()
                        onMixWithAnother()
                    }
                )
            }
            // 11. Estadísticas para geeks
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
            // 11b. Opciones de reproducción movidas al menú del reproductor
            if (nowPlayingActions != null) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Text(
                    text = "Reproductor y sonido",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = SonoraTextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                SheetActionRow(
                    icon = Icons.Rounded.MotionPhotosAuto,
                    iconTint = if (nowPlayingActions.animatedArtworkEnabled) accent else SonoraTextPrimary,
                    label = "Portada animada",
                    subtitle = nowPlayingActions.canvasStatusText.ifBlank {
                        if (nowPlayingActions.animatedArtworkEnabled) "Activada (toca para desactivar)" else "Sin portada animada — Desactivado"
                    },
                    tag = "sheet_toggle_animated_artwork_button",
                    onClick = {
                        nowPlayingActions.onToggleAnimatedArtwork(!nowPlayingActions.animatedArtworkEnabled)
                        onDismiss()
                    }
                )
                SheetActionRow(
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = if (nowPlayingActions.autoMixEnabled) accent else SonoraTextPrimary,
                    label = if (nowPlayingActions.autoMixEnabled) "AutoMix: Activado" else "AutoMix: Desactivado",
                    tag = "sheet_toggle_automix_button",
                    onClick = {
                        nowPlayingActions.onToggleAutoMix(!nowPlayingActions.autoMixEnabled)
                        onDismiss()
                    }
                )
                SheetActionRow(
                    icon = Icons.Rounded.Shuffle,
                    iconTint = if (nowPlayingActions.shuffleEnabled) accent else SonoraTextPrimary,
                    label = if (nowPlayingActions.shuffleEnabled) "Aleatorio: Activado" else "Aleatorio: Desactivado",
                    tag = "now_playing_shuffle_button",
                    onClick = {
                        nowPlayingActions.onToggleShuffle()
                        onDismiss()
                    }
                )
                SheetActionRow(
                    icon = if (nowPlayingActions.repeatMode == PlayerRepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    iconTint = if (nowPlayingActions.repeatMode != PlayerRepeatMode.OFF) accent else SonoraTextPrimary,
                    label = when (nowPlayingActions.repeatMode) {
                        PlayerRepeatMode.OFF -> "Repetir: Desactivado"
                        PlayerRepeatMode.ALL -> "Repetir: Toda la cola"
                        PlayerRepeatMode.ONE -> "Repetir: Esta canción"
                    },
                    tag = "now_playing_repeat_button",
                    onClick = {
                        nowPlayingActions.onCycleRepeat()
                        onDismiss()
                    }
                )
                SheetActionRow(
                    icon = Icons.Rounded.Mic,
                    iconTint = if (nowPlayingActions.djLiveActive) accent else SonoraTextPrimary,
                    label = "DJ Sony por voz",
                    tag = "sheet_voice_dj_button",
                    onClick = {
                        onDismiss()
                        nowPlayingActions.onOpenVoiceDj()
                    }
                )
                SheetActionRow(
                    icon = Icons.Rounded.DirectionsCar,
                    label = "Modo auto",
                    tag = "sheet_driving_mode_button",
                    onClick = {
                        onDismiss()
                        nowPlayingActions.onOpenDrivingMode()
                    }
                )
                SheetActionRow(
                    icon = Icons.Rounded.Equalizer,
                    iconTint = accent,
                    label = "Calidad y ecualizador",
                    tag = "sheet_quality_eq_button",
                    onClick = {
                        onDismiss()
                        nowPlayingActions.onOpenEqualizer()
                    }
                )
                SheetActionRow(
                    icon = Icons.Rounded.Headphones,
                    label = "Salida de audio",
                    tag = "sheet_audio_output_button",
                    onClick = {
                        onDismiss()
                        nowPlayingActions.onOpenAudioOutput()
                    }
                )
            }
            // 12. Al final: Quitar de esta playlist / Quitar de la cola
            if (canRemoveFromPlaylist && onRemoveFromPlaylist != null) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                SheetActionRow(
                    icon = Icons.Rounded.DeleteOutline,
                    iconTint = accent,
                    label = "Quitar de esta playlist",
                    tag = "sheet_remove_from_playlist_button",
                    onClick = {
                        onRemoveFromPlaylist()
                        onDismiss()
                    }
                )
            }
            if (canRemoveFromQueue && onRemoveFromQueue != null) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                SheetActionRow(
                    icon = Icons.Rounded.Close,
                    iconTint = accent,
                    label = "Quitar de la cola",
                    tag = "sheet_remove_from_queue_button",
                    onClick = {
                        onRemoveFromQueue()
                        onDismiss()
                    }
                )
            }
        }
    }

    if (showArtistPicker && onOpenArtist != null) {
        AlertDialog(
            onDismissRequest = { showArtistPicker = false },
            containerColor = Color(0xFF1A1A26),
            titleContentColor = SonoraTextPrimary,
            textContentColor = SonoraTextSecondary,
            title = {
                Text(
                    text = "Elegir artista",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    artistCredits.forEachIndexed { idx, credit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showArtistPicker = false
                                    onDismiss()
                                    onOpenArtist(credit.name, credit.browseId)
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .testTag("artist_picker_option_$idx"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = credit.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = SonoraTextPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showArtistPicker = false }) {
                    Text("Cancelar", color = accent)
                }
            }
        )
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    subtitle: String? = null,
    iconTint: Color = SonoraTextPrimary,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val contentAlpha = if (enabled) 1f else 0.55f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint.copy(alpha = contentAlpha),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = SonoraTextPrimary.copy(alpha = contentAlpha)
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isLoading) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .testTag("${tag}_loading"),
                strokeWidth = 2.dp,
                color = LocalSonoraAccent.current
            )
        }
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

    SonoraLiquidModalSheet(
        onDismissRequest = onDismiss
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
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
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
