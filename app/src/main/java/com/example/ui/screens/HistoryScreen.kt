package com.example.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.GlassCard
import com.example.ui.components.TrackRowItem
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.viewmodel.SonoraViewModel

@Composable
fun HistoryScreen(
    viewModel: SonoraViewModel,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val dayGroups by viewModel.listeningHistory.collectAsState()
    val hasMoreYtHistory by viewModel.hasMoreYouTubeHistory.collectAsState()
    val isLoadingMore by viewModel.isLoadingMoreHistory.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.ensureYouTubeHistoryLoaded()
    }

    val filteredGroups = remember(dayGroups, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) {
            dayGroups
        } else {
            dayGroups.mapNotNull { group ->
                val filteredEntries = group.entries.filter { entry ->
                    entry.track.title.contains(q, ignoreCase = true) ||
                        entry.track.artist.contains(q, ignoreCase = true) ||
                        entry.track.album.orEmpty().contains(q, ignoreCase = true)
                }
                if (filteredEntries.isEmpty()) null else group.copy(entries = filteredEntries)
            }
        }
    }

    val flattenedTracks = remember(filteredGroups) {
        filteredGroups.flatMap { group -> group.entries.map { it.track } }
    }
    val groupOffsets = remember(filteredGroups) {
        val offsets = mutableMapOf<String, Int>()
        var running = 0
        filteredGroups.forEach { group ->
            offsets[group.dayKey] = running
            running += group.entries.size
        }
        offsets
    }

    val shouldLoadMorePages by remember(listState, hasMoreYtHistory, isLoadingMore) {
        derivedStateOf {
            if (!hasMoreYtHistory || isLoadingMore) return@derivedStateOf false
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 0) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (total - 6).coerceAtLeast(0)
        }
    }
    LaunchedEffect(shouldLoadMorePages) {
        if (shouldLoadMorePages) {
            viewModel.loadMoreYouTubeHistory()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("history_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .sonoraLiquidGlassSurface(
                        shape = CircleShape,
                        blurRadius = 14.dp,
                        refractionHeight = 6.dp,
                        refractionAmount = 10.dp,
                        surfaceAlpha = 0.46f
                    )
                    .minimumInteractiveComponentSize()
                    .testTag("history_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Volver",
                    tint = SonoraTextPrimary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${flattenedTracks.size} reproducciones registradas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary
                )
            }
            if (dayGroups.isNotEmpty()) {
                IconButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("history_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.history_clear),
                        tint = SonoraTextSecondary
                    )
                }
            }
        }

        if (dayGroups.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar en tu historial") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = SonoraTextSecondary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Limpiar búsqueda", tint = SonoraTextSecondary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                        focusedTextColor = SonoraTextPrimary,
                        unfocusedTextColor = SonoraTextPrimary,
                        focusedPlaceholderColor = SonoraTextSecondary,
                        unfocusedPlaceholderColor = SonoraTextSecondary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("history_search_input")
                )

                if (flattenedTracks.isNotEmpty()) {
                    Button(
                        onClick = { onPlayQueue(flattenedTracks, 0) },
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("history_play_all_button")
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Reproducir todo")
                        Spacer(Modifier.width(4.dp))
                        Text("Todo")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (dayGroups.isEmpty() && !isLoadingMore) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.history_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = SonoraTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else if (filteredGroups.isEmpty() && !isLoadingMore) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Sin resultados para \"$searchQuery\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SonoraTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding)
            ) {
                filteredGroups.forEach { group ->
                    item(key = "header_${group.dayKey}") {
                        Text(
                            text = group.dayLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                    val baseOffset = groupOffsets[group.dayKey] ?: 0
                    itemsIndexed(
                        items = group.entries,
                        key = { idx, entry -> "hist_${group.dayKey}_${entry.id}_$idx" }
                    ) { idx, entry ->
                        val flatIndex = baseOffset + idx
                        TrackRowItem(
                            track = entry.track,
                            isPlayingCurrent = playbackState.currentTrack?.videoId == entry.track.videoId &&
                                playbackState.currentIndex == flatIndex &&
                                playbackState.queue.size == flattenedTracks.size,
                            isActuallyPlaying = playbackState.isPlaying,
                            showDivider = idx < group.entries.lastIndex,
                            onClick = { onPlayQueue(flattenedTracks, flatIndex) },
                            onMoreClick = { onTrackMore(entry.track) },
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                }

                if (isLoadingMore || hasMoreYtHistory) {
                    item(key = "history_pagination_footer") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isLoadingMore) {
                                CircularProgressIndicator(
                                    color = accent,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Cargando más historial...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary
                                )
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.loadMoreYouTubeHistory() },
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.testTag("history_load_more_button")
                                ) {
                                    Text("Cargar más historial", color = SonoraTextPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Color(0xFF151520),
            titleContentColor = SonoraTextPrimary,
            textContentColor = SonoraTextSecondary,
            title = { Text("Limpiar historial") },
            text = { Text("Se borrarán todas las sesiones registradas de escucha y las estadísticas de Replay.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearPlaybackHistory()
                        showClearConfirm = false
                    }
                ) {
                    Text("Limpiar", color = accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancelar", color = SonoraTextSecondary)
                }
            }
        )
    }
}
