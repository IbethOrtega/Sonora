package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.model.CustomMixFragment
import com.example.model.ListeningStatsSummary
import com.example.model.LyricLine
import com.example.model.PlaylistTransitionSpec
import com.example.model.Track
import com.example.model.TransitionCurveType
import com.example.model.resolveTrackDurationMs
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.TabularTimerTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private fun formatMinSecMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FragmentMixerSheet(
    tracks: List<Track>,
    playlistId: Long = 0L,
    existingSpecs: List<PlaylistTransitionSpec> = emptyList(),
    onDismiss: () -> Unit,
    onSavePlaylistSpec: (PlaylistTransitionSpec) -> Unit = {},
    onDeletePlaylistSpec: (fromVideoId: String, toVideoId: String) -> Unit = { _, _ -> },
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
    if (tracks.size < 2) return
    val accent = LocalSonoraAccent.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isPlaylistMode = playlistId != 0L
    var selectedPairIndex by remember { mutableIntStateOf(0) }
    val safePairIdx = selectedPairIndex.coerceIn(0, (tracks.size - 2).coerceAtLeast(0))
    val outTrack = tracks[safePairIdx]
    val inTrack = tracks[safePairIdx + 1]

    val existingForPair = remember(existingSpecs, outTrack.videoId, inTrack.videoId) {
        existingSpecs.firstOrNull { it.fromVideoId == outTrack.videoId && it.toVideoId == inTrack.videoId }
    }

    val outDurationMs = remember(outTrack) {
        resolveTrackDurationMs(outTrack)
    }
    val inDurationMs = remember(inTrack) {
        resolveTrackDurationMs(inTrack)
    }

    var fromStartMs by remember(outTrack.videoId, inTrack.videoId) {
        mutableLongStateOf(0L)
    }
    var fromExitMs by remember(outTrack.videoId, inTrack.videoId, existingForPair) {
        mutableLongStateOf(
            existingForPair?.fromExitMs?.takeIf { it > 0L }
                ?: (outDurationMs * 0.65).roundToLong().coerceAtLeast(18_000L)
        )
    }
    var toEntryMs by remember(outTrack.videoId, inTrack.videoId, existingForPair) {
        mutableLongStateOf(existingForPair?.toEntryMs ?: 18_000L.coerceAtMost(inDurationMs / 3))
    }
    var overlapSec by remember(outTrack.videoId, inTrack.videoId, existingForPair) {
        mutableIntStateOf(((existingForPair?.crossfadeMs ?: 5_000) / 1000).coerceIn(2, 16))
    }
    var curveType by remember(outTrack.videoId, inTrack.videoId, existingForPair) {
        mutableStateOf(existingForPair?.curveType ?: TransitionCurveType.SMOOTH_FADE)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF13141E),
        contentColor = SonoraTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
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
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                    }
                    Column {
                        Text(
                            text = "Mezclador de Fragmentos",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = if (isPlaylistMode) "Edita los cortes exactos entre pistas de esta playlist" else "Crea un mini-mix estilo estudio",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = SonoraTextSecondary)
                }
            }

            // Pair selector chips when there are 3+ tracks
            if (tracks.size > 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0 until tracks.lastIndex) {
                        val hasCustom = existingSpecs.any {
                            it.fromVideoId == tracks[i].videoId && it.toVideoId == tracks[i + 1].videoId
                        }
                        FilterChip(
                            selected = safePairIdx == i,
                            onClick = { selectedPairIndex = i },
                            label = {
                                Text(
                                    text = if (hasCustom) "Pista ${i + 1}→${i + 2} • Mix" else "Pista ${i + 1}→${i + 2}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
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

            // Track A Outgoing Fragment Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                tint = Color(0xFF1F2233)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SonoraArtwork(
                            url = outTrack.artworkUrl,
                            contentDescription = outTrack.title,
                            modifier = Modifier.size(44.dp),
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
                                text = "${outTrack.title} • ${outTrack.artist}",
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Sale ${formatMinSecMs(fromExitMs)}",
                            style = TabularTimerTextStyle,
                            color = Color.White
                        )
                    }
                    if (!isPlaylistMode) {
                        val maxStartA = (fromExitMs - 10_000L).coerceAtLeast(0L)
                        if (fromStartMs > maxStartA) fromStartMs = maxStartA
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
                            Text(
                                text = formatMinSecMs(fromStartMs),
                                style = TabularTimerTextStyle,
                                color = Color.White
                            )
                        }
                        Slider(
                            value = (fromStartMs.toFloat() / outDurationMs.toFloat()).coerceIn(0f, 0.85f),
                            onValueChange = { frac ->
                                fromStartMs = (frac * outDurationMs).roundToLong().coerceIn(0L, maxStartA)
                            },
                            valueRange = 0f..0.85f,
                            colors = SliderDefaults.colors(
                                thumbColor = accent.copy(alpha = 0.85f),
                                activeTrackColor = accent.copy(alpha = 0.65f),
                                inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                            )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = (fromExitMs.toFloat() / outDurationMs.toFloat()).coerceIn(0.08f, 0.98f),
                        onValueChange = { frac ->
                            val minExit = if (isPlaylistMode) 8_000L else (fromStartMs + 8_000L)
                            fromExitMs = (frac * outDurationMs).roundToLong().coerceIn(minExit, (outDurationMs - 2_000L).coerceAtLeast(minExit))
                        },
                        valueRange = 0.08f..0.98f,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                        )
                    )
                    Text(
                        text = "Inicio mezcla a ${formatMinSecMs((fromExitMs - overlapSec * 1000L).coerceAtLeast(fromStartMs))} • Fin fragmento ${formatMinSecMs(fromExitMs)} / ${formatMinSecMs(outDurationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SonoraTextSecondary
                    )
                }
            }

            // Track B Incoming Fragment Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                tint = Color(0xFF1B2732)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SonoraArtwork(
                            url = inTrack.artworkUrl,
                            contentDescription = inTrack.title,
                            modifier = Modifier.size(44.dp),
                            cornerRadius = 10.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PISTA ENTRANTE (B)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4EF2B2)
                            )
                            Text(
                                text = "${inTrack.title} • ${inTrack.artist}",
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Entra ${formatMinSecMs(toEntryMs)}",
                            style = TabularTimerTextStyle,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = (toEntryMs.toFloat() / inDurationMs.toFloat()).coerceIn(0f, 0.88f),
                        onValueChange = { frac ->
                            toEntryMs = (frac * inDurationMs).roundToLong().coerceIn(0L, (inDurationMs - 10_000L).coerceAtLeast(0L))
                        },
                        valueRange = 0f..0.88f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4EF2B2),
                            activeTrackColor = Color(0xFF4EF2B2),
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                        )
                    )
                    Text(
                        text = "Momento exacto donde entra B (ej. intro, coro o drop): ${formatMinSecMs(toEntryMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SonoraTextSecondary
                    )
                }
            }

            // Overlap & Curve controls
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                tint = Color(0xFF20202E)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Duración del solape (fundido)",
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Estilo de transición",
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
                }
            }

            // Preview + Primary Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onPreviewTransition(
                            outTrack,
                            fromExitMs,
                            inTrack,
                            toEntryMs,
                            overlapSec * 1000,
                            curveType
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("preview_fragment_mix_button")
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = accent)
                    Spacer(Modifier.width(4.dp))
                    Text("Probar cruce", color = SonoraTextPrimary)
                }

                Button(
                    onClick = {
                        if (isPlaylistMode) {
                            onSavePlaylistSpec(
                                PlaylistTransitionSpec(
                                    playlistId = playlistId,
                                    fromVideoId = outTrack.videoId,
                                    toVideoId = inTrack.videoId,
                                    fromExitMs = fromExitMs,
                                    toEntryMs = toEntryMs,
                                    crossfadeMs = overlapSec * 1000,
                                    curveType = curveType
                                )
                            )
                        } else {
                            onPlayCustomMix(
                                listOf(
                                    CustomMixFragment(
                                        track = outTrack,
                                        startMs = fromStartMs.coerceAtLeast(0L),
                                        endMs = fromExitMs.coerceAtLeast(fromStartMs + 8_000L),
                                        crossfadeMs = overlapSec * 1000,
                                        curveType = curveType
                                    ),
                                    CustomMixFragment(
                                        track = inTrack,
                                        startMs = toEntryMs,
                                        endMs = inDurationMs,
                                        crossfadeMs = overlapSec * 1000,
                                        curveType = curveType
                                    )
                                )
                            )
                            onDismiss()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("save_fragment_mix_button")
                ) {
                    Icon(
                        imageVector = if (isPlaylistMode) Icons.Rounded.Check else Icons.Rounded.AutoAwesome,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isPlaylistMode) "Guardar" else "Lanzar Mix")
                }
            }

            if (isPlaylistMode && existingForPair != null) {
                TextButton(
                    onClick = { onDeletePlaylistSpec(outTrack.videoId, inTrack.videoId) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Quitar transición personalizada de este par", color = SonoraTextSecondary)
                }
            }
        }
    }
}

