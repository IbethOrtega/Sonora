package com.example.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.PlaybackOrigin
import com.example.model.RepeatMode
import com.example.model.TrackLyrics
import com.example.ui.components.AnimatedEqualizer
import com.example.ui.components.formatTimeMs
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.TabularTimerTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

@Composable
fun PlaybackOriginCaption(
    origin: PlaybackOrigin?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (origin == null || origin.label.isBlank()) return
    val isNavigable = origin.browseId != null || origin.playlistId != null || origin.tabRoute != null
    val prefix = stringResource(R.string.playing_from_prefix)
    Surface(
        onClick = { if (isNavigable) onClick() },
        enabled = isNavigable,
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)),
        modifier = modifier.testTag("player_origin_caption")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$prefix ${origin.label}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.15.sp
                ),
                color = Color.White.copy(alpha = 0.84f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (isNavigable) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Ir a ${origin.label}",
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .testTag("player_explicit_badge"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "E",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 11.sp
            ),
            color = Color.White.copy(alpha = 0.92f)
        )
    }
}

@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    startDelayMillis: Long = 900L,
    velocityDpPerSec: Float = 32f
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 40.dp.toPx() }
    val speedPxPerSec = with(density) { velocityDpPerSec.dp.toPx() }.coerceAtLeast(1f)
    var overflowPx by remember(text) { mutableIntStateOf(0) }
    var textWidthPx by remember(text) { mutableIntStateOf(0) }
    val offsetX = remember(text) { Animatable(0f) }

    LaunchedEffect(text, overflowPx, textWidthPx) {
        offsetX.snapTo(0f)
        if (overflowPx <= 2 || textWidthPx <= 0) return@LaunchedEffect
        delay(startDelayMillis)
        val travel = textWidthPx.toFloat() + gapPx
        val durationMs = ((travel / speedPxPerSec) * 1000f).roundToInt().coerceAtLeast(1500)
        while (true) {
            offsetX.animateTo(
                targetValue = -travel,
                animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
            )
            offsetX.snapTo(0f)
            delay(1800L)
        }
    }

    val isScrolling = overflowPx > 2
    val edgeFadeModifier = if (isScrolling) {
        Modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.05f to Color.Black,
                        0.92f to Color.Black,
                        1.0f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .then(edgeFadeModifier)
    ) {
        SubcomposeLayout { constraints ->
            val looseConstraints = constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE)
            val primaryPlaceable = subcompose("primary") {
                Text(
                    text = text,
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false
                )
            }.first().measure(looseConstraints)

            val containerW = constraints.maxWidth.coerceAtLeast(1)
            val measuredTextW = primaryPlaceable.width
            val diff = (measuredTextW - containerW).coerceAtLeast(0)
            if (diff != overflowPx) overflowPx = diff
            if (measuredTextW != textWidthPx) textWidthPx = measuredTextW

            val duplicatePlaceable = if (diff > 2) {
                subcompose("duplicate") {
                    Text(
                        text = text,
                        style = style,
                        color = color,
                        maxLines = 1,
                        softWrap = false
                    )
                }.first().measure(looseConstraints)
            } else null

            layout(
                width = minOf(containerW, maxOf(measuredTextW, 0)).coerceAtLeast(
                    if (constraints.hasFixedWidth) containerW else minOf(containerW, measuredTextW)
                ),
                height = primaryPlaceable.height
            ) {
                val shift = if (diff > 2) offsetX.value.roundToInt() else 0
                primaryPlaceable.placeRelative(IntOffset(shift, 0))
                if (duplicatePlaceable != null) {
                    val secondX = shift + measuredTextW + gapPx.roundToInt()
                    duplicatePlaceable.placeRelative(IntOffset(secondX, 0))
                }
            }
        }
    }
}

