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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import com.example.data.network.YouTubeMusicClient
import com.example.model.BrowseCard
import com.example.model.HomeFilterChip
import com.example.model.HomeShelf
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.ui.components.BrowseCardItem
import com.example.ui.components.ErrorStateGlass
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.SonoraLogoGlassBadge
import com.example.ui.components.TrackRowItem
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraAccentSecondary
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraSuccess
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    homeState: UiState<List<HomeShelf>>,
    homeChips: List<HomeFilterChip> = emptyList(),
    personalizedShelves: List<HomeShelf>,
    suggestedSongSet: List<Track>,
    ytPlaylists: List<BrowseCard>,
    recentTracks: List<Track>,
    mostPlayedTracks: List<Track>,
    accountSession: AccountSessionEntity?,
    likedCount: Int = 0,
    playbackState: PlaybackUiState,
    djLiveState: DjLiveState,
    autoMixEnabled: Boolean,
    isRefreshing: Boolean,
    bottomContentPadding: Dp,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSelectHomeChip: (HomeFilterChip) -> Unit = {},
    onOpenAuth: () -> Unit,
    onSyncLibrary: () -> Unit,
    onSignOut: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenLikedSongs: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenSpotifyImport: () -> Unit,
    onStartDjLive: () -> Unit,
    onOpenVoiceDj: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onPlayAll: (List<Track>) -> Unit = { tracks -> if (tracks.isNotEmpty()) onPlayTrack(tracks.first(), tracks) },
    onPlayAutoMixSet: (List<Track>) -> Unit,
    onOpenCard: (BrowseCard) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val accentSecondary = LocalSonoraAccentSecondary.current
    val pullState = rememberPullToRefreshState()
    var showProfileMenu by remember { mutableStateOf(false) }

    val activeChips = remember(homeChips) {
        homeChips.ifEmpty { YouTubeMusicClient.defaultHomeFilterChips(selectedTitle = "Todo") }
    }
    val alreadyShownVideoIds = remember(suggestedSongSet, personalizedShelves, homeState) {
        buildSet {
            suggestedSongSet.forEach { add(it.videoId) }
            personalizedShelves.flatMap { it.tracks }.forEach { add(it.videoId) }
            (homeState as? UiState.Success)?.data?.flatMap { it.tracks }?.forEach { add(it.videoId) }
        }
    }
    val uniqueRecentTracks = remember(recentTracks, alreadyShownVideoIds) {
        recentTracks.filter { it.videoId !in alreadyShownVideoIds }
    }
    val recentClaimedIds = remember(uniqueRecentTracks, alreadyShownVideoIds) {
        alreadyShownVideoIds + uniqueRecentTracks.take(12).map { it.videoId }
    }
    val uniqueMostPlayed = remember(mostPlayedTracks, recentClaimedIds) {
        mostPlayedTracks.filter { it.videoId !in recentClaimedIds }
    }

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
            // 1. Apple Music-inspired Clean Bold Header & Profile Popover
            item {
                YtmHomeHeader(
                    accountSession = accountSession,
                    accent = accent,
                    showProfileMenu = showProfileMenu,
                    onShowProfileMenu = { showProfileMenu = it },
                    onOpenAuth = onOpenAuth,
                    onSyncLibrary = onSyncLibrary,
                    onSignOut = onSignOut,
                    onOpenLibrary = onOpenLibrary,
                    onOpenSpotifyImport = onOpenSpotifyImport,
                    onOpenSettings = onOpenSettings
                )
            }

            // 2. Mood & Activity Filter Chips Strip
            item {
                MoodChipsStrip(
                    chips = activeChips,
                    accent = accent,
                    onSelectChip = onSelectHomeChip
                )
                Spacer(Modifier.height(14.dp))
            }

            // 2b. "Tus Me gusta" quick-access card
            item {
                LikedSongsQuickAccessCard(
                    likedCount = likedCount,
                    accent = accent,
                    onClick = onOpenLikedSongs
                )
                Spacer(Modifier.height(14.dp))
            }

            // 3. "Volver a escuchar" (Listen Again) clean square tiles (deduped from Quick Picks / Personalized)
            if (uniqueRecentTracks.isNotEmpty()) {
                item {
                    SectionTitleHeader(
                        eyebrow = "TU HISTORIAL",
                        title = "Volver a escuchar",
                        accent = accent
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uniqueRecentTracks.take(12), key = { "recent_${it.videoId}" }) { track ->
                            ListenAgainTile(
                                track = track,
                                isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                accent = accent,
                                onClick = { onPlayTrack(track, uniqueRecentTracks) }
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }

            // 4. "Selección rápida" (Quick Picks) — YTM 4-row swipeable horizontal columns
            if (suggestedSongSet.isNotEmpty()) {
                item {
                    QuickPicksSection(
                        tracks = suggestedSongSet,
                        playbackState = playbackState,
                        accent = accent,
                        autoMixEnabled = autoMixEnabled,
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        onPlayTrack = onPlayTrack,
                        onPlayAll = onPlayAll,
                        onPlayAutoMixSet = onPlayAutoMixSet,
                        onTrackMore = onTrackMore
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            // 5. "Para ti hoy" Featured Hero Carousel
            val heroCards = (homeState as? UiState.Success)?.data
                ?.flatMap { it.items }
                ?.take(5)
                .orEmpty()
            if (suggestedSongSet.isNotEmpty() || heroCards.isNotEmpty()) {
                item {
                    ParaTiCarousel(
                        suggestedSongSet = suggestedSongSet,
                        heroCards = heroCards,
                        accent = accent,
                        accentSecondary = accentSecondary,
                        onPlayAutoMixSet = onPlayAutoMixSet,
                        onOpenCard = onOpenCard
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            // 6. Compact Sonora DJ Live Interactive Card
            item {
                DjLiveHomeCard(
                    djLiveState = djLiveState,
                    accent = accent,
                    onStartDjLive = onStartDjLive,
                    onOpenVoiceDj = onOpenVoiceDj,
                    onSwitchDjVibe = onSwitchDjVibe,
                    onStopDjLive = onStopDjLive
                )
                Spacer(Modifier.height(10.dp))
            }

            // 7. Personalized & YTM Home Shelves
            if (personalizedShelves.isNotEmpty()) {
                items(
                    items = personalizedShelves.mapIndexed { idx, shelf -> idx to shelf },
                    key = { (idx, shelf) -> "personalized_${idx}_${shelf.title}" }
                ) { (_, shelf) ->
                    if (shelf.title == "Mixes para ti" && shelf.tracks.isNotEmpty()) {
                        MixesParaTiShelf(
                            shelf = shelf,
                            accent = accent,
                            onPlayMix = { track -> onPlayTrack(track, emptyList()) }
                        )
                    } else {
                        YtmShelfBlock(
                            shelf = shelf,
                            playbackState = playbackState,
                            accent = accent,
                            onPlayTrack = onPlayTrack,
                            onOpenCard = onOpenCard,
                            onTrackMore = onTrackMore
                        )
                    }
                }
            }

            when (homeState) {
                is UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = accent)
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = "Cargando tu feed de YouTube Music...",
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
                    items(
                        items = shelves.mapIndexed { idx, shelf -> idx to shelf },
                        key = { (idx, shelf) -> "ytm_${idx}_${shelf.title}" }
                    ) { (_, shelf) ->
                        YtmShelfBlock(
                            shelf = shelf,
                            playbackState = playbackState,
                            accent = accent,
                            onPlayTrack = onPlayTrack,
                            onOpenCard = onOpenCard,
                            onTrackMore = onTrackMore
                        )
                    }
                }
            }

            // 8. Synced YTM Playlists
            if (accountSession != null && ytPlaylists.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    SectionTitleHeader(
                        eyebrow = "TU CUENTA DE YOUTUBE MUSIC",
                        title = "Tus playlists sincronizadas",
                        accent = accent
                    )
                    Spacer(Modifier.height(8.dp))
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

            // 9. Most Played Summary (excluding tracks already featured above)
            if (uniqueMostPlayed.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    SectionTitleHeader(
                        eyebrow = "TU RESUMEN PERSONAL",
                        title = "Tus más escuchadas",
                        accent = accent
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("home_most_played_card"),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        val topSix = uniqueMostPlayed.take(6)
                        Column(modifier = Modifier.padding(6.dp)) {
                            topSix.forEachIndexed { idx, track ->
                                TrackRowItem(
                                    track = track,
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    showDivider = idx < topSix.lastIndex,
                                    onClick = { onPlayTrack(track, uniqueMostPlayed) },
                                    onMoreClick = { onTrackMore(track) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            // 10. Spotify Import Banner placed below primary music discovery
            item {
                SpotifyImportHomeBanner(onOpenSpotifyImport = onOpenSpotifyImport)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun YtmHomeHeader(
    accountSession: AccountSessionEntity?,
    accent: Color,
    showProfileMenu: Boolean,
    onShowProfileMenu: (Boolean) -> Unit,
    onOpenAuth: () -> Unit,
    onSyncLibrary: () -> Unit,
    onSignOut: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSpotifyImport: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(152.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.img_hero_listening),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(
                ColorMatrix().apply { setToSaturation(0.0f) }
            ),
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.14f)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SonoraLogoGlassBadge()

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
                            .clickable { onShowProfileMenu(true) }
                            .testTag("home_profile_menu_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .sonoraLiquidGlassSurface(
                                    shape = CircleShape,
                                    blurRadius = 14.dp,
                                    refractionHeight = 6.dp,
                                    refractionAmount = 10.dp,
                                    surfaceAlpha = 0.46f
                                ),
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
                                    modifier = Modifier.size(24.dp)
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
                        onDismissRequest = { onShowProfileMenu(false) },
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
                                onShowProfileMenu(false)
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
                                    onShowProfileMenu(false)
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
                                    onShowProfileMenu(false)
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
                                onShowProfileMenu(false)
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
                                onShowProfileMenu(false)
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
                                onShowProfileMenu(false)
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
                    style = MaterialTheme.typography.displayMedium,
                    color = SonoraTextPrimary
                )
                Text(
                    text = if (accountSession != null) {
                        "Hola, ${accountSession.accountName.substringBefore(" ")}"
                    } else {
                        "Para ti"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MoodChipsStrip(
    chips: List<HomeFilterChip>,
    accent: Color,
    onSelectChip: (HomeFilterChip) -> Unit
) {
    val palette = LocalSonoraPalette.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_mood_chips")
    ) {
        items(chips, key = { "chip_${it.title}" }) { chip ->
            val selected = chip.isSelected
            Surface(
                onClick = { onSelectChip(chip) },
                shape = RoundedCornerShape(50),
                color = if (selected) palette.elevated else palette.surface,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("home_mood_chip_${chip.title}")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = chip.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) accent else SonoraTextPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitleHeader(
    eyebrow: String?,
    title: String,
    accent: Color
) {
    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
        if (!eyebrow.isNullOrBlank()) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SonoraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = SonoraTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ListenAgainTile(
    track: Track,
    isPlayingCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(142.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(2.dp)
            .testTag("recent_card_${track.videoId}")
    ) {
        SonoraArtwork(
            url = track.artworkUrl,
            contentDescription = track.title,
            modifier = Modifier.size(138.dp),
            cornerRadius = 11.dp
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (isPlayingCurrent) accent else SonoraTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = SonoraTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuickPicksSection(
    tracks: List<Track>,
    playbackState: PlaybackUiState,
    accent: Color,
    autoMixEnabled: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onPlayAll: (List<Track>) -> Unit,
    onPlayAutoMixSet: (List<Track>) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "PARA EMPEZAR RADIO",
                style = MaterialTheme.typography.labelSmall,
                color = SonoraTextSecondary
            )
            Text(
                text = "Selección rápida",
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
            .padding(horizontal = 14.dp)
            .testTag("home_suggested_set_card"),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val palette = LocalSonoraPalette.current
                Surface(
                    onClick = { onPlayAll(tracks) },
                    shape = RoundedCornerShape(50),
                    color = palette.elevated,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("play_suggested_set_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Radio,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Iniciar radio (${tracks.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = SonoraTextPrimary
                        )
                    }
                }

                Surface(
                    onClick = { onPlayAutoMixSet(tracks) },
                    shape = RoundedCornerShape(50),
                    color = if (autoMixEnabled) accent.copy(alpha = 0.22f) else palette.elevated,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("home_suggested_automix_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = if (autoMixEnabled) accent else SonoraTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "AutoMix",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (autoMixEnabled) Color.White else SonoraTextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            YtmMultiColumnTrackPager(
                tracks = tracks,
                playbackState = playbackState,
                accent = accent,
                onPlayTrack = { track -> onPlayTrack(track, tracks) },
                onTrackMore = onTrackMore
            )
        }
    }
}

@Composable
private fun YtmMultiColumnTrackPager(
    tracks: List<Track>,
    playbackState: PlaybackUiState,
    accent: Color = LocalSonoraAccent.current,
    onPlayTrack: (Track) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val pages = remember(tracks) { tracks.chunked(4) }
    if (pages.size <= 1) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            tracks.forEachIndexed { idx, track ->
                TrackRowItem(
                    track = track,
                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                    isActuallyPlaying = playbackState.isPlaying,
                    indexLabel = "${idx + 1}",
                    showDivider = idx < tracks.lastIndex,
                    onClick = { onPlayTrack(track) },
                    onMoreClick = { onTrackMore(track) }
                )
            }
        }
    } else {
        val pagerState = rememberPagerState(pageCount = { pages.size })
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 6.dp),
                pageSpacing = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_picks_horizontal_pager")
            ) { pageIndex ->
                val pageTracks = pages[pageIndex]
                Column(modifier = Modifier.fillMaxWidth()) {
                    pageTracks.forEachIndexed { rowIdx, track ->
                        TrackRowItem(
                            track = track,
                            isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                            isActuallyPlaying = playbackState.isPlaying,
                            showDivider = rowIdx < pageTracks.lastIndex,
                            onClick = { onPlayTrack(track) },
                            onMoreClick = { onTrackMore(track) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { idx ->
                    val selected = pagerState.currentPage == idx
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
    }
}

@Composable
private fun LikedSongsQuickAccessCard(
    likedCount: Int,
    accent: Color,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("home_liked_songs_card"),
        shape = RoundedCornerShape(18.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(accent, accent.copy(alpha = 0.52f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tus Me gusta",
                    style = MaterialTheme.typography.titleMedium,
                    color = SonoraTextPrimary
                )
                Text(
                    text = if (likedCount > 0) "$likedCount canciones guardadas" else "Tu biblioteca de favoritas",
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Abrir Tus Me gusta",
                tint = SonoraTextSecondary
            )
        }
    }
}

@Composable
private fun MixesParaTiShelf(
    shelf: HomeShelf,
    accent: Color,
    onPlayMix: (Track) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        SectionTitleHeader(
            eyebrow = shelf.subtitle,
            title = shelf.title,
            accent = accent
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(shelf.tracks, key = { "artist_mix_${it.videoId}" }) { track ->
                val artistLabel = track.artist.substringBefore(",").trim().ifBlank { track.title }
                Column(
                    modifier = Modifier
                        .width(146.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPlayMix(track) }
                        .padding(2.dp)
                        .testTag("home_mix_card_${track.videoId}")
                ) {
                    Box(modifier = Modifier.size(142.dp)) {
                        SonoraArtwork(
                            url = track.artworkUrl,
                            contentDescription = "Mix de $artistLabel",
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 14.dp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.72f)
                                        )
                                    )
                                )
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "MIX",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = "Mix de $artistLabel",
                        style = MaterialTheme.typography.labelLarge,
                        color = SonoraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Radio personalizada",
                        style = MaterialTheme.typography.bodySmall,
                        color = SonoraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ParaTiCarousel(
    suggestedSongSet: List<Track>,
    heroCards: List<BrowseCard>,
    accent: Color,
    accentSecondary: Color,
    onPlayAutoMixSet: (List<Track>) -> Unit,
    onOpenCard: (BrowseCard) -> Unit
) {
    Column {
        SectionTitleHeader(
            eyebrow = "CURADO PARA HOY",
            title = "Para ti hoy",
            accent = accent
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.testTag("home_hero_carousel")
        ) {
            if (suggestedSongSet.isNotEmpty()) {
                item(key = "hero_mix_set") {
                    val lead = suggestedSongSet.first()
                    ParaTiHeroArtCard(
                        artworkUrl = lead.artworkUrl,
                        eyebrow = "MIX DEL DÍA",
                        title = lead.title,
                        subtitle = "${suggestedSongSet.size} temas • Fundido listo",
                        eyebrowColor = accent,
                        onClick = { onPlayAutoMixSet(suggestedSongSet) }
                    )
                }
            }
            items(heroCards, key = { "hero_card_${it.id}" }) { card ->
                ParaTiHeroArtCard(
                    artworkUrl = card.artworkUrl,
                    eyebrow = "DESTACADO",
                    title = card.title,
                    subtitle = card.subtitle,
                    eyebrowColor = Color.White.copy(alpha = 0.78f),
                    onClick = { onOpenCard(card) }
                )
            }
        }
    }
}

@Composable
private fun ParaTiHeroArtCard(
    artworkUrl: String,
    eyebrow: String,
    title: String,
    subtitle: String,
    eyebrowColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(236.dp)
            .height(276.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        SonoraArtwork(
            url = artworkUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 20.dp
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.16f),
                        1.0f to Color.Black.copy(alpha = 0.86f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = eyebrowColor,
                maxLines = 1
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DjLiveHomeCard(
    djLiveState: DjLiveState,
    accent: Color,
    onStartDjLive: () -> Unit,
    onOpenVoiceDj: () -> Unit,
    onSwitchDjVibe: () -> Unit,
    onStopDjLive: () -> Unit
) {
    val djTransition = rememberInfiniteTransition(label = "dj_home_orb")
    val orbPulse = djTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "orb_pulse"
    ).value

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .testTag("home_dj_live_card"),
        shape = RoundedCornerShape(18.dp),
        onClick = { if (!djLiveState.isActive) onStartDjLive() else onSwitchDjVibe() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val palette = LocalSonoraPalette.current
            Box(
                modifier = Modifier
                    .size((40f * if (djLiveState.isActive) orbPulse else 1f).dp)
                    .clip(CircleShape)
                    .background(palette.elevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = "DJ Sony",
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (djLiveState.isActive) "DJ Sony • ${djLiveState.segmentTitle}" else "DJ Sony • Mezcla por voz",
                    style = MaterialTheme.typography.titleMedium,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        djLiveState.isLoading -> "DJ Sony está armando tu sesión..."
                        !djLiveState.aiError.isNullOrBlank() -> djLiveState.aiError
                        djLiveState.isActive && djLiveState.spokenCaption.isNotBlank() -> djLiveState.spokenCaption
                        !djLiveState.aiNotice.isNullOrBlank() -> djLiveState.aiNotice
                        else -> "Pídele un artista, género o ambiente por voz"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!djLiveState.aiError.isNullOrBlank()) Color(0xFFFF8A80) else SonoraTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onOpenVoiceDj,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("home_dj_voice_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = "Hablar con DJ Sony",
                    tint = accent
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
                    Icon(Icons.Rounded.Stop, contentDescription = "Detener DJ Sony", tint = accent)
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = palette.elevated
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
}

@Composable
private fun YtmShelfBlock(
    shelf: HomeShelf,
    playbackState: PlaybackUiState,
    accent: Color,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onOpenCard: (BrowseCard) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        SectionTitleHeader(
            eyebrow = shelf.subtitle,
            title = shelf.title,
            accent = accent
        )
        Spacer(Modifier.height(8.dp))

        if (shelf.tracks.isNotEmpty()) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Box(modifier = Modifier.padding(vertical = 6.dp)) {
                    if (shelf.tracks.size >= 5 || shelf.isQuickPicks) {
                        YtmMultiColumnTrackPager(
                            tracks = shelf.tracks.take(16),
                            playbackState = playbackState,
                            onPlayTrack = { track -> onPlayTrack(track, shelf.tracks) },
                            onTrackMore = onTrackMore
                        )
                    } else {
                        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                            shelf.tracks.forEachIndexed { idx, track ->
                                TrackRowItem(
                                    track = track,
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    showDivider = idx < shelf.tracks.lastIndex,
                                    onClick = { onPlayTrack(track, shelf.tracks) },
                                    onMoreClick = { onTrackMore(track) }
                                )
                            }
                        }
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

@Composable
private fun SpotifyImportHomeBanner(
    onOpenSpotifyImport: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("home_spotify_import_banner"),
        shape = RoundedCornerShape(18.dp),
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
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary
                )
            }
        }
    }
}