private enum class LyricsCardTheme(
    val label: String,
    val startArgb: Int,
    val endArgb: Int
) {
    ACCENT_DARK("Estudio Nocturno", 0xFF1A1E2D.toInt(), 0xFF0B0C12.toInt()),
    EMERALD("Esmeralda Profundo", 0xFF0E3D2A.toInt(), 0xFF091410.toInt()),
    CRIMSON("Carmesí Vivo", 0xFF561224.toInt(), 0xFF14080D.toInt()),
    INDIGO("Obsidiana Índigo", 0xFF23184A.toInt(), 0xFF0A0A14.toInt())
}

@Composable
fun LyricsShareCardDialog(
    track: Track,
    lines: List<LyricLine>,
    initialLineIndex: Int = 0,
    ambientPrimary: Color,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = LocalSonoraAccent.current
    val nonEmptyLines = remember(lines) { lines.filter { it.text.isNotBlank() } }
    val selectedIndices = remember(nonEmptyLines, initialLineIndex) {
        mutableStateListOf<Int>().apply {
            val safe = initialLineIndex.coerceIn(0, (nonEmptyLines.size - 1).coerceAtLeast(0))
            if (nonEmptyLines.isNotEmpty()) {
                add(safe)
                if (safe + 1 < nonEmptyLines.size) add(safe + 1)
            }
        }
    }
    var chosenTheme by remember { mutableStateOf(LyricsCardTheme.ACCENT_DARK) }
    var isSharing by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(26.dp),
            tint = Color(0xFF151620),
            alphaTop = 0.94f,
            alphaBottom = 0.98f
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tarjeta de Letra",
                        style = MaterialTheme.typography.headlineMedium,
                        color = SonoraTextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = SonoraTextSecondary)
                    }
                }

                // Live visual card preview
                val previewBg = remember(chosenTheme, ambientPrimary) {
                    if (chosenTheme == LyricsCardTheme.ACCENT_DARK) {
                        Brush.verticalGradient(
                            listOf(ambientPrimary.copy(alpha = 0.55f), Color(0xFF0C0E14))
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(Color(chosenTheme.startArgb), Color(chosenTheme.endArgb))
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(previewBg)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SonoraArtwork(
                                url = track.artworkUrl,
                                contentDescription = track.title,
                                modifier = Modifier.size(46.dp),
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        val snippetText = selectedIndices
                            .sorted()
                            .mapNotNull { nonEmptyLines.getOrNull(it)?.text }
                            .joinToString("\n")
                            .ifBlank { "Elige hasta 4 versos abajo" }

                        Text(
                            text = snippetText,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White
                        )

                        Text(
                            text = "SONORA",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    }
                }

                // Theme chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LyricsCardTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = chosenTheme == theme,
                            onClick = { chosenTheme = theme },
                            label = { Text(theme.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.30f),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Text(
                    text = "Selecciona de 1 a 4 líneas (${selectedIndices.size}/4)",
                    style = MaterialTheme.typography.labelMedium,
                    color = SonoraTextSecondary
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(nonEmptyLines) { idx, line ->
                        val isPicked = idx in selectedIndices
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isPicked) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(
                                0.8.dp,
                                if (isPicked) accent.copy(alpha = 0.7f) else Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isPicked) {
                                        if (selectedIndices.size > 1) selectedIndices.remove(idx)
                                    } else if (selectedIndices.size < 4) {
                                        selectedIndices.add(idx)
                                    }
                                }
                        ) {
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isPicked) Color.White else SonoraTextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        if (isSharing) return@Button
                        isSharing = true
                        scope.launch {
                            val linesText = selectedIndices
                                .sorted()
                                .mapNotNull { nonEmptyLines.getOrNull(it)?.text }
                            val startColor = if (chosenTheme == LyricsCardTheme.ACCENT_DARK) {
                                ambientPrimary.toArgb()
                            } else {
                                chosenTheme.startArgb
                            }
                            shareLyricsCardBitmap(
                                context = context,
                                track = track,
                                quoteLines = linesText,
                                startColor = startColor,
                                endColor = chosenTheme.endArgb
                            )
                            isSharing = false
                            onDismiss()
                        }
                    },
                    enabled = !isSharing && selectedIndices.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("share_lyrics_card_button")
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSharing) "Generando tarjeta..." else "Compartir tarjeta de letra")
                }
            }
        }
    }
}

