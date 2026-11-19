package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.local.AccountSessionEntity
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.LocalMusicRepository
import com.example.data.local.PlaylistEntity
import com.example.data.local.SonoraPreferences
import com.example.data.network.GitHubUpdateChecker
import com.example.data.network.IntegrationsManager
import com.example.data.repository.BackupManager
import com.example.data.repository.MusicRepository
import com.example.data.repository.OfflineDownloadManager
import com.example.model.AccentPreset
import com.example.model.AppIconVariant
import com.example.model.AudioQuality
import com.example.model.BrowseCard
import com.example.model.BrowseDetail
import com.example.model.DjVibe
import com.example.model.EqPreset
import com.example.model.GitHubReleaseInfo
import com.example.model.GlassStyle
import com.example.model.HomeShelf
import com.example.model.ListeningStatsSummary
import com.example.model.MoodCategory
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.SonoraSettings
import com.example.model.SpotifyImportState
import com.example.model.Track
import com.example.player.DjLiveController
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class ExploreUiData(
    val shelves: List<HomeShelf>,
    val moods: List<MoodCategory>
)

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val suggestions: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val results: SearchResults? = null,
    val error: String? = null
)

sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data class UpToDate(val currentVersion: String) : UpdateCheckUiState
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckUiState
    data class Error(val message: String) : UpdateCheckUiState
}

