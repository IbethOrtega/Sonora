package com.example.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MotionPhotosAuto
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.DjVoiceEngine
import com.example.player.PlaybackUiState
import com.example.player.SonoraPlaybackBridge
import com.example.ui.components.AnimatedEqualizer
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.formatTimeMs
import com.example.ui.components.upgradeArtworkUrl
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.TabularTimerTextStyle
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CoverModeTogglePill(
    animatedEnabled: Boolean,
    onSelect: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.10f),
        border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverModeChip(
                icon = Icons.Rounded.Image,
                label = "Original",
                selected = !animatedEnabled,
                tag = "cover_mode_original",
                onClick = { onSelect(false) }
            )
            CoverModeChip(
                icon = Icons.Rounded.MotionPhotosAuto,
                label = "Animada",
                selected = animatedEnabled,
                tag = "cover_mode_animated",
                onClick = { onSelect(true) }
            )
        }
    }
}

@Composable
private fun CoverModeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent,
        modifier = Modifier.testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) Color.White else Color.White.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
fun AnimatedCoverArtwork(
    url: String,
    contentDescription: String,
    isPlaying: Boolean,
    ambientPrimary: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val highResUrl = remember(url) { upgradeArtworkUrl(url) }
    val imageRequest = remember(highResUrl, context) {
        if (highResUrl.isBlank()) null
        else ImageRequest.Builder(context)
            .data(highResUrl)
            .crossfade(280)
            .allowHardware(true)
            .build()
    }

    val motion = rememberInfiniteTransition(label = "animated_cover_motion")
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "cover_phase"
    )
    val shimmerSweep by motion.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5_500, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "shimmer_sweep"
    )

    val amp = if (isPlaying) 1f else 0.2f
    val kineticScale = 1.08f + (0.06f * sin(phase) * amp)
    val panX = 14f * cos(phase * 0.7f) * amp
    val panY = 12f * sin(phase * 0.9f) * amp
    val swayRot = 1.4f * sin(phase * 0.5f) * amp

    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = if (isPlaying) 28.dp else 12.dp,
                shape = shape,
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            placeholder = painterResource(R.drawable.img_album_fallback),
            error = painterResource(R.drawable.img_album_fallback),
            fallback = painterResource(R.drawable.img_album_fallback),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = kineticScale
                    scaleY = kineticScale
                    translationX = panX
                    translationY = panY
                    rotationZ = swayRot
                }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val startX = shimmerSweep * size.width
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.14f * amp),
                                ambientPrimary.copy(alpha = 0.08f * amp),
                                Color.Transparent
                            ),
                            start = Offset(startX - size.width * 0.35f, 0f),
                            end = Offset(startX + size.width * 0.35f, size.height)
                        )
                    )
                }
        )
    }
}

@Composable
fun DjLivePlayerBanner(
    djLiveState: DjLiveState,
    onOpenVoiceDj: () -> Unit,
    onSwitchVibe: () -> Unit,
    onStop: () -> Unit
) {
    val accent = LocalSonoraAccent.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.12f),
        border = BorderStroke(0.7.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("np_dj_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedEqualizer(
                isPlaying = djLiveState.isSpeaking,
                color = accent,
                modifier = Modifier.height(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            var showDetail by remember(djLiveState.aiTechnicalDetail) { mutableStateOf(false) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = djLiveState.segmentTitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = accent
                )
                Text(
                    text = djLiveState.spokenCaption.ifBlank { djLiveState.currentVibe.subtitle },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.90f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val bannerNotice = djLiveState.aiNotice
                    ?: if (djLiveState.voiceEngine == DjVoiceEngine.DEVICE) {
                        "Voz del dispositivo"
                    } else null
                if (!bannerNotice.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = bannerNotice,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!djLiveState.aiTechnicalDetail.isNullOrBlank()) {
                            Text(
                                text = if (showDetail) "Ocultar" else "Ver detalle",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = accent,
                                modifier = Modifier
                                    .clickable { showDetail = !showDetail }
                                    .testTag("np_dj_detail_toggle")
                            )
                        }
                    }
                    if (showDetail && !djLiveState.aiTechnicalDetail.isNullOrBlank()) {
                        Text(
                            text = djLiveState.aiTechnicalDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(
                onClick = onOpenVoiceDj,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("np_dj_banner_voice_button")
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = "Hablar con DJ Sony", tint = accent, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onSwitchVibe,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("np_dj_switch_vibe_button")
            ) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Cambiar vibra", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("np_dj_stop_button")
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = "Detener DJ Sony", tint = accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun LiveAudioVisualizerStrip(
    accent: Color,
    modifier: Modifier = Modifier
) {
    val fftBars by SonoraPlaybackBridge.visualizerBars.collectAsState()
    val hasLiveFft = remember(fftBars) { fftBars.any { it > 0.02f } }
    val anim = rememberInfiniteTransition(label = "live_visualizer_fallback")
    val phase by anim.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart
        ),
        label = "vis_phase"
    )
    Canvas(modifier = modifier.height(20.dp)) {
        val barCount = 20
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        for (i in 0 until barCount) {
            val energy = if (hasLiveFft) {
                val srcIdx = (i * fftBars.size) / barCount
                fftBars.getOrElse(srcIdx) { 0.1f }.coerceIn(0.12f, 1f)
            } else {
                val wave = 0.5f + 0.5f * sin(phase * 1.4f + i * 0.52f)
                (0.20f + 0.65f * wave).coerceIn(0.14f, 0.92f)
            }
            val h = (size.height * energy).coerceAtLeast(3.dp.toPx())
            val x = i * (barWidth + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.85f), accent.copy(alpha = 0.75f))
                ),
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun DrivingModeOverlay(
    track: Track,
    playbackState: PlaybackUiState,
    positionMsFlow: StateFlow<Long>,
    djLiveActive: Boolean,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenVoiceDj: () -> Unit
) {
    BackHandler { onDismiss() }
    val accent = LocalSonoraAccent.current
    val posMs by positionMsFlow.collectAsStateWithLifecycle()
    val duration = playbackState.durationMs.coerceAtLeast(1L)
    val fraction = (posMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF5090A10),
                        playbackState.ambientPrimary.copy(alpha = 0.30f),
                        Color(0xFA06070B)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 18.dp)
            .testTag("driving_mode_overlay")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accent.copy(alpha = 0.22f),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("Modo Conducción", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                }

                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("driving_mode_close_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Salir", tint = Color.White)
                        Text("Salir", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SonoraArtwork(
                    url = track.artworkUrl,
                    contentDescription = track.title,
                    modifier = Modifier.size(152.dp),
                    cornerRadius = 24.dp
                )
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = accent,
                    trackColor = Color.White.copy(alpha = 0.22f)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTimeMs(posMs), style = TabularTimerTextStyle, color = Color.White.copy(alpha = 0.85f))
                    Text(formatTimeMs(playbackState.durationMs), style = TabularTimerTextStyle, color = Color.White.copy(alpha = 0.85f))
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onSkipPrevious,
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                        modifier = Modifier.size(72.dp).testTag("driving_prev_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Anterior", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }

                    Surface(
                        onClick = onTogglePlayPause,
                        shape = CircleShape,
                        color = accent,
                        modifier = Modifier.size(92.dp).testTag("driving_play_pause_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(50.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onSkipNext,
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                        modifier = Modifier.size(72.dp).testTag("driving_next_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Siguiente", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                }

                Surface(
                    onClick = onOpenVoiceDj,
                    shape = RoundedCornerShape(50),
                    color = if (djLiveActive) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (djLiveActive) "Hablar con DJ Sony" else "Pedir mix a DJ Sony",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
