package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.LocalMusicRepository
import com.example.data.local.PlaylistEntity
import com.example.data.local.toTrack
import com.example.model.BrowseCard
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.BrowseCardItem
import com.example.ui.components.CreatePlaylistDialog
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.SonoraReplaySheet
import com.example.ui.components.TrackRowItem
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraAccentSecondary
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.viewmodel.SonoraViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    viewModel: SonoraViewModel,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onOpenAuth: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSpotifyImport: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenCard: (BrowseCard) -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onTrackMore: (Track) -> Unit,
    onOpenSongMixer: () -> Unit = {},
    onOpenYtLiked: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenReplay: () -> Unit = {}
) {
    val accent = LocalSonoraAccent.current
    val accentSecondary = LocalSonoraAccentSecondary.current
    val palette = LocalSonoraPalette.current
    val settings by viewModel.settings.collectAsState()
    val accountSession by viewModel.accountSession.collectAsState()
    val ytPlaylists by viewModel.ytPlaylists.collectAsState()
    val ytSavedCollections by viewModel.ytSavedCollections.collectAsState()
    val ytLibraryArtists by viewModel.ytLibraryArtists.collectAsState()
    val ytLikedSongs by viewModel.ytLikedSongs.collectAsState()
    val ytUploadedSongs by viewModel.ytUploadedSongs.collectAsState()
    val isSyncingYt by viewModel.isSyncingYt.collectAsState()
    val ytSyncError by viewModel.ytSyncError.collectAsState()

    val likedSongs by viewModel.likedSongs.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val localTracks by viewModel.localTracks.collectAsState()
    val isScanningLocal by viewModel.isScanningLocal.collectAsState()
    val listeningStats by viewModel.listeningStats.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val savedCollections by viewModel.savedCollections.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showReplayDialog by remember { mutableStateOf(false) }
    var showLikedExpanded by remember { mutableStateOf(false) }
    var showDownloadsExpanded by remember { mutableStateOf(false) }
    var showLocalExpanded by remember { mutableStateOf(false) }
    var showStatsExpanded by remember { mutableStateOf(false) }
    var showYtUploadsExpanded by remember { mutableStateOf(false) }

    val localFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.addLocalFolder(uri)
            showLocalExpanded = true
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("library_screen"),
        contentPadding = PaddingValues(top = 16.dp, bottom = bottomContentPadding)
    ) {
        // 1. Bold title + floating liquid glass capsule (History, Create, Settings/Avatar)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Biblioteca",
                    style = MaterialTheme.typography.displayLarge,
                    color = SonoraTextPrimary
                )

                Row(
                    modifier = Modifier
                        .sonoraLiquidGlassSurface(
                            shape = CircleShape,
                            blurRadius = 10.dp,
                            refractionHeight = 6.dp,
                            refractionAmount = 10.dp,
                            surfaceAlpha = 0.46f
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onOpenHistory,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("library_header_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = "Historial",
                            tint = SonoraTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("create_playlist_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Nueva playlist",
                            tint = SonoraTextPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("library_settings_button")
                    ) {
                        if (accountSession != null && accountSession!!.avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = accountSession!!.avatarUrl,
                                contentDescription = "Ajustes y cuenta",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Configuración",
                                tint = SonoraTextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 2. Swipeable dark-gradient Replay / Sonora Activity & Studio Mixer hero pager
        item {
            val heroPagerState = rememberPagerState(pageCount = { 2 })
            val hours = listeningStats.estimatedMinutes / 60L
            val mins = listeningStats.estimatedMinutes % 60L
            val timeLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins} min"

            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalPager(
                    state = heroPagerState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    if (page == 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            accent.copy(alpha = 0.34f),
                                            palette.elevated,
                                            palette.surface
                                        )
                                    )
                                )
                                .clickable {
                                    if (listeningStats.totalPlays > 0) {
                                        showStatsExpanded = !showStatsExpanded
                                    } else {
                                        onOpenReplay()
                                    }
                                }
                                .padding(16.dp)
                                .testTag("listening_stats_card")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.BarChart,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(25.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Tu actividad sonora",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (listeningStats.totalPlays > 0) {
                                            "${listeningStats.totalPlays} reproducciones • ${listeningStats.uniqueTracks} temas • $timeLabel"
                                        } else {
                                            "Tus artistas y temas más escuchados de la temporada"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.78f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    onClick = onOpenReplay,
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.16f),
                                    modifier = Modifier.testTag("open_sonora_replay_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "Replay",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            accentSecondary.copy(alpha = 0.34f),
                                            palette.elevated,
                                            palette.surface
                                        )
                                    )
                                )
                                .clickable(onClick = onOpenSongMixer)
                                .padding(16.dp)
                                .testTag("library_minimix_card")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(25.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Mezclar 2 canciones",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Edita el punto de transición y mezcla como DJ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.78f),
                                        maxLines = 2
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.72f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(2) { idx ->
                        val selected = heroPagerState.currentPage == idx
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(width = if (selected) 14.dp else 6.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(if (selected) accent else Color.White.copy(alpha = 0.24f))
                        )
                    }
                }
            }

            if (showStatsExpanded && listeningStats.totalPlays > 0) {
                Spacer(Modifier.height(10.dp))
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        if (listeningStats.topArtists.isNotEmpty()) {
                            Text(
                                text = "Artistas más escuchados",
                                style = MaterialTheme.typography.labelLarge,
                                color = SonoraTextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listeningStats.topArtists.take(4).forEach { artistStat ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = 0.08f))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "${artistStat.artist} (${artistStat.totalPlays}×)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = SonoraTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        if (listeningStats.topTracks.isNotEmpty()) {
                            val topTrackList = remember(listeningStats.topTracks) {
                                listeningStats.topTracks.map { it.track }
                            }
                            Text(
                                text = "Top canciones",
                                style = MaterialTheme.typography.labelLarge,
                                color = SonoraTextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            listeningStats.topTracks.take(5).forEachIndexed { idx, stat ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onPlayTrack(stat.track, topTrackList) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = accent,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(22.dp)
                                    )
                                    Text(
                                        text = "${stat.track.title} • ${stat.track.artist}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = SonoraTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${stat.playCount}×",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accent
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }

        // 3. Flat iOS-style grouped chevron card for core library destinations
        item {
            val totalMb = downloadedTracks.sumOf { it.fileSizeBytes } / (1024f * 1024f)
            val offlineQueue = remember(downloadedTracks) { downloadedTracks.map { it.toTrack() } }
            val activeCount = activeDownloads.size

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Tus me gusta en Sonora
                    LibraryGroupedChevronRow(
                        icon = Icons.Rounded.Favorite,
                        iconTint = accent,
                        title = "Tus me gusta",
                        subtitle = "${likedSongs.size} canciones guardadas",
                        onClick = { showLikedExpanded = !showLikedExpanded },
                        trailingContent = {
                            if (likedSongs.isNotEmpty() || ytLikedSongs.isNotEmpty()) {
                                androidx.compose.material3.TextButton(
                                    onClick = onOpenYtLiked,
                                    modifier = Modifier.testTag("library_liked_view_all_button")
                                ) {
                                    Text("Ver todas", color = accent)
                                }
                            }
                            if (likedSongs.isNotEmpty()) {
                                IconButton(
                                    onClick = { onPlayTrack(likedSongs.first(), likedSongs) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("play_liked_songs_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Reproducir Tus me gusta",
                                        tint = SonoraTextPrimary
                                    )
                                }
                            }
                        },
                        showDivider = true,
                        modifier = Modifier.testTag("liked_songs_card")
                    )

                    // Me gusta de YouTube Music (if connected)
                    if (accountSession != null && (ytLikedSongs.isNotEmpty() || isSyncingYt)) {
                        LibraryGroupedChevronRow(
                            icon = Icons.Rounded.Favorite,
                            iconTint = Color(0xFFFF4D6D),
                            title = "Me gusta de YouTube Music",
                            subtitle = if (isSyncingYt) {
                                "${ytLikedSongs.size} canciones • Sincronizando..."
                            } else {
                                "${ytLikedSongs.size} canciones sincronizadas"
                            },
                            onClick = onOpenYtLiked,
                            trailingContent = {
                                if (ytLikedSongs.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onPlayTrack(ytLikedSongs.first(), ytLikedSongs) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Reproducir", tint = SonoraTextPrimary)
                                    }
                                } else if (isSyncingYt) {
                                    CircularProgressIndicator(
                                        color = accent,
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            },
                            showDivider = true,
                            modifier = Modifier.testTag("yt_liked_songs_card")
                        )
                    }

                    // Descargas sin conexión
                    LibraryGroupedChevronRow(
                        icon = Icons.Rounded.DownloadDone,
                        iconTint = Color(0xFF64D2FF),
                        title = "Descargas sin conexión",
                        subtitle = buildString {
                            append("${downloadedTracks.size} canciones")
                            if (downloadedTracks.isNotEmpty()) append(" • %.1f MB".format(totalMb))
                            if (activeCount > 0) append(" • $activeCount descargando")
                        },
                        onClick = { showDownloadsExpanded = !showDownloadsExpanded },
                        trailingContent = {
                            if (offlineQueue.isNotEmpty()) {
                                IconButton(
                                    onClick = { onPlayTrack(offlineQueue.first(), offlineQueue) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("play_offline_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Reproducir descargas",
                                        tint = SonoraTextPrimary
                                    )
                                }
                            }
                        },
                        showDivider = true,
                        modifier = Modifier.testTag("offline_downloads_card")
                    )

                    // Música local en tu dispositivo
                    LibraryGroupedChevronRow(
                        icon = Icons.Rounded.FolderOpen,
                        iconTint = Color(0xFFE0A8FF),
                        title = "Música local",
                        subtitle = if (localTracks.isEmpty()) {
                            "MP3, FLAC, M4A u OGG desde carpetas"
                        } else {
                            "${localTracks.size} pistas • ${settings.localFolderUris.size} carpeta(s)"
                        },
                        onClick = { showLocalExpanded = !showLocalExpanded },
                        trailingContent = {
                            if (localTracks.isNotEmpty()) {
                                IconButton(
                                    onClick = { onPlayTrack(localTracks.first(), localTracks) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("play_local_music_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Reproducir música local",
                                        tint = SonoraTextPrimary
                                    )
                                }
                            }
                        },
                        showDivider = true,
                        modifier = Modifier.testTag("local_music_card")
                    )

                    // Importar de Spotify
                    LibraryGroupedChevronRow(
                        icon = Icons.Rounded.LibraryAdd,
                        iconTint = Color(0xFF1DB954),
                        title = "Importar de Spotify",
                        subtitle = "Trae tus playlists con enlace",
                        onClick = onOpenSpotifyImport,
                        showDivider = true,
                        modifier = Modifier.testTag("library_spotify_import_card")
                    )

                    // Historial de escucha
                    LibraryGroupedChevronRow(
                        icon = Icons.Rounded.History,
                        iconTint = accentSecondary,
                        title = "Historial de escucha",
                        subtitle = "Todas tus reproducciones por fecha",
                        onClick = onOpenHistory,
                        showDivider = false,
                        modifier = Modifier.testTag("library_history_card")
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Inline expandable Liked Songs
        if (showLikedExpanded) {
            if (likedSongs.isEmpty()) {
                item {
                    Text(
                        text = "Toca la estrella o el corazón en cualquier canción para guardarla aquí.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                    )
                }
            } else {
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            likedSongs.forEachIndexed { idx, track ->
                                TrackRowItem(
                                    track = track,
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    showDivider = idx < likedSongs.lastIndex,
                                    onClick = { onPlayTrack(track, likedSongs) },
                                    onMoreClick = { onTrackMore(track) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // Inline expandable Downloads
        if (showDownloadsExpanded) {
            item {
                val offlineQueue = remember(downloadedTracks) { downloadedTracks.map { it.toTrack() } }
                if (offlineQueue.isEmpty()) {
                    Text(
                        text = "Usa el menú (⋮) en cualquier canción para descargarla y escucharla sin internet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                    )
                } else {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            offlineQueue.forEach { track ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        TrackRowItem(
                                            track = track,
                                            isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                            isActuallyPlaying = playbackState.isPlaying,
                                            onClick = { onPlayTrack(track, offlineQueue) },
                                            onMoreClick = { onTrackMore(track) }
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteDownload(track.videoId) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Eliminar descarga",
                                            tint = SonoraTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // Inline expandable Local Folders & Tracks
        if (showLocalExpanded) {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { localFolderPicker.launch(null) },
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("add_local_folder_button")
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, tint = SonoraTextPrimary)
                                Spacer(Modifier.width(6.dp))
                                Text("Agregar carpeta", color = SonoraTextPrimary, maxLines = 1)
                            }
                            if (settings.localFolderUris.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { viewModel.rescanLocalFolders() },
                                    enabled = !isScanningLocal,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .height(44.dp)
                                        .testTag("rescan_local_folders_button")
                                ) {
                                    if (isScanningLocal) {
                                        CircularProgressIndicator(
                                            color = accent,
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Refresh, contentDescription = "Reescanear", tint = SonoraTextPrimary)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Reescanear", color = SonoraTextPrimary)
                                    }
                                }
                            }
                        }

                        if (settings.localFolderUris.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                settings.localFolderUris.forEach { folderUri ->
                                    InputChip(
                                        selected = false,
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = LocalMusicRepository.friendlyFolderLabel(folderUri),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { viewModel.removeLocalFolder(folderUri) },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = "Quitar carpeta",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        },
                                        colors = InputChipDefaults.inputChipColors(
                                            containerColor = Color.White.copy(alpha = 0.08f),
                                            labelColor = SonoraTextPrimary,
                                            trailingIconColor = SonoraTextSecondary
                                        )
                                    )
                                }
                            }
                        }

                        if (localTracks.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            localTracks.forEachIndexed { idx, track ->
                                TrackRowItem(
                                    track = track,
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    showDivider = idx < localTracks.lastIndex,
                                    onClick = { onPlayTrack(track, localTracks) },
                                    onMoreClick = { onTrackMore(track) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // Synced YouTube Music Library shelves (if signed in)
        if (accountSession != null) {
            if (!isSyncingYt && (ytSyncError != null || (ytLikedSongs.isEmpty() && ytPlaylists.isEmpty() && ytSavedCollections.isEmpty() && ytLibraryArtists.isEmpty() && ytUploadedSongs.isEmpty()))) {
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("yt_sync_notice_card"),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = ytSyncError
                                    ?: "No se cargaron tus playlists o canciones de YouTube Music todavía.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextPrimary
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.syncYouTubeLibrary() },
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Reintentar", color = SonoraTextPrimary)
                                }
                                OutlinedButton(
                                    onClick = onOpenAuth,
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Reconectar cuenta", color = accent)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (ytPlaylists.isNotEmpty()) {
                item {
                    Text(
                        text = "Tus playlists de YouTube Music",
                        style = MaterialTheme.typography.headlineMedium,
                        color = SonoraTextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(ytPlaylists, key = { "ytpl_${it.id}" }) { card ->
                            BrowseCardItem(card = card, onClick = { onOpenCard(card) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (ytSavedCollections.isNotEmpty()) {
                item {
                    Text(
                        text = "Álbumes y colecciones de YouTube",
                        style = MaterialTheme.typography.headlineMedium,
                        color = SonoraTextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(ytSavedCollections, key = { "ytcol_${it.id}" }) { card ->
                            BrowseCardItem(card = card, onClick = { onOpenCard(card) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (ytLibraryArtists.isNotEmpty()) {
                item {
                    Text(
                        text = "Artistas de tu biblioteca de YouTube",
                        style = MaterialTheme.typography.headlineMedium,
                        color = SonoraTextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(ytLibraryArtists, key = { "ytart_${it.id}" }) { card ->
                            BrowseCardItem(card = card, onClick = { onOpenCard(card) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (ytUploadedSongs.isNotEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("yt_uploaded_songs_card"),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { showYtUploadsExpanded = !showYtUploadsExpanded }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accentSecondary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Música subida a YouTube Music",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SonoraTextPrimary
                                )
                                Text(
                                    text = "${ytUploadedSongs.size} canciones en tu nube • ${if (showYtUploadsExpanded) "Ocultar" else "Ver canciones"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonoraTextSecondary
                                )
                            }
                            IconButton(
                                onClick = { onPlayTrack(ytUploadedSongs.first(), ytUploadedSongs) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Reproducir música subida", tint = SonoraTextPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (showYtUploadsExpanded) {
                    item {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                val visibleUploads = ytUploadedSongs.take(30)
                                visibleUploads.forEachIndexed { idx, track ->
                                    TrackRowItem(
                                        track = track,
                                        isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                        isActuallyPlaying = playbackState.isPlaying,
                                        showDivider = idx < visibleUploads.lastIndex,
                                        onClick = { onPlayTrack(track, ytUploadedSongs) },
                                        onMoreClick = { onTrackMore(track) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        // Custom & Imported Playlists
        item {
            Text(
                text = "Tus playlists e importadas",
                style = MaterialTheme.typography.headlineMedium,
                color = SonoraTextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }

        if (playlists.isEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    onClick = { showCreateDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.img_playlist_default),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Crea o importa tu primera playlist",
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "Organiza canciones o tráelas desde Spotify",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary
                            )
                        }
                    }
                }
            }
        } else {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    borderWidth = 0.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        playlists.forEachIndexed { idx, playlist ->
                            PlaylistLibraryRow(
                                playlist = playlist,
                                viewModel = viewModel,
                                onClick = { onOpenPlaylist(playlist.id) },
                                onDelete = { viewModel.deletePlaylist(playlist.id) },
                                showDivider = idx < playlists.lastIndex
                            )
                        }
                    }
                }
            }
        }

        // Saved Collections (Albums / Playlists from YouTube Music)
        if (savedCollections.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Álbumes y colecciones guardadas",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(savedCollections, key = { "saved_${it.id}" }) { card ->
                        BrowseCardItem(card = card, onClick = { onOpenCard(card) })
                    }
                }
            }
        }

        // Recent tracks
        if (recentTracks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Historial reciente",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        val visibleRecent = recentTracks.take(10)
                        visibleRecent.forEachIndexed { idx, track ->
                            TrackRowItem(
                                track = track,
                                isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                isActuallyPlaying = playbackState.isPlaying,
                                showDivider = idx < visibleRecent.lastIndex,
                                onClick = { onPlayTrack(track, recentTracks) },
                                onMoreClick = { onTrackMore(track) }
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
            onConfirm = { name, desc ->
                viewModel.createPlaylist(name, desc)
                showCreateDialog = false
            }
        )
    }

    if (showReplayDialog) {
        SonoraReplaySheet(
            stats = listeningStats,
            onDismiss = { showReplayDialog = false }
        )
    }
}

@Composable
private fun LibraryGroupedChevronRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iconTint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailingContent()
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(20.dp)
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp, end = 14.dp),
                thickness = 0.5.dp,
                color = Color.White.copy(alpha = 0.08f)
            )
        }
    }
}

@Composable
private fun PlaylistLibraryRow(
    playlist: PlaylistEntity,
    viewModel: SonoraViewModel,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    showDivider: Boolean
) {
    val count by viewModel.observePlaylistTrackCount(playlist.id).collectAsState(initial = 0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("playlist_row_${playlist.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (playlist.coverUrl.isNotBlank()) {
                SonoraArtwork(
                    url = playlist.coverUrl,
                    contentDescription = playlist.name,
                    modifier = Modifier.size(50.dp),
                    cornerRadius = 11.dp
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.img_playlist_default),
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(11.dp))
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = if (playlist.description.isNotBlank()) {
                    "$count canciones • ${playlist.description}"
                } else "$count canciones"
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_playlist_${playlist.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Eliminar playlist ${playlist.name}",
                    tint = SonoraTextSecondary
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(20.dp)
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 74.dp, end = 12.dp),
                thickness = 0.5.dp,
                color = Color.White.copy(alpha = 0.08f)
            )
        }
    }
}
