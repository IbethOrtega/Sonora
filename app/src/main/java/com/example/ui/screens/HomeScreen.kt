package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.AccountSessionEntity
import com.example.model.BrowseCard
import com.example.model.HomeShelf
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.AdFreeGlassBadge
import com.example.ui.components.BrowseCardItem
import com.example.ui.components.ErrorStateGlass
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraBackground
import com.example.ui.theme.SonoraSuccess
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.viewmodel.UiState

@Composable
fun HomeScreen(
    homeState: UiState<List<HomeShelf>>,
    personalizedShelves: List<HomeShelf>,
    ytPlaylists: List<BrowseCard>,
    recentTracks: List<Track>,
    accountSession: AccountSessionEntity?,
    playbackState: PlaybackUiState,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onRetry: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSpotifyImport: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onOpenCard: (BrowseCard) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.img_hero_listening),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.55f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    SonoraBackground.copy(alpha = 0.65f),
                                    SonoraBackground
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AdFreeGlassBadge()

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = onOpenAuth,
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.12f),
                                border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.24f)),
                                modifier = Modifier.testTag("home_account_pill")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (accountSession != null && accountSession.avatarUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = accountSession.avatarUrl,
                                            contentDescription = accountSession.accountName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.AccountCircle,
                                            contentDescription = "Cuenta de Google",
                                            tint = if (accountSession != null) SonoraSuccess else SonoraTextPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Text(
                                        text = accountSession?.accountName?.substringBefore(" ") ?: "Iniciar sesión",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SonoraTextPrimary,
                                        maxLines = 1
                                    )
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.12f),
                                border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.24f))
                            ) {
                                IconButton(
                                    onClick = onOpenSettings,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .minimumInteractiveComponentSize()
                                        .testTag("home_settings_button")
                                ) {
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

                    Column {
                        Text(
                            text = "Inicio",
                            style = MaterialTheme.typography.displayLarge,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = if (accountSession != null) {
                                "Conectado como ${accountSession.accountName}"
                            } else {
                                "Tu música de YouTube Music con diseño cristalino"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
                    }
                }
            }
        }

        // Quick action banner for Spotify Import
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("home_spotify_import_banner"),
                shape = RoundedCornerShape(18.dp),
                tint = Color(0xFF1DB954).copy(alpha = 0.35f),
                alphaTop = 0.26f,
                alphaBottom = 0.72f,
                onClick = onOpenSpotifyImport
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LibraryAdd,
                        contentDescription = null,
                        tint = Color(0xFF1DB954),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Importar playlists de Spotify",
                            style = MaterialTheme.typography.titleMedium,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = "Trae tus playlists con un enlace y escúchalas sin anuncios",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (recentTracks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Escuchado recientemente",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentTracks.take(10), key = { "recent_${it.videoId}" }) { track ->
                        GlassCard(
                            modifier = Modifier
                                .width(142.dp)
                                .testTag("recent_card_${track.videoId}"),
                            shape = RoundedCornerShape(16.dp),
                            onClick = { onPlayTrack(track, recentTracks) }
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Box(modifier = Modifier.size(126.dp)) {
                                    SonoraArtwork(
                                        url = track.artworkUrl,
                                        contentDescription = track.title,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 12.dp
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color.Black.copy(alpha = 0.62f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = "Reproducir ${track.title}",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = SonoraTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonoraTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        if (accountSession != null && ytPlaylists.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "TU CUENTA DE YOUTUBE MUSIC",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Text(
                    text = "Tus playlists sincronizadas",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(ytPlaylists.take(12), key = { "home_ytpl_${it.id}" }) { card ->
                        BrowseCardItem(card = card, onClick = { onOpenCard(card) })
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        if (personalizedShelves.isNotEmpty()) {
            items(personalizedShelves, key = { "personalized_${it.title}" }) { shelf ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        if (!shelf.subtitle.isNullOrBlank()) {
                            Text(
                                text = shelf.subtitle.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = accent
                            )
                        }
                        Text(
                            text = shelf.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            shelf.tracks.take(6).forEach { track ->
                                TrackRowItem(
                                    track = track,
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    onClick = { onPlayTrack(track, shelf.tracks) },
                                    onMoreClick = { onTrackMore(track) }
                                )
                            }
                        }
                    }
                }
            }
        }

        when (homeState) {
            is UiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = accent)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Conectando con YouTube Music...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary
                            )
                        }
                    }
                }
            }

            is UiState.Error -> {
                item {
                    ErrorStateGlass(
                        message = homeState.message,
                        onRetry = onRetry
                    )
                }
            }

            is UiState.Success -> {
                val shelves = homeState.data
                items(shelves, key = { it.title }) { shelf ->
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            if (!shelf.subtitle.isNullOrBlank()) {
                                Text(
                                    text = shelf.subtitle.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent
                                )
                            }
                            Text(
                                text = shelf.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = SonoraTextPrimary
                            )
                        }
                        Spacer(Modifier.height(10.dp))

                        if (shelf.tracks.isNotEmpty()) {
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(modifier = Modifier.padding(6.dp)) {
                                    shelf.tracks.take(6).forEach { track ->
                                        TrackRowItem(
                                            track = track,
                                            isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                            isActuallyPlaying = playbackState.isPlaying,
                                            onClick = { onPlayTrack(track, shelf.tracks) },
                                            onMoreClick = { onTrackMore(track) }
                                        )
                                    }
                                }
                            }
                        }

                        if (shelf.items.isNotEmpty()) {
                            if (shelf.tracks.isNotEmpty()) Spacer(Modifier.height(12.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(shelf.items, key = { "${shelf.title}_${it.id}" }) { card ->
                                    BrowseCardItem(
                                        card = card,
                                        onClick = { onOpenCard(card) }
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
