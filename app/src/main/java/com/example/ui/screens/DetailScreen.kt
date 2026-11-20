package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.BrowseCard
import com.example.model.ItemKind
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.BrowseCardItem
import com.example.ui.components.ErrorStateGlass
import com.example.ui.components.FragmentMixerSheet
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.SonoraBackground
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.viewmodel.SonoraViewModel
import com.example.ui.viewmodel.UiState

@Composable
fun DetailScreen(
    browseId: String,
    initialTitle: String,
    initialSubtitle: String,
    initialArtworkUrl: String,
    params: String?,
    viewModel: SonoraViewModel,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onOpenCard: (BrowseCard) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    LaunchedEffect(browseId, params) {
        viewModel.loadDetail(browseId, params)
    }

    val detailState by viewModel.detailState.collectAsState()
    val isSaved by viewModel.observeIsCollectionSaved(browseId).collectAsState(initial = false)
    val accent = LocalSonoraAccent.current

    val loadedDetail = (detailState as? UiState.Success)?.data
    val displayTitle = loadedDetail?.title?.takeIf { it.isNotBlank() } ?: initialTitle
    val displaySubtitle = loadedDetail?.subtitle?.takeIf { it.isNotBlank() } ?: initialSubtitle
    val displayArtwork = loadedDetail?.artworkUrl?.takeIf { it.isNotBlank() } ?: initialArtworkUrl

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("detail_screen")
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomContentPadding)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    if (displayArtwork.isNotBlank()) {
                        AsyncImage(
                            model = displayArtwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .blur(70.dp)
                                .alpha(0.50f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.25f),
                                        SonoraBackground.copy(alpha = 0.72f),
                                        SonoraBackground
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .minimumInteractiveComponentSize()
                                    .testTag("detail_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Volver",
                                    tint = SonoraTextPrimary
                                )
                            }

                            IconButton(
                                onClick = {
                                    viewModel.toggleSaveCollection(
                                        BrowseCard(
                                            id = browseId,
                                            title = displayTitle,
                                            subtitle = displaySubtitle,
                                            artworkUrl = displayArtwork,
                                            kind = loadedDetail?.kind ?: ItemKind.ALBUM
                                        ),
                                        isSaved
                                    )
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .minimumInteractiveComponentSize()
                                    .testTag("detail_save_button")
                            ) {
                                Icon(
                                    imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                    contentDescription = if (isSaved) "Quitar de biblioteca" else "Guardar en biblioteca",
                                    tint = if (isSaved) accent else SonoraTextPrimary
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        SonoraArtwork(
                            url = displayArtwork,
                            contentDescription = displayTitle,
                            modifier = Modifier.size(210.dp),
                            cornerRadius = 20.dp,
                            isCircular = loadedDetail?.kind == ItemKind.ARTIST
                        )

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.headlineLarge,
                            color = SonoraTextPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (displaySubtitle.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = displaySubtitle,
                                style = MaterialTheme.typography.bodyLarge,
                                color = SonoraTextSecondary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        val tracks = loadedDetail?.tracks.orEmpty()
                        if (tracks.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { onPlayQueue(tracks, 0) },
                                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("detail_play_all_button")
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Reproducir", style = MaterialTheme.typography.titleMedium)
                                }
                                OutlinedButton(
                                    onClick = { onPlayQueue(tracks.shuffled(), 0) },
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        contentColor = SonoraTextPrimary
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("detail_shuffle_button")
                                ) {
                                    Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = accent)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Aleatorio", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }

            when (val st = detailState) {
                is UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = accent)
                        }
                    }
                }

                is UiState.Error -> {
                    item {
                        ErrorStateGlass(
                            message = st.message,
                            onRetry = { viewModel.loadDetail(browseId, params) }
                        )
                    }
                }

                is UiState.Success -> {
                    val detail = st.data
                    if (detail.tracks.isNotEmpty()) {
                        item {
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(modifier = Modifier.padding(6.dp)) {
                                    detail.tracks.forEachIndexed { idx, track ->
                                        TrackRowItem(
                                            track = track,
                                            isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                            isActuallyPlaying = playbackState.isPlaying,
                                            indexLabel = "%02d".format(idx + 1),
                                            onClick = { onPlayQueue(detail.tracks, idx) },
                                            onMoreClick = { onTrackMore(track) }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    itemsIndexed(detail.shelves, key = { idx, it -> "detail_shelf_${idx}_${it.title}" }) { _, shelf ->
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = shelf.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = SonoraTextPrimary,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            if (shelf.items.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    itemsIndexed(shelf.items, key = { idx, card -> "rel_${shelf.title}_${idx}_${card.id}" }) { _, card ->
                                        BrowseCardItem(card = card, onClick = { onOpenCard(card) })
                                    }
                                }
                            }
                            if (shelf.tracks.isNotEmpty()) {
                                if (shelf.items.isNotEmpty()) Spacer(Modifier.height(10.dp))
                                GlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Column(modifier = Modifier.padding(6.dp)) {
                                        shelf.tracks.forEachIndexed { idx, track ->
                                            TrackRowItem(
                                                track = track,
                                                isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                                isActuallyPlaying = playbackState.isPlaying,
                                                indexLabel = "%02d".format(idx + 1),
                                                onClick = { onPlayQueue(shelf.tracks, idx) },
                                                onMoreClick = { onTrackMore(track) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocalPlaylistDetailScreen(
    playlistId: Long,
    viewModel: SonoraViewModel,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val playlist by viewModel.observePlaylist(playlistId).collectAsState(initial = null)
    val tracks by viewModel.observePlaylistTracks(playlistId).collectAsState(initial = emptyList())
    val transitions by viewModel.observePlaylistTransitions(playlistId).collectAsState(initial = emptyList())
    var showFragmentMixer by remember { mutableStateOf(false) }
    val accent = LocalSonoraAccent.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("local_playlist_screen"),
        contentPadding = PaddingValues(top = 12.dp, bottom = bottomContentPadding)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver",
                            tint = SonoraTextPrimary
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SonoraArtwork(
                    url = playlist?.coverUrl ?: tracks.firstOrNull()?.artworkUrl.orEmpty(),
                    contentDescription = playlist?.name ?: "Playlist",
                    modifier = Modifier.size(190.dp),
                    cornerRadius = 20.dp
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = playlist?.name ?: "Playlist",
                    style = MaterialTheme.typography.headlineLarge,
                    color = SonoraTextPrimary
                )
                if (!playlist?.description.isNullOrBlank()) {
                    Text(
                        text = playlist?.description.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary
                    )
                }
                Text(
                    text = "${tracks.size} canciones",
                    style = MaterialTheme.typography.labelMedium,
                    color = SonoraTextSecondary
                )
                if (tracks.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.playPlaylistWithTransitions(tracks, 0, playlistId) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Reproducir", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onPlayQueue(tracks.shuffled(), 0) },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                contentColor = SonoraTextPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = accent)
                            Spacer(Modifier.width(4.dp))
                            Text("Aleatorio", maxLines = 1)
                        }
                    }
                    if (tracks.size >= 2) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { showFragmentMixer = true },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = accent.copy(alpha = 0.14f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("local_playlist_mixer_button")
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                            Spacer(Modifier.width(8.dp))
                            val countLabel = if (transitions.isEmpty()) {
                                "Mezclar fragmentos entre pistas"
                            } else {
                                "Mezclador (${transitions.size} transiciones activas)"
                            }
                            Text(countLabel, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }

        if (tracks.isEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Text(
                        text = "Aún no hay canciones en esta playlist. Usa el botón de opciones (⋮) en cualquier canción para agregarla.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        } else {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        tracks.forEachIndexed { idx, track ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.weight(1f)) {
                                    TrackRowItem(
                                        track = track,
                                        isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                        isActuallyPlaying = playbackState.isPlaying,
                                        indexLabel = "%02d".format(idx + 1),
                                        onClick = { viewModel.playPlaylistWithTransitions(tracks, idx, playlistId) },
                                        onMoreClick = { onTrackMore(track) }
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.removeTrackFromPlaylist(playlistId, track.videoId) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Quitar de playlist",
                                        tint = SonoraTextSecondary
                                    )
                                }
                            }
                            val nextTrack = tracks.getOrNull(idx + 1)
                            val pairSpec = if (nextTrack != null) {
                                transitions.firstOrNull {
                                    it.fromVideoId == track.videoId && it.toVideoId == nextTrack.videoId
                                }
                            } else null
                            if (pairSpec != null) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = accent.copy(alpha = 0.16f),
                                    modifier = Modifier.padding(start = 52.dp, bottom = 6.dp)
                                ) {
                                    Text(
                                        text = "Cruce ${pairSpec.crossfadeMs / 1000}s • ${pairSpec.curveType.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFragmentMixer && tracks.size >= 2) {
        FragmentMixerSheet(
            tracks = tracks,
            playlistId = playlistId,
            existingSpecs = transitions,
            onDismiss = { showFragmentMixer = false },
            onSavePlaylistSpec = { spec -> viewModel.savePlaylistTransition(spec) },
            onDeletePlaylistSpec = { fromId, toId -> viewModel.deletePlaylistTransition(playlistId, fromId, toId) },
            onPreviewTransition = { outT, exitMs, inT, entryMs, overlapMs, curve ->
                viewModel.previewTransitionPair(outT, exitMs, inT, entryMs, overlapMs, curve)
            },
            onPlayCustomMix = { fragments -> viewModel.playCustomMixFragments(fragments) }
        )
    }
}