class SonoraViewModel(
    private val repository: MusicRepository,
    val playerController: PlayerController,
    private val preferences: SonoraPreferences,
    private val offlineDownloadManager: OfflineDownloadManager,
    private val djLiveController: DjLiveController,
    private val backupManager: BackupManager,
    private val updateChecker: GitHubUpdateChecker,
    private val localMusicRepository: LocalMusicRepository,
    private val integrationsManager: IntegrationsManager
) : ViewModel() {

    val playbackState: StateFlow<PlaybackUiState> = playerController.state
    val sleepTimerRemainingSec: StateFlow<Int?> = playerController.sleepTimerRemainingSec
    val sleepAfterCurrentTrack: StateFlow<Boolean> = playerController.sleepAfterCurrentTrack
    val djLiveState: StateFlow<DjLiveState> = djLiveController.state
    val activeDownloads: StateFlow<Map<String, Float>> = offlineDownloadManager.activeDownloads

    val settings: StateFlow<SonoraSettings> = preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SonoraSettings())

    val localTracks: StateFlow<List<Track>> = localMusicRepository.observeLocalTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val listeningStats: StateFlow<ListeningStatsSummary> = repository.observeListeningStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListeningStatsSummary())

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    private val _homeState = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val homeState: StateFlow<UiState<List<HomeShelf>>> = _homeState.asStateFlow()

    private val _isRefreshingHome = MutableStateFlow(false)
    val isRefreshingHome: StateFlow<Boolean> = _isRefreshingHome.asStateFlow()

    private val _personalizedShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val personalizedShelves: StateFlow<List<HomeShelf>> = _personalizedShelves.asStateFlow()

    private val _suggestedSongSet = MutableStateFlow<List<Track>>(emptyList())
    val suggestedSongSet: StateFlow<List<Track>> = _suggestedSongSet.asStateFlow()

    private val _exploreState = MutableStateFlow<UiState<ExploreUiData>>(UiState.Loading)
    val exploreState: StateFlow<UiState<ExploreUiData>> = _exploreState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _detailState = MutableStateFlow<UiState<BrowseDetail>>(UiState.Loading)
    val detailState: StateFlow<UiState<BrowseDetail>> = _detailState.asStateFlow()

    val accountSession: StateFlow<AccountSessionEntity?> = repository.observeAccountSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _ytPlaylists = MutableStateFlow<List<BrowseCard>>(emptyList())
    val ytPlaylists: StateFlow<List<BrowseCard>> = _ytPlaylists.asStateFlow()

    private val _ytSavedCollections = MutableStateFlow<List<BrowseCard>>(emptyList())
    val ytSavedCollections: StateFlow<List<BrowseCard>> = _ytSavedCollections.asStateFlow()

    private val _ytLikedSongs = MutableStateFlow<List<Track>>(emptyList())
    val ytLikedSongs: StateFlow<List<Track>> = _ytLikedSongs.asStateFlow()

    private val _isSyncingYt = MutableStateFlow(false)
    val isSyncingYt: StateFlow<Boolean> = _isSyncingYt.asStateFlow()

    private val _ytSyncError = MutableStateFlow<String?>(null)
    val ytSyncError: StateFlow<String?> = _ytSyncError.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _spotifyImportState = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val spotifyImportState: StateFlow<SpotifyImportState> = _spotifyImportState.asStateFlow()

    private val _updateCheckState = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    val updateCheckState: StateFlow<UpdateCheckUiState> = _updateCheckState.asStateFlow()

    private val _uiNotice = MutableStateFlow<String?>(null)
    val uiNotice: StateFlow<String?> = _uiNotice.asStateFlow()

    val likedSongs: StateFlow<List<Track>> = repository.observeLikedSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedTracks: StateFlow<List<DownloadedTrackEntity>> = repository.observeDownloadedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTracks: StateFlow<List<Track>> = repository.observeRecentTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostPlayedTracks: StateFlow<List<Track>> = repository.observeMostPlayedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedCollections: StateFlow<List<BrowseCard>> = repository.observeSavedCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchHistory: StateFlow<List<String>> = repository.observeSearchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var importJob: Job? = null
    private var recommendationsJob: Job? = null
    private var suggestionsSetJob: Job? = null
    private var homeRefreshGeneration: Int = 0

    init {
        viewModelScope.launch {
            preferences.settingsFlow.collect { s ->
                repository.audioOnlyPreference = s.audioOnlyPreference
            }
        }

        viewModelScope.launch {
            launch { repository.warmupResolvers() }
            val session = repository.restoreSessionOnStartup()
            if (session != null) {
                performYouTubeSync()
            }
            loadHome()
            loadExplore()
        }

        viewModelScope.launch {
            combine(
                repository.observeRecentTracks(),
                repository.observeMostPlayedTracks(),
                repository.observeLikedSongs(),
                _ytLikedSongs
            ) { recent, mostPlayed, localLiked, ytLiked ->
                Quad(recent, mostPlayed, localLiked, ytLiked)
            }
                .distinctUntilChanged { old, new ->
                    old.first.firstOrNull()?.videoId == new.first.firstOrNull()?.videoId &&
                        old.second.take(3).map { it.videoId } == new.second.take(3).map { it.videoId } &&
                        old.third.firstOrNull()?.videoId == new.third.firstOrNull()?.videoId &&
                        old.fourth.firstOrNull()?.videoId == new.fourth.firstOrNull()?.videoId
                }
                .collect { (recent, mostPlayed, localLiked, ytLiked) ->
                    val combinedLiked = (localLiked + ytLiked).distinctBy { it.videoId }
                    if (recent.isNotEmpty() || mostPlayed.isNotEmpty() || combinedLiked.isNotEmpty()) {
                        refreshPersonalizedRecommendations(recent, mostPlayed, combinedLiked)
                    } else {
                        _personalizedShelves.value = emptyList()
                    }
                }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun refreshPersonalizedRecommendations(
        seedTracks: List<Track>,
        mostPlayedTracks: List<Track> = emptyList(),
        likedTracks: List<Track> = emptyList()
    ) {
        recommendationsJob?.cancel()
        recommendationsJob = viewModelScope.launch {
            val shelves = repository.getPersonalizedRecommendations(
                seedTracks = seedTracks,
                mostPlayedTracks = mostPlayedTracks,
                likedTracks = likedTracks
            )
            if (shelves.isNotEmpty()) {
                _personalizedShelves.value = shelves
                val currentHome = (_homeState.value as? UiState.Success)?.data.orEmpty()
                updateSuggestedSongSet(currentHome, shelves)
            }
        }
    }

    private fun updateSuggestedSongSet(
        homeShelves: List<HomeShelf>,
        personalized: List<HomeShelf> = _personalizedShelves.value
    ) {
        suggestionsSetJob?.cancel()
        suggestionsSetJob = viewModelScope.launch {
            val combinedLiked = (likedSongs.value + _ytLikedSongs.value).distinctBy { it.videoId }
            val suggested = repository.buildSuggestedSongSet(
                homeShelves = homeShelves,
                personalizedShelves = personalized,
                recentTracks = recentTracks.value,
                mostPlayedTracks = mostPlayedTracks.value,
                likedTracks = combinedLiked,
                refreshSeed = homeRefreshGeneration
            )
            if (suggested.isNotEmpty()) {
                _suggestedSongSet.value = suggested
            }
        }
    }

    fun dismissNotice() {
        _uiNotice.value = null
    }

    fun startDjLive(vibe: DjVibe = djLiveState.value.currentVibe) {
        djLiveController.startDjLive(vibe)
    }

    fun startVoiceDjMix(spokenPrompt: String) {
        djLiveController.startVoiceDjMix(spokenPrompt)
    }

    fun switchDjVibe() {
        djLiveController.switchVibe()
    }

    fun stopDjLive() {
        djLiveController.stopDjLive()
    }

    fun downloadTrack(track: Track) {
        val wifiOnly = settings.value.wifiOnlyDownloads
        _uiNotice.value = "Descargando ${track.title}..."
        offlineDownloadManager.launchDownload(track, wifiOnly) { res ->
            res.onSuccess {
                _uiNotice.value = "${track.title} guardada sin conexión"
            }.onFailure { err ->
                _uiNotice.value = err.localizedMessage ?: "No se pudo descargar esta canción."
            }
        }
    }

    fun deleteDownload(videoId: String) {
        viewModelScope.launch {
            offlineDownloadManager.deleteDownload(videoId)
            _uiNotice.value = "Descarga eliminada"
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch {
            offlineDownloadManager.clearAllDownloads()
            _uiNotice.value = "Descargas offline eliminadas"
        }
    }

    fun observeIsDownloaded(videoId: String): Flow<Boolean> = repository.observeIsDownloaded(videoId)

    fun exportLibraryBackup(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            backupManager.exportToUri(uri)
                .onSuccess { count ->
                    onResult("Respaldo exportado ($count canciones guardadas)")
                }
                .onFailure { err ->
                    onResult(err.localizedMessage ?: "Error al exportar el respaldo")
                }
        }
    }

    fun importLibraryBackup(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            backupManager.importFromUri(uri)
                .onSuccess { (likes, pls) ->
                    onResult("Importados $likes me gusta y $pls playlists")
                }
                .onFailure { err ->
                    onResult(err.localizedMessage ?: "El archivo de respaldo no es válido")
                }
        }
    }

    fun checkForUpdates() {
        if (_updateCheckState.value is UpdateCheckUiState.Checking) return
        viewModelScope.launch {
            _updateCheckState.value = UpdateCheckUiState.Checking
            updateChecker.fetchLatestRelease()
                .onSuccess { release ->
                    val newer = GitHubUpdateChecker.isRemoteVersionNewer(
                        remoteTag = release.tagName,
                        currentVersionName = BuildConfig.VERSION_NAME
                    )
                    _updateCheckState.value = if (newer) {
                        UpdateCheckUiState.UpdateAvailable(release)
                    } else {
                        UpdateCheckUiState.UpToDate(BuildConfig.VERSION_NAME)
                    }
                }
                .onFailure { err ->
                    _updateCheckState.value = UpdateCheckUiState.Error(
                        err.localizedMessage ?: "No se pudo comprobar actualizaciones."
                    )
                }
        }
    }

    fun onYouTubeCookiesCaptured(cookies: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isSyncingYt.value = true
            _authError.value = null
            _ytSyncError.value = null
            repository.connectYouTubeAccount(cookies)
                .onSuccess {
                    performYouTubeSync()
                    loadHome()
                    onSuccess()
                }
                .onFailure { err ->
                    _authError.value = err.localizedMessage ?: "No se pudo validar la sesión de Google."
                    _isSyncingYt.value = false
                }
        }
    }

    private suspend fun performYouTubeSync() {
        _isSyncingYt.value = true
        _ytSyncError.value = null
        repository.syncYouTubeLibrary()
            .onSuccess { synced ->
                _ytPlaylists.value = synced.playlists
                _ytSavedCollections.value = synced.savedCollections
                _ytLikedSongs.value = synced.likedSongs
                val seeds = synced.historyTracks.ifEmpty { recentTracks.value }
                val mostPlayed = mostPlayedTracks.value
                val liked = (likedSongs.value + synced.likedSongs).distinctBy { it.videoId }
                if (seeds.isNotEmpty() || mostPlayed.isNotEmpty() || liked.isNotEmpty()) {
                    refreshPersonalizedRecommendations(seeds, mostPlayed, liked)
                }
            }
            .onFailure { err ->
                _ytSyncError.value = err.localizedMessage ?: "No se pudo sincronizar tu cuenta de YouTube Music."
            }
        _isSyncingYt.value = false
    }

    fun syncYouTubeLibrary() {
        viewModelScope.launch {
            performYouTubeSync()
        }
    }

    fun signOutYouTube() {
        viewModelScope.launch {
            repository.disconnectYouTubeAccount()
            _ytPlaylists.value = emptyList()
            _ytSavedCollections.value = emptyList()
            _ytLikedSongs.value = emptyList()
            _ytSyncError.value = null
            loadHome()
        }
    }

    fun importSpotifyUrl(url: String) {
        if (url.isBlank()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            repository.importSpotifyUrl(url).collect { progress ->
                _spotifyImportState.value = progress
            }
        }
    }

    fun importSpotifyText(playlistName: String, rawText: String) {
        if (rawText.isBlank()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            repository.importSpotifyText(playlistName, rawText).collect { progress ->
                _spotifyImportState.value = progress
            }
        }
    }

    fun resetSpotifyImport() {
        importJob?.cancel()
        _spotifyImportState.value = SpotifyImportState.Idle
    }

    fun loadHome() {
        viewModelScope.launch {
            _homeState.value = UiState.Loading
            repository.getHomeShelves()
                .onSuccess { shelves ->
                    _homeState.value = UiState.Success(shelves)
                    updateSuggestedSongSet(shelves, _personalizedShelves.value)
                }
                .onFailure { err ->
                    _homeState.value = UiState.Error(err.localizedMessage ?: "No se pudo conectar con YouTube Music.")
                }
        }
    }

    fun refreshHomeFeed() {
        if (_isRefreshingHome.value) return
        viewModelScope.launch {
            _isRefreshingHome.value = true
            homeRefreshGeneration++
            try {
                if (accountSession.value != null) {
                    performYouTubeSync()
                } else {
                    val combinedLiked = (likedSongs.value + _ytLikedSongs.value).distinctBy { it.videoId }
                    if (recentTracks.value.isNotEmpty() || mostPlayedTracks.value.isNotEmpty() || combinedLiked.isNotEmpty()) {
                        val recs = repository.getPersonalizedRecommendations(
                            seedTracks = recentTracks.value,
                            mostPlayedTracks = mostPlayedTracks.value,
                            likedTracks = combinedLiked
                        )
                        if (recs.isNotEmpty()) {
                            _personalizedShelves.value = recs
                        }
                    }
                }
                repository.getHomeShelves()
                    .onSuccess { shelves ->
                        _homeState.value = UiState.Success(shelves)
                        updateSuggestedSongSet(shelves, _personalizedShelves.value)
                    }
                    .onFailure { err ->
                        if (_homeState.value !is UiState.Success) {
                            _homeState.value = UiState.Error(err.localizedMessage ?: "No se pudo conectar con YouTube Music.")
                        }
                    }
            } finally {
                _isRefreshingHome.value = false
            }
        }
    }

    fun loadExplore() {
        viewModelScope.launch {
            _exploreState.value = UiState.Loading
            repository.getExploreData()
                .onSuccess { (shelves, moods) ->
                    _exploreState.value = UiState.Success(ExploreUiData(shelves, moods))
                }
                .onFailure { err ->
                    _exploreState.value = UiState.Error(err.localizedMessage ?: "Error al cargar Explorar.")
                }
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        _searchState.update { it.copy(query = newQuery) }
        suggestionJob?.cancel()
        if (newQuery.isBlank()) {
            _searchState.update { it.copy(suggestions = emptyList(), results = null, error = null) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(280L)
            val list = repository.getSearchSuggestions(newQuery)
            _searchState.update { it.copy(suggestions = list) }
        }
    }

    fun onSearchFilterChange(filter: SearchFilter) {
        _searchState.update { it.copy(filter = filter) }
        val currentQuery = _searchState.value.query
        if (currentQuery.isNotBlank()) {
            submitSearch(currentQuery, filter)
        }
    }

    fun submitSearch(queryOverride: String? = null, filterOverride: SearchFilter? = null) {
        val q = (queryOverride ?: _searchState.value.query).trim()
        val f = filterOverride ?: _searchState.value.filter
        if (q.isBlank()) return
        searchJob?.cancel()
        _searchState.update { it.copy(query = q, isSearching = true, error = null, suggestions = emptyList()) }
        searchJob = viewModelScope.launch {
            repository.search(q, f)
                .onSuccess { results ->
                    _searchState.update { it.copy(isSearching = false, results = results) }
                }
                .onFailure { err ->
                    _searchState.update {
                        it.copy(isSearching = false, error = err.localizedMessage ?: "No se pudo completar la búsqueda.")
                    }
                }
        }
    }

    fun deleteSearchHistory(query: String) {
        viewModelScope.launch { repository.deleteSearchHistory(query) }
    }

    fun loadDetail(browseId: String, params: String? = null) {
        detailJob?.cancel()
        _detailState.value = UiState.Loading
        detailJob = viewModelScope.launch {
            repository.getBrowseDetail(browseId, params)
                .onSuccess { detail ->
                    _detailState.value = UiState.Success(detail)
                }
                .onFailure { err ->
                    _detailState.value = UiState.Error(err.localizedMessage ?: "No se pudo abrir esta colección.")
                }
        }
    }

    fun observeIsLiked(videoId: String): Flow<Boolean> = repository.observeIsLiked(videoId)

    fun toggleLike(track: Track) {
        viewModelScope.launch { repository.toggleLike(track) }
    }

    fun observeIsCollectionSaved(browseId: String): Flow<Boolean> = repository.observeIsCollectionSaved(browseId)

    fun toggleSaveCollection(card: BrowseCard, currentlySaved: Boolean) {
        viewModelScope.launch { repository.toggleSaveCollection(card, currentlySaved) }
    }

    fun createPlaylist(name: String, description: String = "", initialTrack: Track? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.createPlaylist(name, description)
            if (initialTrack != null) {
                repository.addTrackToPlaylist(id, initialTrack)
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }

    fun addTrackToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { repository.addTrackToPlaylist(playlistId, track) }
    }

    fun removeTrackFromPlaylist(playlistId: Long, videoId: String) {
        viewModelScope.launch { repository.removeTrackFromPlaylist(playlistId, videoId) }
    }

    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?> = repository.observePlaylist(playlistId)

    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> = repository.observePlaylistTracks(playlistId)

    fun observePlaylistTrackCount(playlistId: Long): Flow<Int> = repository.observePlaylistTrackCount(playlistId)

    fun setAudioQuality(quality: AudioQuality) {
        viewModelScope.launch { preferences.setAudioQuality(quality) }
    }

    fun setAutoplayRadio(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoplayRadio(enabled) }
    }

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch { preferences.setSkipSilence(enabled) }
    }

    fun setDolbyAtmosEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setDolbyAtmosEnabled(enabled) }
    }

    fun setSpatialAudioEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setSpatialAudioEnabled(enabled) }
    }

    fun setAutoMixEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoMixEnabled(enabled) }
    }

    fun setAutoMixOverlapSeconds(seconds: Int) {
        viewModelScope.launch { preferences.setAutoMixOverlapSeconds(seconds) }
    }

    fun setAccentPreset(preset: AccentPreset) {
        viewModelScope.launch { preferences.setAccentPreset(preset) }
    }

    fun setCustomAccentHex(colorHex: Long) {
        viewModelScope.launch { preferences.setCustomAccentHex(colorHex) }
    }

    fun setAppIconVariant(variant: AppIconVariant) {
        viewModelScope.launch { preferences.setAppIconVariant(variant) }
    }

    fun setAnimatedArtworkEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setAnimatedArtworkEnabled(enabled) }
    }

    fun setEqEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setEqEnabled(enabled) }
    }

    fun setEqPreset(preset: EqPreset) {
        viewModelScope.launch { preferences.setEqPreset(preset) }
    }

    fun setBassBoostStrength(strength: Int) {
        viewModelScope.launch { preferences.setBassBoostStrength(strength) }
    }

    fun setNormalizeVolume(enabled: Boolean) {
        viewModelScope.launch { preferences.setNormalizeVolume(enabled) }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch { preferences.setWifiOnlyDownloads(enabled) }
    }

    fun adjustLyricsOffsetMs(deltaMs: Long) {
        viewModelScope.launch {
            val next = (settings.value.lyricsOffsetMs + deltaMs).coerceIn(-10_000L, 10_000L)
            preferences.setLyricsOffsetMs(next)
        }
    }

    fun resetLyricsOffsetMs() {
        viewModelScope.launch { preferences.setLyricsOffsetMs(0L) }
    }

    fun setGlassStyle(style: GlassStyle) {
        viewModelScope.launch { preferences.setGlassStyle(style) }
    }

    fun setSleepTimer(minutes: Int?) {
        playerController.setSleepTimer(minutes)
    }

    fun setSleepAfterCurrentTrack(enabled: Boolean) {
        playerController.setSleepAfterCurrentTrack(enabled)
    }

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch { preferences.setPlaybackSpeed(speed) }
    }

    fun setPlaybackPitch(pitch: Float) {
        viewModelScope.launch { preferences.setPlaybackPitch(pitch) }
    }

    fun resetSpeedAndPitch() {
        viewModelScope.launch {
            preferences.setPlaybackSpeed(1.0f)
            preferences.setPlaybackPitch(1.0f)
        }
    }

    fun setAudioOnlyPreference(enabled: Boolean) {
        repository.audioOnlyPreference = enabled
        viewModelScope.launch { preferences.setAudioOnlyPreference(enabled) }
    }

    fun reloadCurrentLyrics() {
        playerController.reloadLyrics()
    }

    fun addLocalFolder(treeUri: Uri) {
        if (_isScanningLocal.value) return
        viewModelScope.launch {
            _isScanningLocal.value = true
            localMusicRepository.addFolderAndScan(treeUri)
                .onSuccess { count ->
                    _uiNotice.value = "Carpeta agregada: $count canciones locales detectadas"
                }
                .onFailure { err ->
                    _uiNotice.value = err.localizedMessage ?: "No se pudo leer la carpeta seleccionada."
                }
            _isScanningLocal.value = false
        }
    }

    fun rescanLocalFolders() {
        if (_isScanningLocal.value) return
        val folders = settings.value.localFolderUris
        if (folders.isEmpty()) return
        viewModelScope.launch {
            _isScanningLocal.value = true
            localMusicRepository.rescanFolders(folders)
                .onSuccess { count ->
                    _uiNotice.value = "Biblioteca local actualizada ($count canciones)"
                }
                .onFailure { err ->
                    _uiNotice.value = err.localizedMessage ?: "Error al escanear carpetas locales."
                }
            _isScanningLocal.value = false
        }
    }

    fun removeLocalFolder(treeUriStr: String) {
        viewModelScope.launch {
            localMusicRepository.removeFolder(treeUriStr)
            _uiNotice.value = "Carpeta local eliminada"
        }
    }

    fun computeLocalArtCacheMb(): Float =
        localMusicRepository.computeArtCacheSizeBytes() / (1024f * 1024f)

    fun clearLocalArtCache() {
        viewModelScope.launch {
            localMusicRepository.clearArtCache()
            _uiNotice.value = "Caché de carátulas locales limpiada"
        }
    }

    fun connectListenBrainz(token: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = integrationsManager.validateListenBrainzToken(token)
            result.onSuccess { username ->
                preferences.saveListenBrainzCredentials(token = token, username = username, enabled = true)
            }
            onResult(result)
        }
    }

    fun setListenBrainzEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setListenBrainzEnabled(enabled) }
    }

    fun disconnectListenBrainz() {
        viewModelScope.launch { preferences.clearListenBrainz() }
    }

    fun connectLastFm(
        username: String,
        password: String,
        apiKey: String,
        apiSecret: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            val res = integrationsManager.authenticateLastFmMobileSession(username, password, apiKey, apiSecret)
            val mapped = res.map { (resolvedName, sessionKey) ->
                preferences.saveLastFmCredentials(
                    apiKey = apiKey,
                    apiSecret = apiSecret,
                    sessionKey = sessionKey,
                    username = resolvedName,
                    enabled = true
                )
                resolvedName
            }
            onResult(mapped)
        }
    }

    fun setLastFmEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setLastFmEnabled(enabled) }
    }

    fun disconnectLastFm() {
        viewModelScope.launch { preferences.clearLastFm() }
    }

    fun connectDiscord(token: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val res = integrationsManager.validateDiscordToken(token)
            res.onSuccess { username ->
                preferences.saveDiscordCredentials(token = token, username = username, enabled = true)
            }
            onResult(res)
        }
    }

    fun setDiscordRpcEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setDiscordRpcEnabled(enabled)
            if (!enabled) integrationsManager.disconnectDiscord()
        }
    }

    fun disconnectDiscord() {
        integrationsManager.disconnectDiscord()
        viewModelScope.launch { preferences.clearDiscord() }
    }

    fun clearStreamCache() {
        repository.clearStreamCache()
    }

    fun clearPlaybackHistory() {
        viewModelScope.launch {
            repository.clearPlaybackHistory()
            _personalizedShelves.value = emptyList()
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { repository.clearSearchHistory() }
    }

    class Factory(
        private val repository: MusicRepository,
        private val playerController: PlayerController,
        private val preferences: SonoraPreferences,
        private val offlineDownloadManager: OfflineDownloadManager,
        private val djLiveController: DjLiveController,
        private val backupManager: BackupManager,
        private val updateChecker: GitHubUpdateChecker,
        private val localMusicRepository: LocalMusicRepository,
        private val integrationsManager: IntegrationsManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SonoraViewModel(
                repository = repository,
                playerController = playerController,
                preferences = preferences,
                offlineDownloadManager = offlineDownloadManager,
                djLiveController = djLiveController,
                backupManager = backupManager,
                updateChecker = updateChecker,
                localMusicRepository = localMusicRepository,
                integrationsManager = integrationsManager
            ) as T
        }
    }
}