@Composable
fun GlassDiscPair(
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onOpenOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalSonoraAccent.current
    val haptics = rememberPlayerHaptics()
    val heartScale by animateFloatAsState(
        targetValue = if (isLiked) 1.12f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "glass_heart_scale"
    )
    val heartTint by animateColorAsState(
        targetValue = if (isLiked) accent else Color.White.copy(alpha = 0.92f),
        animationSpec = tween(220),
        label = "glass_heart_tint"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(
            onClick = {
                haptics.confirm()
                onToggleLike()
            },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("now_playing_like_button")
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (isLiked) 0.20f else 0.13f))
                    .border(
                        width = 0.7.dp,
                        color = if (isLiked) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.18f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isLiked) "Quitar me gusta" else "Me gusta",
                    tint = heartTint,
                    modifier = Modifier
                        .size(19.dp)
                        .scale(heartScale)
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
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.13f))
                    .border(0.7.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = "Más opciones",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
fun CurrentLyricStrip(
    lyrics: TrackLyrics?,
    positionMsFlow: StateFlow<Long>,
    lyricsOffsetMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lines = lyrics?.lines.orEmpty()
    if (lines.isEmpty()) return

    val positionMs by positionMsFlow.collectAsStateWithLifecycle()
    val effectiveMs = (positionMs + lyricsOffsetMs).coerceAtLeast(0L)
    val currentLine = remember(lines, lyrics?.isSynced, effectiveMs) {
        if (lyrics?.isSynced != true) {
            lines.firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
        } else {
            var activeText = ""
            for (line in lines) {
                if (line.timeMs <= effectiveMs + 180L) {
                    if (line.text.isNotBlank()) activeText = line.text
                } else {
                    break
                }
            }
            activeText.ifBlank { lines.firstOrNull { it.text.isNotBlank() }?.text.orEmpty() }
        }
    }

    if (currentLine.isBlank()) return

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("current_lyric_strip")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Lyrics,
                contentDescription = "Abrir letras",
                tint = LocalSonoraAccent.current,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = currentLine,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ThinSlider(
    positionMsFlow: StateFlow<Long>,
    durationMs: Long,
    crossfadeZoneFraction: Float,
    codecBadgeText: String,
    isPlaying: Boolean,
    hasActiveAudioFx: Boolean,
    accent: Color,
    onOpenAudioSheet: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val livePosMs by positionMsFlow.collectAsStateWithLifecycle()
    val safeDuration = durationMs.coerceAtLeast(1L)

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(1) }

    val haptics = rememberPlayerHaptics()
    val liveFraction = (livePosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val fraction = if (isScrubbing) scrubFraction else liveFraction
    val displayPosMs = if (isScrubbing) (scrubFraction * safeDuration).toLong() else livePosMs

    val trackHeight by animateDpAsState(
        targetValue = if (isScrubbing) 9.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "thin_slider_height"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(safeDuration) {
                    detectTapGestures { offset ->
                        haptics.tick()
                        val f = (offset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        onSeekTo((f * safeDuration).toLong())
                    }
                }
                .pointerInput(safeDuration) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            haptics.tick()
                            isScrubbing = true
                            scrubFraction = (startOffset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            scrubFraction = (change.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            haptics.tick()
                            onSeekTo((scrubFraction * safeDuration).toLong())
                            isScrubbing = false
                        },
                        onDragCancel = {
                            isScrubbing = false
                        }
                    )
                }
                .testTag("now_playing_slider"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
            ) {
                val h = size.height
                val radius = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f),
                    cornerRadius = radius
                )
                if (crossfadeZoneFraction > 0.005f) {
                    val zoneW = size.width * crossfadeZoneFraction.coerceIn(0f, 0.3f)
                    drawRoundRect(
                        color = accent.copy(alpha = 0.40f),
                        topLeft = Offset(size.width - zoneW, 0f),
                        size = Size(zoneW, h),
                        cornerRadius = radius
                    )
                }
                val progressW = size.width * fraction.coerceIn(0f, 1f)
                if (progressW > 0.5f) {
                    drawRoundRect(
                        color = if (isScrubbing) Color.White else Color.White.copy(alpha = 0.84f),
                        size = Size(progressW, h),
                        cornerRadius = radius
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTimeMs(displayPosMs),
                style = TabularTimerTextStyle,
                color = if (isScrubbing) Color.White else Color.White.copy(alpha = 0.58f)
            )

            Text(
                text = "-${formatTimeMs((safeDuration - displayPosMs).coerceAtLeast(0L))}",
                style = TabularTimerTextStyle,
                color = if (isScrubbing) Color.White else Color.White.copy(alpha = 0.58f)
            )
        }
    }
}

@Composable
fun TransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    accent: Color,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberPlayerHaptics()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                haptics.tick()
                onSkipPrevious()
            },
            modifier = Modifier
                .size(64.dp)
                .testTag("now_playing_prev_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "Anterior",
                tint = Color.White,
                modifier = Modifier.size(46.dp)
            )
        }

        IconButton(
            onClick = {
                haptics.tick()
                onTogglePlayPause()
            },
            modifier = Modifier
                .size(76.dp)
                .testTag("now_playing_play_pause_button")
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        IconButton(
            onClick = {
                haptics.tick()
                onSkipNext()
            },
            modifier = Modifier
                .size(64.dp)
                .testTag("now_playing_next_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Siguiente",
                tint = Color.White,
                modifier = Modifier.size(46.dp)
            )
        }
    }
}

