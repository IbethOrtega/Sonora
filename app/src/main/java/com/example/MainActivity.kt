package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.model.BrowseCard
import com.example.model.Track
import com.example.ui.components.AddToPlaylistSheet
import com.example.ui.components.AmbientBackdrop
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.TrackOptionsSheet
import com.example.ui.navigation.DetailRoute
import com.example.ui.navigation.ExploreRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.LibraryRoute
import com.example.ui.navigation.LocalPlaylistRoute
import com.example.ui.navigation.SearchRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.LocalPlaylistDetailScreen
import com.example.ui.screens.NowPlayingSheet
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SpotifyImportSheet
import com.example.ui.screens.YouTubeSignInScreen
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraCrimson
import com.example.ui.theme.SonoraGlassBorderBottom
import com.example.ui.theme.SonoraGlassBorderTop
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTheme
import com.example.ui.viewmodel.SonoraViewModel

class MainActivity : ComponentActivity() {

    private val app by lazy { application as SonoraApplication }

    override fun onStart() {
        super.onStart()
        app.playerController.connect()
    }

    override fun onStop() {
        super.onStop()
        app.playerController.release()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isWideScreen = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

            val viewModel: SonoraViewModel = viewModel(
                factory = SonoraViewModel.Factory(
                    repository = app.repository,
                    playerController = app.playerController,
                    preferences = app.preferences
                )
            )
            val settings by viewModel.settings.collectAsState()

            SonoraTheme(
                accentPreset = settings.accentPreset,
                glassStyle = settings.glassStyle
            ) {
                SonoraAppShell(
                    viewModel = viewModel,
                    isWideScreen = isWideScreen
                )
            }
        }
    }
}

private data class TopLevelTab(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
)