@Composable
fun SonoraReplaySheet(
    stats: ListeningStatsSummary,
    onDismiss: () -> Unit
) {
    val accent = LocalSonoraAccent.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSharing by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            tint = Color(0xFF141826),
            alphaTop = 0.96f,
            alphaBottom = 0.98f
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SONORA REPLAY",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent
                        )
                        Text(
                            text = "Tu resumen sonoro",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = SonoraTextSecondary)
                    }
                }

                // Visual summary poster card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.48f), Color(0xFF121420), Color(0xFF0A0B10))
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(22.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "${stats.estimatedMinutes} min",
                                    style = MaterialTheme.typography.displayLarge,
                                    color = Color.White
                                )
                                Text(
                                    text = "escuchados este período",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.72f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${stats.totalPlays}",
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = accent
                                )
                                Text(
                                    text = "reproducciones",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.72f)
                                )
                            }
                        }

                        val topArtist = stats.topArtists.firstOrNull()
                        if (topArtist != null) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White.copy(alpha = 0.08f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "ARTISTA #1",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = accent
                                        )
                                        Text(
                                            text = topArtist.artist,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "${topArtist.totalPlays} plays",
                                        style = TabularTimerTextStyle,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        if (stats.topTracks.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "TUS CANCIONES TOP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                                stats.topTracks.take(5).forEachIndexed { idx, entry ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            style = TabularTimerTextStyle,
                                            color = accent
                                        )
                                        SonoraArtwork(
                                            url = entry.track.artworkUrl,
                                            contentDescription = entry.track.title,
                                            modifier = Modifier.size(34.dp),
                                            cornerRadius = 8.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.track.title,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = entry.track.artist,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White.copy(alpha = 0.65f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = "${entry.playCount}x",
                                            style = TabularTimerTextStyle,
                                            color = Color.White.copy(alpha = 0.75f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        if (isSharing) return@Button
                        isSharing = true
                        scope.launch {
                            shareReplaySummaryBitmap(context, stats, accent.toArgb())
                            isSharing = false
                        }
                    },
                    enabled = !isSharing && stats.totalPlays > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("share_replay_button")
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSharing) "Creando imagen..." else "Compartir Sonora Replay")
                }
            }
        }
    }
}

