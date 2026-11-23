package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.CustomMixFragment
import com.example.model.Track
import com.example.model.TransitionCurveType
import com.example.model.resolveTrackDurationMs
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.theme.TabularTimerTextStyle
import com.example.ui.viewmodel.MixerSearchState
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class MixerSlot {
    TRACK_A,
    TRACK_B
}

private enum class QuickPickerSource(val label: String) {
    QUEUE("Cola"),
    LIKED("Me gusta"),
    RECENT("Recientes"),
    LOCAL("Local / Offline")
}

private fun formatMixerTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalSongMixerSheet(
    initialTrackA: Track? = null,
    initialTrackB: Track? = null,
    queueTracks: List<Track> = emptyList(),
    likedTracks: List<Track> = emptyList(),
    recentTracks: List<Track> = emptyList(),
    localAndOfflineTracks: List<Track> = emptyList(),
    currentPlaybackDurationMs: Long = 0L,
    currentPlayingVideoId: String? = null,
    mixerSearchState: MixerSearchState,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: (String?) -> Unit,
    onClearSearch: () -> Unit,
    onDismiss: () -> Unit,
    onPreviewTransition: (
        outgoingTrack: Track,
        fromExitMs: Long,
        incomingTrack: Track,
        toEntryMs: Long,
        overlapMs: Int,
        curveType: TransitionCurveType
    ) -> Unit,
    onPlayCustomMix: (List<CustomMixFragment>) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val incomingAccent = Color(0xFF4EF2B2)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var trackA by remember(initialTrackA?.videoId) { mutableStateOf(initialTrackA) }
    var trackB by remember(initialTrackB?.videoId) { mutableStateOf(initialTrackB) }
    var pickingSlot by remember(initialTrackA?.videoId, initialTrackB?.videoId) {
        mutableStateOf(
            if (initialTrackA != null && initialTrackB == null) MixerSlot.TRACK_B else null
        )
    }

    DisposableEffect(Unit) {
        onDispose { onClearSearch() }
    }

    val durA = remember(trackA, currentPlayingVideoId, currentPlaybackDurationMs) {
        val t = trackA ?: return@remember 210_000L
        val liveDur = if (t.videoId == currentPlayingVideoId) currentPlaybackDurationMs else 0L
        resolveTrackDurationMs(t, liveDur)
    }
    val durB = remember(trackB, currentPlayingVideoId, currentPlaybackDurationMs) {
        val t = trackB ?: return@remember 210_000L
        val liveDur = if (t.videoId == currentPlayingVideoId) currentPlaybackDurationMs else 0L
        resolveTrackDurationMs(t, liveDur)
    }

    var fromStartMs by remember(trackA?.videoId) {
        mutableLongStateOf(0L)
    }
    var fromExitMs by remember(trackA?.videoId) {
        mutableLongStateOf((durA * 0.68).roundToLong().coerceIn(14_000L, (durA - 4_000L).coerceAtLeast(14_000L)))
    }
    var toEntryMs by remember(trackB?.videoId) {
        mutableLongStateOf(16_000L.coerceAtMost((durB / 3L).coerceAtLeast(0L)))
    }
    var overlapSec by remember { mutableIntStateOf(6) }
    var curveType by remember { mutableStateOf(TransitionCurveType.SMOOTH_FADE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF13141E),
        contentColor = SonoraTextPrimary
    ) {
        AnimatedContent(
            targetState = pickingSlot,
            label = "universal_mixer_content"
        ) { activePickerSlot ->
            if (activePickerSlot != null) {
                MixerTrackSearchPickerView(
                    slot = activePickerSlot,
                    otherSelectedTrack = if (activePickerSlot == MixerSlot.TRACK_A) trackB else trackA,
                    searchState = mixerSearchState,
                    queueTracks = queueTracks,
                    likedTracks = likedTracks,
                    recentTracks = recentTracks,
                    localAndOfflineTracks = localAndOfflineTracks,
                    onQueryChange = onSearchQueryChange,
                    onSubmitSearch = onSubmitSearch,
                    onBack = {
                        onClearSearch()
                        pickingSlot = null
                    },
                    onSelectTrack = { chosen ->
                        if (activePickerSlot == MixerSlot.TRACK_A) {
                            trackA = chosen
                            val chosenDur = resolveTrackDurationMs(
                                chosen,
                                if (chosen.videoId == currentPlayingVideoId) currentPlaybackDurationMs else 0L
                            )
                            fromStartMs = 0L
                            fromExitMs = (chosenDur * 0.68).roundToLong().coerceIn(14_000L, (chosenDur - 4_000L).coerceAtLeast(14_000L))
                            pickingSlot = if (trackB == null) MixerSlot.TRACK_B else null
                        } else {
                            trackB = chosen
                            val chosenDur = resolveTrackDurationMs(
                                chosen,
                                if (chosen.videoId == currentPlayingVideoId) currentPlaybackDurationMs else 0L
                            )
                            toEntryMs = 16_000L.coerceAtMost((chosenDur / 3L).coerceAtLeast(0L))
                            pickingSlot = null
                        }
                        onClearSearch()
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp)
                        .testTag("universal_mixer_editor"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(accent.copy(alpha = 0.22f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                            }
                            Column {
                                Text(
                                    text = "Mezclador de Canciones",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = SonoraTextPrimary
                                )
                                Text(
                                    text = "Busca 2 temas cualquiera y elige desde qué parte mezclar",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonoraTextSecondary
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = SonoraTextSecondary)
                        }
                    }

                    // Slot A: Outgoing Song Card
                    val selectedA = trackA
                    if (selectedA == null) {
                        EmptyMixerTrackSlotCard(
                            slotLabel = "PISTA SALIENTE (A)",
                            prompt = "Busca o elige la primera canción",
                            accentColor = accent,
                            tag = "mixer_pick_track_a",
                            onClick = { pickingSlot = MixerSlot.TRACK_A }
                        )
                    } else {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SonoraArtwork(
                                        url = selectedA.artworkUrl,
                                        contentDescription = selectedA.title,
                                        modifier = Modifier.size(46.dp),
                                        cornerRadius = 10.dp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "PISTA SALIENTE (A)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = accent
                                        )
                                        Text(
                                            text = selectedA.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = SonoraTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = selectedA.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SonoraTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextButton(
                                        onClick = { pickingSlot = MixerSlot.TRACK_A },
                                        modifier = Modifier.testTag("mixer_change_track_a")
                                    ) {
                                        Text("Cambiar", color = accent)
                                    }
                                }

                                // Start offset for Song A
                                val maxStartA = (fromExitMs - 10_000L).coerceAtLeast(0L)
                                if (fromStartMs > maxStartA) {
                                    fromStartMs = maxStartA
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Inicia fragmento A",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SonoraTextSecondary
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        TimeNudgeChip("-5s") {
                                            fromStartMs = (fromStartMs - 5_000L).coerceAtLeast(0L)
                                        }
                                        Text(
                                            text = formatMixerTime(fromStartMs),
                                            style = TabularTimerTextStyle,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        TimeNudgeChip("+5s") {
                                            fromStartMs = (fromStartMs + 5_000L).coerceAtMost(maxStartA)
                                        }
                                    }
                                }
                                Slider(
                                    value = if (durA > 0L) (fromStartMs.toFloat() / durA.toFloat()).coerceIn(0f, 0.85f) else 0f,
                                    onValueChange = { frac ->
                                        val candidate = (frac * durA).roundToLong().coerceIn(0L, maxStartA)
                                        fromStartMs = candidate
                                    },
                                    valueRange = 0f..0.85f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = accent.copy(alpha = 0.9f),
                                        activeTrackColor = accent.copy(alpha = 0.72f),
                                        inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                                    )
                                )

                                // Exit point for Song A
                                val minExitA = (fromStartMs + 10_000L).coerceAtMost((durA - 4_000L).coerceAtLeast(10_000L))
                                val maxExitA = (durA - 2_000L).coerceAtLeast(minExitA)
                                if (fromExitMs < minExitA) fromExitMs = minExitA

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Sale / mezcla hacia B",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SonoraTextPrimary
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        TimeNudgeChip("-5s") {
                                            fromExitMs = (fromExitMs - 5_000L).coerceAtLeast(minExitA)
                                        }
                                        Text(
                                            text = formatMixerTime(fromExitMs),
                                            style = TabularTimerTextStyle,
                                            color = accent,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        TimeNudgeChip("+5s") {
                                            fromExitMs = (fromExitMs + 5_000L).coerceAtMost(maxExitA)
                                        }
                                    }
                                }
                                Slider(
                                    value = (fromExitMs.toFloat() / durA.toFloat()).coerceIn(0.08f, 0.98f),
                                    onValueChange = { frac ->
                                        fromExitMs = (frac * durA).roundToLong().coerceIn(minExitA, maxExitA)
                                    },
                                    valueRange = 0.08f..0.98f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = accent,
                                        activeTrackColor = accent,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                                    )
                                )
                                Text(
                                    text = "Suena de ${formatMixerTime(fromStartMs)} a ${formatMixerTime(fromExitMs)} • Cruza desde ${formatMixerTime((fromExitMs - overlapSec * 1000L).coerceAtLeast(fromStartMs))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SonoraTextSecondary
                                )
                            }
                        }
                    }

                    // Swap A <-> B centered pill
                    if (trackA != null || trackB != null) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            OutlinedButton(
                                onClick = {
                                    val oldA = trackA
                                    val oldB = trackB
                                    trackA = oldB
                                    trackB = oldA
                                    val oldEntry = toEntryMs
                                    val newDurA = oldB?.let { resolveTrackDurationMs(it) } ?: 210_000L
                                    fromStartMs = oldEntry.coerceIn(0L, (newDurA - 20_000L).coerceAtLeast(0L))
                                    fromExitMs = (newDurA * 0.70).roundToLong().coerceIn(fromStartMs + 10_000L, (newDurA - 2_000L).coerceAtLeast(14_000L))
                                    toEntryMs = 15_000L.coerceAtMost(((oldA?.let { resolveTrackDurationMs(it) } ?: 210_000L) / 3L))
                                },
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("mixer_swap_tracks_button")
                            ) {
                                Icon(Icons.Rounded.SwapVert, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Intercambiar A ↔ B", style = MaterialTheme.typography.labelMedium, color = SonoraTextPrimary)
                            }
                        }
                    }

                    // Slot B: Incoming Song Card
                    val selectedB = trackB
                    if (selectedB == null) {
                        EmptyMixerTrackSlotCard(
                            slotLabel = "PISTA ENTRANTE (B)",
                            prompt = "Busca o elige la segunda canción",
                            accentColor = incomingAccent,
                            tag = "mixer_pick_track_b",
                            onClick = { pickingSlot = MixerSlot.TRACK_B }
                        )
                    } else {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SonoraArtwork(
                                        url = selectedB.artworkUrl,
                                        contentDescription = selectedB.title,
                                        modifier = Modifier.size(46.dp),
                                        cornerRadius = 10.dp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "PISTA ENTRANTE (B)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = incomingAccent
                                        )
                                        Text(
                                            text = selectedB.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = SonoraTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = selectedB.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SonoraTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextButton(
                                        onClick = { pickingSlot = MixerSlot.TRACK_B },
                                        modifier = Modifier.testTag("mixer_change_track_b")
                                    ) {
                                        Text("Cambiar", color = incomingAccent)
                                    }
                                }

                                val maxEntryB = (durB - 10_000L).coerceAtLeast(0L)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Entra B desde",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SonoraTextPrimary
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        TimeNudgeChip("-5s") {
                                            toEntryMs = (toEntryMs - 5_000L).coerceAtLeast(0L)
                                        }
                                        Text(
                                            text = formatMixerTime(toEntryMs),
                                            style = TabularTimerTextStyle,
                                            color = incomingAccent,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        TimeNudgeChip("+5s") {
                                            toEntryMs = (toEntryMs + 5_000L).coerceAtMost(maxEntryB)
                                        }
                                    }
                                }
                                Slider(
                                    value = (toEntryMs.toFloat() / durB.toFloat()).coerceIn(0f, 0.88f),
                                    onValueChange = { frac ->
                                        toEntryMs = (frac * durB).roundToLong().coerceIn(0L, maxEntryB)
                                    },
                                    valueRange = 0f..0.88f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = incomingAccent,
                                        activeTrackColor = incomingAccent,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                                    )
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = toEntryMs < 2_000L,
                                        onClick = { toEntryMs = 0L },
                                        label = { Text("Inicio (0:00)") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = incomingAccent.copy(alpha = 0.24f),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                    val chorusPos = (durB * 0.32).roundToLong().coerceAtMost(maxEntryB)
                                    FilterChip(
                                        selected = kotlin.math.abs(toEntryMs - chorusPos) < 4_000L,
                                        onClick = { toEntryMs = chorusPos },
                                        label = { Text("Coro (${formatMixerTime(chorusPos)})") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = incomingAccent.copy(alpha = 0.24f),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                    val midPos = (durB * 0.50).roundToLong().coerceAtMost(maxEntryB)
                                    FilterChip(
                                        selected = kotlin.math.abs(toEntryMs - midPos) < 4_000L,
                                        onClick = { toEntryMs = midPos },
                                        label = { Text("Mitad (${formatMixerTime(midPos)})") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = incomingAccent.copy(alpha = 0.24f),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Overlap + Transition Curve controls
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Duración del cruce",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SonoraTextPrimary
                                )
                                Text(
                                    text = "${overlapSec} s",
                                    style = TabularTimerTextStyle,
                                    color = accent
                                )
                            }
                            Slider(
                                value = overlapSec.toFloat(),
                                onValueChange = { overlapSec = it.roundToInt().coerceIn(2, 16) },
                                valueRange = 2f..16f,
                                steps = 13,
                                colors = SliderDefaults.colors(
                                    thumbColor = accent,
                                    activeTrackColor = accent,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                                )
                            )
                            Text(
                                text = "Estilo de mezcla",
                                style = MaterialTheme.typography.labelLarge,
                                color = SonoraTextSecondary
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TransitionCurveType.entries.forEach { curve ->
                                    FilterChip(
                                        selected = curveType == curve,
                                        onClick = { curveType = curve },
                                        label = { Text(curve.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = accent.copy(alpha = 0.28f),
                                            selectedLabelColor = Color.White,
                                            containerColor = Color.White.copy(alpha = 0.06f),
                                            labelColor = SonoraTextSecondary
                                        )
                                    )
                                }
                            }

                            if (selectedA != null && selectedB != null) {
                                Spacer(Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.06f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.SwapHoriz,
                                            contentDescription = null,
                                            tint = incomingAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "A (${formatMixerTime(fromStartMs)}→${formatMixerTime(fromExitMs)}) ✕ ${overlapSec}s ▸ B (desde ${formatMixerTime(toEntryMs)})",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = SonoraTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val sameTrackWarning = selectedA != null && selectedB != null && selectedA.videoId == selectedB.videoId
                    if (sameTrackWarning) {
                        Text(
                            text = "Elige dos canciones diferentes para evitar cortes repetidos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = accent
                        )
                    }

                    val canMix = selectedA != null && selectedB != null && selectedA.videoId != selectedB.videoId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            enabled = canMix,
                            onClick = {
                                val outT = selectedA ?: return@OutlinedButton
                                val inT = selectedB ?: return@OutlinedButton
                                onPreviewTransition(
                                    outT,
                                    fromExitMs,
                                    inT,
                                    toEntryMs,
                                    overlapSec * 1000,
                                    curveType
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, if (canMix) accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("preview_fragment_mix_button")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = if (canMix) accent else SonoraTextTertiary)
                            Spacer(Modifier.width(4.dp))
                            Text("Probar cruce", color = if (canMix) SonoraTextPrimary else SonoraTextTertiary)
                        }

                        Button(
                            enabled = canMix,
                            onClick = {
                                val outT = selectedA ?: return@Button
                                val inT = selectedB ?: return@Button
                                onPlayCustomMix(
                                    listOf(
                                        CustomMixFragment(
                                            track = outT,
                                            startMs = fromStartMs.coerceAtLeast(0L),
                                            endMs = fromExitMs.coerceAtLeast(fromStartMs + 8_000L),
                                            crossfadeMs = overlapSec * 1000,
                                            curveType = curveType
                                        ),
                                        CustomMixFragment(
                                            track = inT,
                                            startMs = toEntryMs.coerceAtLeast(0L),
                                            endMs = durB,
                                            crossfadeMs = overlapSec * 1000,
                                            curveType = curveType
                                        )
                                    )
                                )
                                onDismiss()
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                disabledContainerColor = Color.White.copy(alpha = 0.12f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_fragment_mix_button")
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Lanzar Mix")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMixerTrackSlotCard(
    slotLabel: String,
    prompt: String,
    accentColor: Color,
    tag: String,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        shape = RoundedCornerShape(18.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = accentColor)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slotLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor
                )
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.titleMedium,
                    color = SonoraTextPrimary
                )
            }
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "Buscar canción",
                tint = SonoraTextSecondary
            )
        }
    }
}

@Composable
private fun TimeNudgeChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.09f),
        border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = SonoraTextPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MixerTrackSearchPickerView(
    slot: MixerSlot,
    otherSelectedTrack: Track?,
    searchState: MixerSearchState,
    queueTracks: List<Track>,
    likedTracks: List<Track>,
    recentTracks: List<Track>,
    localAndOfflineTracks: List<Track>,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String?) -> Unit,
    onBack: () -> Unit,
    onSelectTrack: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val focusManager = LocalFocusManager.current
    var quickSource by remember {
        mutableStateOf(
            when {
                queueTracks.isNotEmpty() -> QuickPickerSource.QUEUE
                likedTracks.isNotEmpty() -> QuickPickerSource.LIKED
                recentTracks.isNotEmpty() -> QuickPickerSource.RECENT
                else -> QuickPickerSource.LOCAL
            }
        )
    }

    val q = searchState.query.trim()
    val allLocalCandidates = remember(queueTracks, likedTracks, recentTracks, localAndOfflineTracks) {
        (queueTracks + likedTracks + recentTracks + localAndOfflineTracks).distinctBy { it.videoId }
    }
    val localMatches = remember(q, allLocalCandidates) {
        if (q.isBlank()) emptyList()
        else allLocalCandidates.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }.take(10)
    }
    val quickList = remember(quickSource, queueTracks, likedTracks, recentTracks, localAndOfflineTracks) {
        when (quickSource) {
            QuickPickerSource.QUEUE -> queueTracks
            QuickPickerSource.LIKED -> likedTracks
            QuickPickerSource.RECENT -> recentTracks
            QuickPickerSource.LOCAL -> localAndOfflineTracks
        }.distinctBy { it.videoId }
    }

    val displayedTracks = remember(q, searchState.results, localMatches, quickList) {
        if (q.isNotBlank()) {
            (searchState.results + localMatches).distinctBy { it.videoId }
        } else {
            quickList
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp, max = 640.dp)
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("mixer_picker_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Volver al mezclador",
                    tint = SonoraTextPrimary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (slot == MixerSlot.TRACK_A) "Elegir Pista Saliente (A)" else "Elegir Pista Entrante (B)",
                    style = MaterialTheme.typography.titleLarge,
                    color = SonoraTextPrimary
                )
                Text(
                    text = "Busca cualquier canción o selecciona de tus listas",
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary
                )
            }
        }

        if (otherSelectedTrack != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF17171C),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (slot == MixerSlot.TRACK_B) {
                            "Mezclando después de: ${otherSelectedTrack.title}"
                        } else {
                            "Entrará antes de: ${otherSelectedTrack.title}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = SonoraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        OutlinedTextField(
            value = searchState.query,
            onValueChange = onQueryChange,
            placeholder = { Text("Buscar canción o artista...", color = SonoraTextTertiary) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = accent) },
            trailingIcon = {
                if (searchState.query.isNotBlank()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Limpiar búsqueda", tint = SonoraTextSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    onSubmitSearch(null)
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1A1A25),
                unfocusedContainerColor = Color(0xFF161620),
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                focusedTextColor = SonoraTextPrimary,
                unfocusedTextColor = SonoraTextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mixer_search_input")
        )

        if (q.isBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickPickerSource.entries.forEach { source ->
                    FilterChip(
                        selected = quickSource == source,
                        onClick = { quickSource = source },
                        label = { Text(source.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = Color.White,
                            containerColor = Color.White.copy(alpha = 0.06f),
                            labelColor = SonoraTextSecondary
                        )
                    )
                }
            }
        }

        if (searchState.isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
            }
        }

        val errMsg = searchState.error
        if (errMsg != null && displayedTracks.isEmpty() && !searchState.isSearching) {
            Text(
                text = errMsg,
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextSecondary,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else if (displayedTracks.isEmpty() && !searchState.isSearching) {
            Text(
                text = if (q.isBlank()) "Escribe el nombre de cualquier canción arriba para buscarla y seleccionarla." else "Sin resultados todavía. Toca buscar en el teclado.",
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextSecondary,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(displayedTracks, key = { "mixpick_${it.videoId}" }) { item ->
                    val isAlreadyOther = otherSelectedTrack?.videoId == item.videoId
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isAlreadyOther) accent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelectTrack(item) }
                            .testTag("mixer_pick_item_${item.videoId}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SonoraArtwork(
                                url = item.artworkUrl,
                                contentDescription = item.title,
                                modifier = Modifier.size(44.dp),
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SonoraTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.durationText.isNotBlank()) {
                                Text(
                                    text = item.durationText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SonoraTextTertiary,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Seleccionar para mezcla",
                                tint = accent
                            )
                        }
                    }
                }
            }
        }
    }
}
