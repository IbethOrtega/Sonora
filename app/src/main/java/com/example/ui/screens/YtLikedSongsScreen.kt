package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.ui.components.DominantCoverTopGradient
import com.example.ui.components.GlassCard
import com.example.ui.components.TrackRowItem
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.viewmodel.SonoraViewModel

private enum class LikedSortMode(val label: String) {
    DEFAULT("Recientes"),
    TITLE("Título A–Z"),
    ARTIST("Artista A–Z")
}

@Composable
fun YtLikedSongsScreen(
    viewModel: SonoraViewModel,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val songs by viewModel.allLikedSongs.collectAsState()
    val isSyncing by viewModel.isSyncingYt.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(LikedSortMode.DEFAULT) }
    var visibleCount by remember { mutableIntStateOf(80) }
    val listState = rememberLazyListState()

    val filteredSongs = remember(songs, searchQuery, sortMode) {
        val q = searchQuery.trim()
        val matched = if (q.isBlank()) {
            songs
        } else {
            songs.filter { t ->
                t.title.contains(q, ignoreCase = true) ||
                    t.artist.contains(q, ignoreCase = true) ||
                    t.album.orEmpty().contains(q, ignoreCase = true)
            }
        }
        when (sortMode) {
            LikedSortMode.DEFAULT -> matched
            LikedSortMode.TITLE -> matched.sortedBy { it.title.lowercase() }
            LikedSortMode.ARTIST -> matched.sortedBy { it.artist.lowercase() }
        }
    }

    val visibleSongs = remember(filteredSongs, visibleCount) {
        filteredSongs.take(visibleCount.coerceAtLeast(80))
    }
    val hasMoreToReveal = visibleSongs.size < filteredSongs.size

    val shouldLoadMore by remember(listState, hasMoreToReveal, filteredSongs.size) {
        derivedStateOf {
            if (!hasMoreToReveal) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (visibleSongs.size - 8).coerceAtLeast(0)
        }
    }
    LaunchedEffect(shouldLoadMore, visibleSongs.size) {
        if (shouldLoadMore) {
            visibleCount += 80
        }
    }

    val palette = LocalSonoraPalette.current
    val coverUrl = songs.firstOrNull()?.artworkUrl.orEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("yt_liked_songs_screen")
    ) {
        DominantCoverTopGradient(artworkUrl = coverUrl, fallbackColor = accent)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
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
                    .testTag("yt_liked_back_button")
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
                    text = stringResource(R.string.yt_liked_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isSyncing) {
                        "${filteredSongs.size} canciones • Sincronizando..."
                    } else {
                        "${filteredSongs.size} canciones"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary
                )
            }
            IconButton(
                onClick = { viewModel.syncYouTubeLibrary() },
                enabled = !isSyncing,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("yt_liked_sync_button")
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        color = accent,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = "Sincronizar Me gusta",
                        tint = SonoraTextPrimary
                    )
                }
            }
        }

        if (songs.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    visibleCount = 80
                },
                placeholder = { Text("Filtrar por canción, artista o álbum") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = SonoraTextSecondary)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Limpiar filtro", tint = SonoraTextSecondary)
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = palette.elevated,
                    unfocusedContainerColor = palette.surface,
                    focusedTextColor = SonoraTextPrimary,
                    unfocusedTextColor = SonoraTextPrimary,
                    focusedPlaceholderColor = SonoraTextSecondary,
                    unfocusedPlaceholderColor = SonoraTextSecondary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("yt_liked_search_input")
            )

            Spacer(Modifier.height(8.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(LikedSortMode.entries) { mode ->
                    val selected = mode == sortMode
                    FilterChip(
                        selected = selected,
                        onClick = { sortMode = mode },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.White.copy(alpha = 0.16f),
                            selectedLabelColor = SonoraTextPrimary,
                            containerColor = palette.surface,
                            labelColor = SonoraTextSecondary
                        ),
                        border = null,
                        modifier = Modifier.testTag("yt_liked_sort_${mode.name.lowercase()}")
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (songs.isEmpty()) {
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
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.yt_liked_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = SonoraTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else if (filteredSongs.isEmpty()) {
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
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onPlayQueue(filteredSongs, 0) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("yt_liked_play_all_button")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.play_all))
                        }
                        Button(
                            onClick = { onPlayQueue(filteredSongs.shuffled(), 0) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.elevated,
                                contentColor = SonoraTextPrimary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("yt_liked_shuffle_button")
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = SonoraTextPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.shuffle), color = SonoraTextPrimary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                itemsIndexed(
                    items = visibleSongs,
                    key = { idx, track -> "yt_liked_${track.videoId}_$idx" }
                ) { idx, track ->
                    TrackRowItem(
                        track = track,
                        indexLabel = "${idx + 1}",
                        isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                        isActuallyPlaying = playbackState.isPlaying,
                        showDivider = idx < visibleSongs.lastIndex,
                        onClick = { onPlayQueue(filteredSongs, idx) },
                        onMoreClick = { onTrackMore(track) },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }

                if (hasMoreToReveal || isSyncing) {
                    item(key = "yt_liked_loading_footer") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = accent,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (isSyncing) "Cargando más Me gusta de YouTube Music..." else "Cargando más canciones...",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