private suspend fun shareLyricsCardBitmap(
    context: Context,
    track: Track,
    quoteLines: List<String>,
    startColor: Int,
    endColor: Int
) = withContext(Dispatchers.IO) {
    val artBitmap = loadBitmapForShare(context, track.artworkUrl)
    val width = 1080
    val height = 1350
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(startColor, endColor),
            null,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    if (artBitmap != null) {
        val artRect = RectF(88f, 110f, 268f, 290f)
        val scaled = Bitmap.createScaledBitmap(artBitmap, 180, 180, true)
        canvas.drawBitmap(scaled, null, artRect, null)
    }

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 32f
    }
    val textStartX = if (artBitmap != null) 298f else 88f
    canvas.drawText(track.title.take(36), textStartX, 185f, titlePaint)
    canvas.drawText(track.artist.take(40), textStartX, 238f, artistPaint)

    val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 58f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val quoteText = quoteLines.joinToString("\n")
    val layout = StaticLayout.Builder.obtain(quoteText, 0, quoteText.length, quotePaint, width - 176)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(14f, 1.08f)
        .build()
    canvas.save()
    canvas.translate(88f, 390f)
    layout.draw(canvas)
    canvas.restore()

    val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = 28f
        letterSpacing = 0.14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText("SONORA", 88f, height - 96f, brandPaint)

    launchShareBitmapIntent(context, bmp, "sonora_lyrics_${System.currentTimeMillis()}.png", "${track.title} — ${track.artist}")
}