@Composable
private fun SonoraAppShell(
    viewModel: SonoraViewModel,
    isWideScreen: Boolean
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    val playbackState by viewModel.playbackState.collectAsState()
    val homeState by viewModel.homeState.collectAsState()
    val personalizedShelves by viewModel.personalizedShelves.collectAsState()
    val ytPlaylists by viewModel.ytPlaylists.collectAsState()
    val exploreState by viewModel.exploreState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val mostPlayedTracks by viewModel.mostPlayedTracks.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val accountSession by viewModel.accountSession.collectAsState()
    val isSyncingYt by viewModel.isSyncingYt.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val spotifyImportState by viewModel.spotifyImportState.collectAsState()

    var isNowPlayingExpanded by remember { mutableStateOf(false) }
    var showYouTubeSignIn by remember { mutableStateOf(false) }
    var showSpotifyImport by remember { mutableStateOf(false) }
    var optionsTrack by remember { mutableStateOf<Track?>(null) }
    var playlistTargetTrack by remember { mutableStateOf<Track?>(null) }
    var askedNotificationPermission by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* playback works regardless of notification permission */ }
    )

    fun ensureNotificationPermission() {
        if (!askedNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askedNotificationPermission = true
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val playTrackAction: (Track, List<Track>) -> Unit = { track, queue ->
        ensureNotificationPermission()
        viewModel.playerController.playTrack(track, queue)
    }

    val playQueueAction: (List<Track>, Int) -> Unit = { queue, index ->
        ensureNotificationPermission()
        viewModel.playerController.playQueue(queue, index)
    }

    val openCardAction: (BrowseCard) -> Unit = { card ->
        if (card.videoId != null) {
            playTrackAction(
                Track(
                    videoId = card.videoId,
                    title = card.title,
                    artist = card.subtitle,
                    artworkUrl = card.artworkUrl
                ),
                emptyList()
            )
        } else {
            navController.navigate(
                DetailRoute(
                    browseId = card.id,
                    title = card.title,
                    subtitle = card.subtitle,
                    artworkUrl = card.artworkUrl,
                    params = card.params
                )
            )
        }
    }

    val tabs = remember {
        listOf(
            TopLevelTab(HomeRoute, "Inicio", Icons.Rounded.Home, Icons.Outlined.Home, "tab_home"),
            TopLevelTab(ExploreRoute, "Explorar", Icons.Rounded.Explore, Icons.Outlined.Explore, "tab_explore"),
            TopLevelTab(SearchRoute, "Buscar", Icons.Rounded.Search, Icons.Outlined.Search, "tab_search"),
            TopLevelTab(LibraryRoute, "Biblioteca", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic, "tab_library")
        )
    }

    fun navigateToTab(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun isTabSelected(route: Any): Boolean {
        val dest = backStackEntry?.destination ?: return false
        return when (route) {
            is HomeRoute -> dest.hasRoute<HomeRoute>()
            is ExploreRoute -> dest.hasRoute<ExploreRoute>()
            is SearchRoute -> dest.hasRoute<SearchRoute>()
            is LibraryRoute -> dest.hasRoute<LibraryRoute>()
            else -> false
        }
    }

    val bottomPadding = when {
        isWideScreen && playbackState.currentTrack != null -> 108.dp
        isWideScreen -> 24.dp
        playbackState.currentTrack != null -> 182.dp
        else -> 112.dp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackdrop(
            primaryColor = playbackState.ambientPrimary,
            secondaryColor = playbackState.ambientSecondary
        )

        Row(modifier = Modifier.fillMaxSize()) {
            if (isWideScreen) {
                NavigationRail(
                    containerColor = Color(0xCC12121A),
                    modifier = Modifier
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Spacer(Modifier.height(16.dp))
                    tabs.forEach { tab ->
                        val selected = isTabSelected(tab.route)
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateToTab(tab.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = SonoraCrimson,
                                selectedTextColor = SonoraCrimson,
                                unselectedIconColor = SonoraTextSecondary,
                                unselectedTextColor = SonoraTextSecondary,
                                indicatorColor = SonoraCrimson.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier.testTag(tab.tag)
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                NavHost(
                    navController = navController,
                    startDestination = HomeRoute
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            homeState = homeState,
                            personalizedShelves = personalizedShelves,
                            ytPlaylists = ytPlaylists,
                            recentTracks = recentTracks,
                            mostPlayedTracks = mostPlayedTracks,
                            accountSession = accountSession,
                            playbackState = playbackState,
                            bottomContentPadding = bottomPadding,
                            onRetry = { viewModel.loadHome() },
                            onOpenAuth = {
                                if (accountSession == null) {
                                    showYouTubeSignIn = true
                                } else {
                                    navigateToTab(LibraryRoute)
                                }
                            },
                            onOpenSettings = {
                                navController.navigate(SettingsRoute) { launchSingleTop = true }
                            },
                            onOpenSpotifyImport = { showSpotifyImport = true },
                            onPlayTrack = playTrackAction,
                            onOpenCard = openCardAction,
                            onTrackMore = { optionsTrack = it }
                        )
                    }

                    composable<ExploreRoute> {
                        ExploreScreen(
                            exploreState = exploreState,
                            playbackState = playbackState,
                            bottomContentPadding = bottomPadding,
                            onRetry = { viewModel.loadExplore() },
                            onPlayTrack = playTrackAction,
                            onOpenCard = openCardAction,
                            onOpenMood = { mood ->
                                navController.navigate(
                                    DetailRoute(
                                        browseId = mood.browseId,
                                        title = mood.title,
                                        subtitle = "Géneros y momentos",
                                        params = mood.params
                                    )
                                )
                            },
                            onTrackMore = { optionsTrack = it }
                        )
                    }

                    composable<SearchRoute> {
                        SearchScreen(
                            searchState = searchState,
                            searchHistory = searchHistory,
                            playbackState = playbackState,
                            bottomContentPadding = bottomPadding,
                            onQueryChange = { viewModel.onSearchQueryChange(it) },
                            onFilterChange = { viewModel.onSearchFilterChange(it) },
                            onSubmitSearch = { viewModel.submitSearch(it) },
                            onDeleteHistory = { viewModel.deleteSearchHistory(it) },
                            onPlayTrack = playTrackAction,
                            onOpenCard = openCardAction,
                            onTrackMore = { optionsTrack = it }
                        )
                    }

                    composable<LibraryRoute> {
                        LibraryScreen(
                            viewModel = viewModel,
                            playbackState = playbackState,
                            bottomContentPadding = bottomPadding,
                            onOpenAuth = { showYouTubeSignIn = true },
                            onSignOut = {
                                runCatching {
                                    CookieManager.getInstance().removeAllCookies(null)
                                    CookieManager.getInstance().flush()
                                }
                                viewModel.signOutYouTube()
                            },
                            onOpenSettings = {
                                navController.navigate(SettingsRoute) { launchSingleTop = true }
                            },
                            onOpenSpotifyImport = { showSpotifyImport = true },
                            onOpenPlaylist = { playlistId ->
                                navController.navigate(LocalPlaylistRoute(playlistId))
                            },
                            onOpenCard = openCardAction,
                            onPlayTrack = playTrackAction,
                            onTrackMore = { optionsTrack = it }
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            viewModel = viewModel,
                            bottomContentPadding = bottomPadding,
                            onBack = { navController.popBackStack() },
                            onOpenAuth = { showYouTubeSignIn = true },
                            onSignOut = {
                                runCatching {
                                    CookieManager.getInstance().removeAllCookies(null)
                                    CookieManager.getInstance().flush()
                                }
                                viewModel.signOutYouTube()
                            }
                        )
                    }

                    composable<DetailRoute> { entry ->
                        val route = entry.toRoute<DetailRoute>()
                        DetailScreen(
                            browseId = route.browseId,
                            initialTitle = route.title,
                            initialSubtitle = route.subtitle,
                            initialArtworkUrl = route.artworkUrl,
                            params = route.params,
                            viewModel = viewModel,
                            playbackState = playbackState,
                            bottomContentPadding = bottomPadding,
                            onBack = { navController.popBackStack() },
                            onPlayQueue = playQueueAction,
                            onOpenCard = openCardAction,
                            onTrackMore = { optionsTrack = it }
                        )
                    }

                    composable<LocalPlaylistRoute> { entry ->
                        val route = entry.toRoute<LocalPlaylistRoute>()
                        LocalPlaylistDetailScreen(
                            playlistId = route.playlistId,
                            viewModel = viewModel,
                            playbackState = playbackState,
                            bottomContentPadding = bottomPadding,
                            onBack = { navController.popBackStack() },
                            onPlayQueue = playQueueAction,
                            onTrackMore = { optionsTrack = it }
                        )
                    }
                }

                // Floating error toast if playback fails while minimized
                val err = playbackState.errorMessage
                if (err != null && !isNowPlayingExpanded) {
                    LaunchedEffect(err) {
                        kotlinx.coroutines.delay(5000L)
                        viewModel.playerController.dismissError()
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFA1C1218),
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, SonoraCrimson.copy(alpha = 0.75f)),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.playerController.retryCurrent() }) {
                                Text("Reintentar", color = SonoraCrimson)
                            }
                        }
                    }
                }

                // Floating Translucent MiniPlayer + Glass Bottom Tab Bar
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AnimatedVisibility(
                        visible = playbackState.currentTrack != null,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            onExpand = { isNowPlayingExpanded = true },
                            onTogglePlayPause = { viewModel.playerController.togglePlayPause() },
                            onSkipNext = { viewModel.playerController.skipNext() }
                        )
                    }

                    if (!isWideScreen) {
                        GlassBottomTabBar(
                            tabs = tabs,
                            isSelected = ::isTabSelected,
                            onSelect = ::navigateToTab
                        )
                    }
                }
            }
        }

        // Fullscreen Apple Music Now Playing Overlay
        AnimatedVisibility(
            visible = isNowPlayingExpanded && playbackState.currentTrack != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            val activeTrack = playbackState.currentTrack
            val isLiked by if (activeTrack != null) {
                viewModel.observeIsLiked(activeTrack.videoId).collectAsState(initial = false)
            } else {
                remember { mutableStateOf(false) }
            }

            NowPlayingSheet(
                playbackState = playbackState,
                isLiked = isLiked,
                onCollapse = { isNowPlayingExpanded = false },
                onTogglePlayPause = { viewModel.playerController.togglePlayPause() },
                onSkipNext = { viewModel.playerController.skipNext() },
                onSkipPrevious = { viewModel.playerController.skipPrevious() },
                onSeekTo = { viewModel.playerController.seekTo(it) },
                onToggleShuffle = { viewModel.playerController.toggleShuffle() },
                onCycleRepeat = { viewModel.playerController.cycleRepeatMode() },
                onToggleLike = { viewModel.toggleLike(it) },
                onAddToPlaylist = { playlistTargetTrack = it },
                onSkipToQueueIndex = { viewModel.playerController.skipToQueueIndex(it) },
                onRetry = { viewModel.playerController.retryCurrent() },
                onOpenOptions = { optionsTrack = it }
            )
        }

        // YouTube Music Google Account Sign-In Overlay
        AnimatedVisibility(
            visible = showYouTubeSignIn,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            YouTubeSignInScreen(
                isSyncing = isSyncingYt,
                authError = authError,
                onCookiesCaptured = { cookies ->
                    viewModel.onYouTubeCookiesCaptured(cookies) {
                        showYouTubeSignIn = false
                    }
                },
                onClose = { showYouTubeSignIn = false }
            )
        }
    }

    // Spotify Playlist Importer Modal Sheet
    if (showSpotifyImport) {
        SpotifyImportSheet(
            importState = spotifyImportState,
            onDismiss = { showSpotifyImport = false },
            onImportUrl = { url -> viewModel.importSpotifyUrl(url) },
            onImportText = { title, text -> viewModel.importSpotifyText(title, text) },
            onOpenPlaylist = { playlistId ->
                navController.navigate(LocalPlaylistRoute(playlistId))
            },
            onReset = { viewModel.resetSpotifyImport() }
        )
    }

    // Track Options Modal Sheet
    optionsTrack?.let { selectedTrack ->
        val isLiked by viewModel.observeIsLiked(selectedTrack.videoId).collectAsState(initial = false)
        TrackOptionsSheet(
            track = selectedTrack,
            isLiked = isLiked,
            onDismiss = { optionsTrack = null },
            onToggleLike = { viewModel.toggleLike(selectedTrack) },
            onAddToQueue = { viewModel.playerController.addToQueue(selectedTrack) },
            onAddToPlaylist = { playlistTargetTrack = selectedTrack },
            onOpenAlbum = { albumId, albumTitle ->
                isNowPlayingExpanded = false
                navController.navigate(
                    DetailRoute(
                        browseId = albumId,
                        title = albumTitle
                    )
                )
            }
        )
    }

    // Add To Playlist Modal Sheet
    playlistTargetTrack?.let { target ->
        AddToPlaylistSheet(
            track = target,
            playlists = playlists,
            onDismiss = { playlistTargetTrack = null },
            onSelectPlaylist = { playlistId ->
                viewModel.addTrackToPlaylist(playlistId, target)
            },
            onCreatePlaylistWithTrack = { name ->
                viewModel.createPlaylist(name = name, initialTrack = target)
            }
        )
    }
}

@Composable
private fun GlassBottomTabBar(
    tabs: List<TopLevelTab>,
    isSelected: (Any) -> Boolean,
    onSelect: (Any) -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    val glassBg = Brush.verticalGradient(
        colors = listOf(
            Color(0xD91B1B26),
            Color(0xEB101017)
        )
    )
    val borderBrush = Brush.linearGradient(
        colors = listOf(SonoraGlassBorderTop, SonoraGlassBorderBottom)
    )

    val accent = LocalSonoraAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(glassBg)
            .border(BorderStroke(0.8.dp, borderBrush), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val selected = isSelected(tab.route)
            val pillBg = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent
            val contentColor = if (selected) accent else SonoraTextSecondary

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(pillBg)
                    .clickable { onSelect(tab.route) }
                    .padding(vertical = 7.dp)
                    .testTag(tab.tag),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                    contentDescription = tab.label,
                    tint = contentColor,
                    modifier = Modifier.size(23.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1
                )
            }
        }
    }
}