@Composable
fun VolumeRow(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVol = remember(audioManager) {
        (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
    }

    var currentVolFraction by remember {
        mutableFloatStateOf(
            audioManager?.let {
                (it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
            } ?: 0.7f
        )
    }
    var isDragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }

    DisposableEffect(context, audioManager) {
        val syncVolume = {
            if (!isDragging && audioManager != null) {
                currentVolFraction = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat())
                    .coerceIn(0f, 1f)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                syncVolume()
            }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                syncVolume()
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching {
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    fun applyVolumeFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        currentVolFraction = clamped
        val targetInt = (clamped * maxVol).roundToInt().coerceIn(0, maxVol)
        runCatching {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetInt, 0)
        }
    }

    val barHeight by animateDpAsState(
        targetValue = if (isDragging) 9.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "volume_row_height"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        IconButton(
            onClick = { applyVolumeFraction(0f) },
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Icon(
                imageVector = if (currentVolFraction <= 0.02f) {
                    Icons.AutoMirrored.Rounded.VolumeMute
                } else {
                    Icons.AutoMirrored.Rounded.VolumeDown
                },
                contentDescription = "Silenciar",
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(19.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        applyVolumeFraction(offset.x / widthPx.toFloat())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            isDragging = true
                            applyVolumeFraction(startOffset.x / widthPx.toFloat())
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            applyVolumeFraction(change.position.x / widthPx.toFloat())
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.20f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentVolFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = if (isDragging) 0.96f else 0.78f))
                )
            }
        }

        IconButton(
            onClick = { applyVolumeFraction(1f) },
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = "Volumen máximo",
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
fun PlayerActionRow(
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    djLiveActive: Boolean,
    isLyricsOpen: Boolean,
    isQueueOpen: Boolean,
    queueCount: Int,
    onToggleLyrics: () -> Unit,
    onToggleDj: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenAudioOutput: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalSonoraAccent.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionGlyphButton(
            icon = Icons.Rounded.Lyrics,
            contentDescription = "Letras",
            selected = isLyricsOpen,
            accent = accent,
            tag = "tab_lyrics_button",
            onClick = onToggleLyrics
        )
        ActionGlyphButton(
            icon = Icons.Rounded.Headphones,
            contentDescription = "Salida de audio",
            selected = false,
            accent = accent,
            tag = "np_audio_output_button",
            onClick = onOpenAudioOutput
        )
        ActionGlyphButton(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = "Cola ($queueCount)",
            selected = isQueueOpen,
            accent = accent,
            tag = "tab_queue_button",
            onClick = onToggleQueue
        )
    }
}

@Composable
private fun ActionGlyphButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    val haptics = rememberPlayerHaptics()
    Surface(
        onClick = {
            haptics.tick()
            onClick()
        },
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f),
        border = BorderStroke(
            0.7.dp,
            if (selected) accent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.14f)
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(tag)
    ) {
        Box(
            modifier = Modifier.size(42.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