private suspend fun shareReplaySummaryBitmap(
    context: Context,
    stats: ListeningStatsSummary,
    accentArgb: Int
) = withContext(Dispatchers.IO) {
    val width = 1080
    val height = 1350
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(accentArgb, 0xFF121520.toInt(), 0xFF0A0B10.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 30f
        letterSpacing = 0.14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText("SONORA REPLAY", 88f, 140f, labelPaint)

    val heroPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 96f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText("${stats.estimatedMinutes} min", 88f, 270f, heroPaint)

    val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFFFFFF.toInt()
        textSize = 36f
    }
    canvas.drawText("${stats.totalPlays} reproducciones • ${stats.uniqueTracks} temas", 88f, 330f, subPaint)

    stats.topArtists.firstOrNull()?.let { top ->
        canvas.drawText("Artista #1: ${top.artist} (${top.totalPlays}x)", 88f, 440f, subPaint)
    }

    var y = 550f
    val rowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    stats.topTracks.take(5).forEachIndexed { idx, entry ->
        canvas.drawText("${idx + 1}. ${entry.track.title.take(28)} — ${entry.track.artist.take(20)}", 88f, y, rowPaint)
        y += 88f
    }

    launchShareBitmapIntent(context, bmp, "sonora_replay_${System.currentTimeMillis()}.png", "Mi Sonora Replay")
}

private suspend fun loadBitmapForShare(context: Context, url: String): Bitmap? {
    if (url.isBlank()) return null
    return runCatching {
        val req = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .size(300)
            .build()
        val res = ImageLoader(context).execute(req)
        (res as? SuccessResult)?.drawable?.let { it as? BitmapDrawable }?.bitmap
    }.getOrNull()
}

private suspend fun launchShareBitmapIntent(
    context: Context,
    bitmap: Bitmap,
    fileName: String,
    subjectText: String
) {
    val dir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
    val outFile = File(dir, fileName)
    FileOutputStream(outFile).use { fos ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 96, fos)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, subjectText)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    withContext(Dispatchers.Main) {
        context.startActivity(
            Intent.createChooser(shareIntent, "Compartir tarjeta Sonora").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
