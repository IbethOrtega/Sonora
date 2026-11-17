package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.example.data.local.AccountSessionEntity
import com.example.model.BrowseCard
import com.example.model.HomeShelf
import com.example.model.Track
import com.example.player.DjLiveState
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
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    homeState: UiState<List<HomeShelf>>,
    personalizedShelves: List<HomeShelf>,
    suggestedSongSet: List<Track>,
    ytPlaylists: List<BrowseCard>,
    recentTracks: List<Track>,
    mostPlayedTracks: List<Track>,
    accountSession: AccountSessionEntity?,
    playbackState: PlaybackUiState,
    djLiveState: DjLiveState,
    autoMixEnabled: Boolean,
    isRefreshing: Boolean,
    bottomContentPadding: Dp,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenAuth: () -> Unit,
    onSyncLibrary: () -> Unit,
    onSignOut: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSpotifyImport: () -> Unit,
    onStartDjLive: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onPlayAutoMixSet: (List<Track>) -> Unit,
    onOpenCard: (BrowseCard) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val pullState = rememberPullToRefreshState()
    var showProfileMenu by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_pull_to_refresh"),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                containerColor = Color(0xCC1D1D28),
                color = accent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
        }
    ) {
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

                            // Compact Circular Google Profile Avatar Button + Liquid Glass Mini Menu
                            Box {
                                val avatarRim = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.50f),
                                        Color.White.copy(alpha = 0.14f),
                                        Color.White.copy(alpha = 0.30f)
                                    )
                                )
                                Box(
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(CircleShape)
                                        .clickable { showProfileMenu = true }
                                        .testTag("home_profile_menu_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.White.copy(alpha = 0.22f),
                                                        Color.White.copy(alpha = 0.08f)
                                                    )
                                                )
                                            )
                                            .border(BorderStroke(1.dp, avatarRim), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (accountSession != null && accountSession.avatarUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = accountSession.avatarUrl,
                                                contentDescription = "Perfil de ${accountSession.accountName}",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(2.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.AccountCircle,
                                                contentDescription = "Perfil y ajustes",
                                                tint = if (accountSession != null) SonoraSuccess else SonoraTextPrimary,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }

                                    if (accountSession != null) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(3.dp)
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(SonoraSuccess)
                                                .border(1.5.dp, Color(0xFF0A0A0E), CircleShape)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showProfileMenu,
                                    onDismissRequest = { showProfileMenu = false },
                                    containerColor = Color(0xEB181824),
                                    shape = RoundedCornerShape(22.dp),
                                    border = BorderStroke(
                                        0.9.dp,
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.40f),
                                                Color.White.copy(alpha = 0.12f)
                                            )
                                        )
                                    ),
                                    modifier = Modifier
                                        .width(268.dp)
                                        .testTag("home_profile_popover_menu")
                                ) {
                                    // Active Profile Header
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.12f))
                                                .border(0.8.dp, Color.White.copy(alpha = 0.28f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (accountSession != null && accountSession.avatarUrl.isNotBlank()) {
                                                AsyncImage(
                                                    model = accountSession.avatarUrl,
                                                    contentDescription = accountSession.accountName,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(CircleShape)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Rounded.AccountCircle,
                                                    contentDescription = null,
                                                    tint = if (accountSession != null) SonoraSuccess else SonoraTextPrimary,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = accountSession?.accountName ?: "Modo Invitado",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = SonoraTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (accountSession != null) {
                                                    accountSession.accountHandle.ifBlank { "Cuenta de Google conectada" }
                                                } else {
                                                    "Conecta tu perfil de Google"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (accountSession != null) SonoraSuccess else SonoraTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    HorizontalDivider(
                                        color = Color.White.copy(alpha = 0.10f),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (accountSession == null) {
                                                    "Iniciar sesión con Google"
                                                } else {
                                                    "Cambiar perfil de Google"
                                                },
                                                color = SonoraTextPrimary
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.SwapHoriz,
                                                contentDescription = null,
                                                tint = accent
                                            )
                                        },
                                        onClick = {
                                            showProfileMenu = false
                                            onOpenAuth()
                                        },
                                        modifier = Modifier.testTag("profile_menu_switch_account")
                                    )

                                    if (accountSession != null) {
                                        DropdownMenuItem(
                                            text = { Text("Sincronizar biblioteca", color = SonoraTextPrimary) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.Sync,
                                                    contentDescription = null,
                                                    tint = SonoraTextPrimary
                                                )
                                            },
                                            onClick = {
                                                showProfileMenu = false
                                                onSyncLibrary()
                                            },
                                            modifier = Modifier.testTag("profile_menu_sync")
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Cerrar sesión", color = accent) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.Logout,
                                                    contentDescription = null,
                                                    tint = accent
                                                )
                                            },
                                            onClick = {
                                                showProfileMenu = false
                                                onSignOut()
                                            },
                                            modifier = Modifier.testTag("profile_menu_signout")
                                        )
                                    }

                                    DropdownMenuItem(
                                        text = { Text("Mi biblioteca", color = SonoraTextPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.LibraryMusic,
                                                contentDescription = null,
                                                tint = SonoraTextPrimary
                                            )
                                        },
                                        onClick = {
                                            showProfileMenu = false
                                            onOpenLibrary()
                                        },
                                        modifier = Modifier.testTag("profile_menu_library")
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Importar de Spotify", color = SonoraTextPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.LibraryAdd,
                                                contentDescription = null,
                                                tint = Color(0xFF1DB954)
                                            )
                                        },
                                        onClick = {
                                            showProfileMenu = false
                                            onOpenSpotifyImport()
                                        },
                                        modifier = Modifier.testTag("profile_menu_spotify")
                                    )

                                    HorizontalDivider(
                                        color = Color.White.copy(alpha = 0.10f),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Ajustes y calidad de audio",
                                                fontWeight = FontWeight.SemiBold,
                                                color = SonoraTextPrimary
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Settings,
                                                contentDescription = "Ajustes",
                                                tint = accent
                                            )
                                        },
                                        onClick = {
                                            showProfileMenu = false
                                            onOpenSettings()
                                        },
                                        modifier = Modifier.testTag("profile_menu_settings_button")
                                    )
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
                                    "Hola, ${accountSession.accountName.substringBefore(" ")}"
                                } else {
                                    "Tu música de YouTube Music con cristal líquido"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary
                            )
                        }
                    }
                }
            }

            // Sonora DJ Live interactive card
            item {
                val djTransition = rememberInfiniteTransition(label = "dj_home_orb")
                val orbPulse = djTransition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
                    label = "orb_pulse"
                ).value

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("home_dj_live_card"),
                    shape = RoundedCornerShape(22.dp),
                    tint = accent.copy(alpha = 0.42f),
                    alphaTop = 0.30f,
                    alphaBottom = 0.56f,
                    onClick = { if (!djLiveState.isActive) onStartDjLive() else onSwitchDjVibe() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size((44f * if (djLiveState.isActive) orbPulse else 1f).dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(accent, accent.copy(alpha = 0.25f))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "Sonora DJ Live",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (djLiveState.isActive) "DJ Live • ${djLiveState.currentVibe.label}" else "Sonora DJ Live",
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = when {
                                    djLiveState.isLoading -> "Mezclando tu sesión personalizada..."
                                    djLiveState.isActive && djLiveState.spokenCaption.isNotBlank() -> djLiveState.spokenCaption
                                    else -> "Sesión con voz DJ y mezclas personalizadas según tu gusto"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (djLiveState.isActive) {
                            IconButton(
                                onClick = onSwitchDjVibe,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("home_dj_switch_vibe")
                            ) {
                                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Cambiar vibra", tint = Color.White)
                            }
                            IconButton(
                                onClick = onStopDjLive,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("home_dj_stop")
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = "Detener DJ", tint = accent)
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.16f),
                                border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "Iniciar",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // Quick action banner for Spotify Import
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("home_spotify_import_banner"),
                    shape = RoundedCornerShape(20.dp),
                    tint = Color(0xFF1DB954).copy(alpha = 0.32f),
                    alphaTop = 0.24f,
                    alphaBottom = 0.50f,
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

            // Dynamic Refreshed Song Suggestion Set ("Set de sugerencias")
            if (suggestedSongSet.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SET DE SUGERENCIAS • ACTUALIZADO",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent
                            )
                            Text(
                                text = "Sugerencias para tu sesión",
                                style = MaterialTheme.typography.headlineMedium,
                                color = SonoraTextPrimary
                            )
                        }
                        IconButton(
                            onClick = onRefresh,
                            enabled = !isRefreshing,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("home_refresh_suggestions_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Actualizar sugerencias",
                                tint = SonoraTextSecondary
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("home_suggested_set_card"),
                        shape = RoundedCornerShape(22.dp),
                        tint = accent.copy(alpha = 0.24f),
                        alphaTop = 0.34f,
                        alphaBottom = 0.56f
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    onClick = { onPlayTrack(suggestedSongSet.first(), suggestedSongSet) },
                                    shape = RoundedCornerShape(50),
                                    color = accent.copy(alpha = 0.85f),
                                    modifier = Modifier.testTag("play_suggested_set_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Reproducir set (${suggestedSongSet.size})",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Color.White
                                        )
                                    }
                                }

                                Surface(
                                    onClick = { onPlayAutoMixSet(suggestedSongSet) },
                                    shape = RoundedCornerShape(50),
                                    color = if (autoMixEnabled) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.11f),
                                    border = BorderStroke(
                                        0.7.dp,
                                        if (autoMixEnabled) accent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.26f)
                                    ),
                                    modifier = Modifier.testTag("home_suggested_automix_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = if (autoMixEnabled) accent else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "AutoMix",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Color.White
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            suggestedSongSet.take(8).forEachIndexed { idx, track ->
                                TrackRowItem(
                                    track = track,
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    indexLabel = "${idx + 1}",
                                    onClick = { onPlayTrack(track, suggestedSongSet) },
                                    onMoreClick = { onTrackMore(track) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
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
                                shape = RoundedCornerShape(18.dp),
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
                                                .background(Color.White.copy(alpha = 0.22f))
                                                .border(0.7.dp, Color.White.copy(alpha = 0.40f), CircleShape),
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

            if (mostPlayedTracks.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "TU RESUMEN PERSONAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Text(
                        text = "Tus más escuchadas",
                        style = MaterialTheme.typography.headlineMedium,
                        color = SonoraTextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("home_most_played_card"),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            mostPlayedTracks.take(6).forEach { track ->
                                TrackRowItem(
                                    track = track,
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    onClick = { onPlayTrack(track, mostPlayedTracks) },
                                    onMoreClick = { onTrackMore(track) }
                                )
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
}
