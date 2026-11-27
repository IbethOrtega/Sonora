package com.example.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.model.QueueMoodFilter
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.AnimatedEqualizer
import com.example.ui.components.SonoraArtwork
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import kotlin.math.roundToInt

/**
 * Clean-room InlineQueue pane with sticky header, drag-to-reorder handle,
 * horizontal swipe-to-remove gesture, AutoMix coherence badge, mood chips,
 * and Song Mixer launcher.
 */
@Composable
fun QueuePane(
    playbackState: PlaybackUiState,
    onSkipToIndex: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onClearQueue: () -> Unit = {},
    onOpenItemOptions: (Track, Int) -> Unit,
    onRetuneQueueMood: (QueueMoodFilter) -> Unit,
    onOpenSongMixer: () -> Unit = {},
    onDismissQueue: (() -> Unit)? = null
) {
    val accent = LocalSonoraAccent.current
    val haptics = rememberPlayerHaptics()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 64.dp.toPx() }
    val swipeRemoveThresholdPx = with(density) { 108.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header (drag downward to close InlineQueue when onDismissQueue is wired)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onDismissQueue != null) {
                        Modifier.pointerInput(onDismissQueue) {
                            var draggedDown = 0f
                            detectVerticalDragGestures(
                                onDragStart = { draggedDown = 0f },
                                onVerticalDrag = { _, delta -> draggedDown += delta },
                                onDragEnd = {
                                    if (draggedDown > 90f) {
                                        haptics.tick()
                                        onDismissQueue()
                                    }
                                }
                            )
                        }
                    } else Modifier
                )
        ) {
            if (onDismissQueue != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.28f))
                        .clickable {
                            haptics.tick()
                            onDismissQueue()
                        }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "A continuación",
                        style = MaterialTheme.typography.headlineMedium,
                        color = SonoraTextPrimary
                    )
                    val radioSeed = playbackState.radioSeedTitle?.takeIf { it.isNotBlank() }
                    if (radioSeed != null) {
                        Text(
                            text = "Reproduciendo desde la radio de $radioSeed",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${playbackState.queue.size} canciones",
                            style = MaterialTheme.typography.labelSmall,
                            color = SonoraTextSecondary
                        )
                        if (playbackState.autoMixStatus != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Radio coherente",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (playbackState.isRetuningQueue) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accent
                        )
                    }
                    if (playbackState.queue.size > 1) {
                        TextButton(
                            onClick = {
                                haptics.tick()
                                onClearQueue()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("queue_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Limpiar cola",
                                tint = SonoraTextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Limpiar", style = MaterialTheme.typography.labelMedium, color = SonoraTextSecondary)
                        }
                    }
                    Surface(
                        onClick = {
                            haptics.tick()
                            onOpenSongMixer()
                        },
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier.testTag("queue_open_mixer_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "Mezclar canciones",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Mezclar",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = SonoraTextPrimary
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueMoodFilter.entries.forEach { mood ->
                val selected = playbackState.activeQueueMood == mood
                FilterChip(
                    selected = selected,
                    onClick = {
                        haptics.tick()
                        onRetuneQueueMood(mood)
                    },
                    label = { Text(text = mood.label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color.White.copy(alpha = 0.24f),
                        selectedLabelColor = Color.White,
                        containerColor = Color.White.copy(alpha = 0.10f),
                        labelColor = Color.White.copy(alpha = 0.78f)
                    ),
                    border = null,
                    modifier = Modifier.testTag("queue_mood_${mood.name.lowercase()}")
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(
                items = playbackState.queue,
                key = { idx, item -> "${idx}_${item.videoId}" }
            ) { idx, item ->
                val isCurrent = idx == playbackState.currentIndex
                var swipeOffsetX by remember(idx, item.videoId) { mutableFloatStateOf(0f) }
                var dragOffsetY by remember(idx, item.videoId) { mutableFloatStateOf(0f) }
                var liveIndex by remember(idx, item.videoId) { mutableIntStateOf(idx) }
                val dismissBackground by animateColorAsState(
                    targetValue = if (swipeOffsetX < -swipeRemoveThresholdPx * 0.55f) Color(0xFFE11D48).copy(alpha = 0.30f) else Color.Transparent,
                    label = "queue_swipe_bg"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragOffsetY != 0f) 2f else 0f)
                        .offset { IntOffset(swipeOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
                        .background(dismissBackground, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (!isCurrent) {
                                    Modifier.pointerInput(idx, playbackState.queue.size) {
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { _, dragAmount ->
                                                swipeOffsetX = (swipeOffsetX + dragAmount).coerceIn(-swipeRemoveThresholdPx * 1.4f, 0f)
                                            },
                                            onDragEnd = {
                                                if (swipeOffsetX <= -swipeRemoveThresholdPx) {
                                                    haptics.confirm()
                                                    onRemoveItem(idx)
                                                }
                                                swipeOffsetX = 0f
                                            },
                                            onDragCancel = { swipeOffsetX = 0f }
                                        )
                                    }
                                } else Modifier
                            )
                            .clickable {
                                haptics.tick()
                                onSkipToIndex(idx)
                            }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isCurrent) {
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = "Reordenar ${item.title}",
                                tint = SonoraTextTertiary,
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(end = 4.dp)
                                    .pointerInput(idx, playbackState.queue.size) {
                                        detectDragGestures(
                                            onDragStart = {
                                                liveIndex = idx
                                                dragOffsetY = 0f
                                                haptics.tick()
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                                val steps = (dragOffsetY / rowHeightPx).toInt()
                                                if (steps != 0) {
                                                    val target = (liveIndex + steps).coerceIn(0, playbackState.queue.lastIndex)
                                                    if (target != liveIndex && target != playbackState.currentIndex) {
                                                        onMoveItem(liveIndex, target)
                                                        liveIndex = target
                                                        dragOffsetY -= steps * rowHeightPx
                                                        haptics.tick()
                                                    }
                                                }
                                            },
                                            onDragEnd = { dragOffsetY = 0f },
                                            onDragCancel = { dragOffsetY = 0f }
                                        )
                                    }
                            )
                        }

                        SonoraArtwork(
                            url = item.artworkUrl,
                            contentDescription = item.title,
                            modifier = Modifier.size(44.dp),
                            cornerRadius = 9.dp
                        )
                        Spacer(Modifier.width(10.dp))
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

                        if (!isCurrent) {
                            if (swipeOffsetX < -24f) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6B81),
                                    modifier = Modifier.size(20.dp).padding(end = 6.dp)
                                )
                            }
                            if (idx > 0) {
                                IconButton(
                                    onClick = { onMoveItem(idx, idx - 1) },
                                    modifier = Modifier.size(30.dp).testTag("queue_move_up_$idx")
                                ) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Subir en la cola", tint = SonoraTextSecondary, modifier = Modifier.size(19.dp))
                                }
                            }
                            if (idx < playbackState.queue.lastIndex) {
                                IconButton(
                                    onClick = { onMoveItem(idx, idx + 1) },
                                    modifier = Modifier.size(30.dp).testTag("queue_move_down_$idx")
                                ) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Bajar en la cola", tint = SonoraTextSecondary, modifier = Modifier.size(19.dp))
                                }
                            }
                            IconButton(
                                onClick = {
                                    haptics.tick()
                                    onRemoveItem(idx)
                                },
                                modifier = Modifier.size(30.dp).testTag("queue_remove_$idx")
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Quitar de la cola", tint = SonoraTextTertiary, modifier = Modifier.size(17.dp))
                            }
                            IconButton(
                                onClick = { onOpenItemOptions(item, idx) },
                                modifier = Modifier.size(30.dp).testTag("queue_more_$idx")
                            ) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Más opciones", tint = SonoraTextSecondary, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AnimatedEqualizer(
                                    isPlaying = playbackState.isPlaying,
                                    color = accent,
                                    modifier = Modifier.height(14.dp)
                                )
                                if (item.durationText.isNotBlank()) {
                                    Text(item.durationText, style = MaterialTheme.typography.labelSmall, color = accent)
                                }
                                IconButton(
                                    onClick = { onOpenItemOptions(item, idx) },
                                    modifier = Modifier.size(30.dp).testTag("queue_more_$idx")
                                ) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "Más opciones", tint = SonoraTextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    if (idx < playbackState.queue.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp, end = 8.dp),
                            thickness = 0.5.dp,
                            color = Color.White.copy(alpha = 0.08f)
                        )
                    }
                }
            }
        }
    }
}
