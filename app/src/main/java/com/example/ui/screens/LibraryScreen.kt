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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraAccentSecondary
import com.example.ui.theme.SonoraSuccess
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
    onTrackMore: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val accentSecondary = LocalSonoraAccentSecondary.current
    val settings by viewModel.settings.collectAsState()
    val accountSession by viewModel.accountSession.collectAsState()
    val ytPlaylists by viewModel.ytPlaylists.collectAsState()
    val ytSavedCollections by viewModel.ytSavedCollections.collectAsState()
    val ytLikedSongs by viewModel.ytLikedSongs.collectAsState()
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
    var showLikedExpanded by remember { mutableStateOf(false) }
    var showDownloadsExpanded by remember { mutableStateOf(false) }
    var showLocalExpanded by remember { mutableStateOf(false) }
    var showStatsExpanded by remember { mutableStateOf(false) }
    var showYtLikedExpanded by remember { mutableStateOf(true) }

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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("library_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Configuración",
                            tint = SonoraTextPrimary
                        )
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("create_playlist_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nueva", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // Google / YouTube Music Account Connection Card
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("account_connection_card"),
                shape = RoundedCornerShape(22.dp),
                tint = if (accountSession != null) SonoraSuccess.copy(alpha = 0.2f) else Color(0xFF282838)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (accountSession != null && accountSession!!.avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = accountSession!!.avatarUrl,
                                contentDescription = accountSession!!.accountName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountCircle,
                                    contentDescription = null,
                                    tint = if (accountSession != null) SonoraSuccess else SonoraTextPrimary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = accountSession?.accountName ?: "Cuenta de Google / YouTube Music",
                                style = MaterialTheme.typography.titleLarge,
                                color = SonoraTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (accountSession != null) {
                                    "Conectado • ${accountSession!!.accountHandle.ifBlank { "Sincronización activa" }}"
                                } else {
                                    "Inicia sesión para importar tus playlists y me gusta de YouTube Music"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary,
                                maxLines = 2
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (accountSession == null) {
                        Button(
                            onClick = onOpenAuth,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("google_signin_button")
                        ) {
                            Text("Iniciar sesión con Google", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.syncYouTubeLibrary() },
                                enabled = !isSyncingYt,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("sync_yt_library_button")
                            ) {
                                if (isSyncingYt) {
                                    CircularProgressIndicator(
                                        color = accent,
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Rounded.Sync, contentDescription = null, tint = SonoraTextPrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Sincronizar", color = SonoraTextPrimary)
                                }
                            }
                            OutlinedButton(
                                onClick = onSignOut,
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("signout_yt_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = accent)
                                Spacer(Modifier.width(6.dp))
                                Text("Cerrar sesión", color = accent)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Spotify Import Glass Card
        item {
            val spotifyGreen = Color(0xFF1DB954)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("library_spotify_import_card"),
                shape = RoundedCornerShape(20.dp),
                tint = spotifyGreen.copy(alpha = 0.35f),
                alphaTop = 0.24f,
                alphaBottom = 0.72f,
                onClick = onOpenSpotifyImport
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(spotifyGreen.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LibraryAdd,
                            contentDescription = null,
                            tint = spotifyGreen,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Importar playlist de Spotify",
                            style = MaterialTheme.typography.titleLarge,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = "Pega el enlace de Spotify o una lista de canciones",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // Synced YouTube Music Library section (if signed in)
        if (accountSession != null) {
            if (!isSyncingYt && (ytSyncError != null || (ytLikedSongs.isEmpty() && ytPlaylists.isEmpty() && ytSavedCollections.isEmpty()))) {
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("yt_sync_notice_card"),
                        shape = RoundedCornerShape(18.dp),
                        tint = accent.copy(alpha = 0.22f)
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

            if (ytLikedSongs.isNotEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("yt_liked_songs_card"),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { showYtLikedExpanded = !showYtLikedExpanded }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = accent)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Me gusta de YouTube Music",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SonoraTextPrimary
                                )
                                Text(
                                    text = "${ytLikedSongs.size} canciones sincronizadas • ${if (showYtLikedExpanded) "Ocultar" else "Ver canciones"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonoraTextSecondary
                                )
                            }
                            IconButton(
                                onClick = { onPlayTrack(ytLikedSongs.first(), ytLikedSongs) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Reproducir", tint = SonoraTextPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (showYtLikedExpanded) {
                    item {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                ytLikedSongs.take(25).forEach { track ->
                                    TrackRowItem(
                                        track = track,
                                        isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                        isActuallyPlaying = playbackState.isPlaying,
                                        onClick = { onPlayTrack(track, ytLikedSongs) },
                                        onMoreClick = { onTrackMore(track) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
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
        }

        // Offline Downloaded Tracks card
        item {
            val totalMb = downloadedTracks.sumOf { it.fileSizeBytes } / (1024f * 1024f)
            val offlineQueue = remember(downloadedTracks) { downloadedTracks.map { it.toTrack() } }
            val activeCount = activeDownloads.size

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("offline_downloads_card"),
                shape = RoundedCornerShape(22.dp),
                tint = Color(0xFF0A84FF).copy(alpha = 0.28f),
                alphaTop = 0.34f,
                alphaBottom = 0.78f,
                onClick = { showDownloadsExpanded = !showDownloadsExpanded }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0A84FF).copy(alpha = 0.24f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DownloadDone,
                            contentDescription = null,
                            tint = Color(0xFF64D2FF),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Descargas sin conexión",
                            style = MaterialTheme.typography.titleLarge,
                            color = SonoraTextPrimary
                        )
                        val infoText = buildString {
                            append("${downloadedTracks.size} canciones")
                            if (downloadedTracks.isNotEmpty()) {
                                append(" • %.1f MB".format(totalMb))
                            }
                            if (activeCount > 0) {
                                append(" • $activeCount descargando")
                            }
                        }
                        Text(
                            text = infoText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
                    }
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
                                tint = SonoraTextPrimary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

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

        // Local Audio Files (SAF zero-permission library)
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("local_music_card"),
                shape = RoundedCornerShape(22.dp),
                tint = Color(0xFFBF5AF2).copy(alpha = 0.24f),
                alphaTop = 0.32f,
                alphaBottom = 0.76f,
                onClick = { showLocalExpanded = !showLocalExpanded }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFBF5AF2).copy(alpha = 0.24f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = Color(0xFFE0A8FF),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Música local en tu dispositivo",
                                style = MaterialTheme.typography.titleLarge,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = if (localTracks.isEmpty()) {
                                    "Selecciona carpetas con MP3, FLAC, M4A u OGG (y letras .lrc)"
                                } else {
                                    "${localTracks.size} pistas • ${settings.localFolderUris.size} carpeta(s)"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary
                            )
                        }
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
                                    tint = SonoraTextPrimary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
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
                                .height(48.dp)
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
                                    .height(48.dp)
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
                                    onClick = { showLocalExpanded = true },
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
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (showLocalExpanded && localTracks.isNotEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        localTracks.forEach { track ->
                            TrackRowItem(
                                track = track,
                                isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                isActuallyPlaying = playbackState.isPlaying,
                                onClick = { onPlayTrack(track, localTracks) },
                                onMoreClick = { onTrackMore(track) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // Listening Stats Summary Card
        if (listeningStats.totalPlays > 0) {
            item {
                val hours = listeningStats.estimatedMinutes / 60L
                val mins = listeningStats.estimatedMinutes % 60L
                val timeLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins} min"

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("listening_stats_card"),
                    shape = RoundedCornerShape(22.dp),
                    tint = accentSecondary.copy(alpha = 0.25f),
                    onClick = { showStatsExpanded = !showStatsExpanded }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(accent.copy(alpha = 0.20f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BarChart,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Tu actividad sonora",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = SonoraTextPrimary
                                )
                                Text(
                                    text = "${listeningStats.totalPlays} reproducciones • ${listeningStats.uniqueTracks} temas • $timeLabel",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonoraTextSecondary
                                )
                            }
                        }

                        if (showStatsExpanded) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            Spacer(Modifier.height(10.dp))

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
                                Spacer(Modifier.height(12.dp))
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
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = SonoraTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stat.track.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = stat.track.artist,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = SonoraTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = "${stat.playCount}×",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = accent,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        // Featured Local Liked Songs glass card
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("liked_songs_card"),
                shape = RoundedCornerShape(22.dp),
                tint = accent.copy(alpha = 0.45f),
                alphaTop = 0.42f,
                alphaBottom = 0.78f,
                onClick = { showLikedExpanded = !showLikedExpanded }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(listOf(accent, accentSecondary))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tus me gusta en Sonora",
                            style = MaterialTheme.typography.titleLarge,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = "${likedSongs.size} canciones guardadas",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
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
                                tint = SonoraTextPrimary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (showLikedExpanded) {
            if (likedSongs.isEmpty()) {
                item {
                    Text(
                        text = "Toca el corazón en cualquier canción para guardarla aquí.",
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
                            likedSongs.forEach { track ->
                                TrackRowItem(
                                    track = track,
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    onClick = { onPlayTrack(track, likedSongs) },
                                    onMoreClick = { onTrackMore(track) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
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
            items(playlists, key = { "pl_${it.id}" }) { playlist ->
                PlaylistLibraryRow(
                    playlist = playlist,
                    viewModel = viewModel,
                    onClick = { onOpenPlaylist(playlist.id) },
                    onDelete = { viewModel.deletePlaylist(playlist.id) }
                )
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
                Spacer(Modifier.height(16.dp))
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
                        recentTracks.take(10).forEach { track ->
                            TrackRowItem(
                                track = track,
                                isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                isActuallyPlaying = playbackState.isPlaying,
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
}

@Composable
private fun PlaylistLibraryRow(
    playlist: PlaylistEntity,
    viewModel: SonoraViewModel,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val count by viewModel.observePlaylistTrackCount(playlist.id).collectAsState(initial = 0)
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .testTag("playlist_row_${playlist.id}"),
        shape = RoundedCornerShape(16.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (playlist.coverUrl.isNotBlank()) {
                SonoraArtwork(
                    url = playlist.coverUrl,
                    contentDescription = playlist.name,
                    modifier = Modifier.size(54.dp),
                    cornerRadius = 12.dp
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.img_playlist_default),
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                    style = MaterialTheme.typography.bodyMedium,
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
        }
    }
}
