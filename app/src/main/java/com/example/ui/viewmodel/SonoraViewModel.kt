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
import com.example.data.repository.HomeFeedCurator
import com.example.data.repository.MusicRepository
import com.example.data.repository.OfflineDownloadManager
import com.example.data.repository.QueueBuilder
import com.example.model.AccentPreset
import com.example.model.AppIconVariant
import com.example.model.ArtistCredit
import com.example.model.AudioQuality
import com.example.model.BrowseCard
import com.example.model.BrowseDetail
import com.example.model.CustomMixFragment
import com.example.model.DjVibe
import com.example.model.EqPreset
import com.example.model.GitHubReleaseInfo
import com.example.model.GlassStyle
import com.example.model.HomeFilterChip
import com.example.model.HomeShelf
import com.example.model.HistoryDayGroup
import com.example.model.ListeningStatsSummary
import com.example.model.MoodCategory
import com.example.model.PlayerBackdropStyle
import com.example.model.PlaylistTransitionSpec
import com.example.model.QueueMoodFilter
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.SonoraSettings
import com.example.model.SpotifyImportState
import com.example.model.StatsPeriod
import com.example.model.Track
import com.example.model.TransitionCurveType
import com.example.model.YouTubeAuthSessionPayload
import com.example.model.splitArtistNames
import com.example.player.DjLiveController
import com.example.player.DjLiveState
import com.example.player.PlaybackUiState
import com.example.player.PlayerController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

data class MixerSearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Track> = emptyList(),
    val error: String? = null
)

sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data class UpToDate(val currentVersion: String) : UpdateCheckUiState
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckUiState
    data class Error(val message: String) : UpdateCheckUiState
}

data class TrackOptionsMetadataState(
    val videoId: String = "",
    val isResolving: Boolean = false,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artists: List<ArtistCredit> = emptyList()
)

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
    val positionMsFlow: StateFlow<Long> = playerController.positionMsFlow
    val sleepTimerRemainingSec: StateFlow<Int?> = playerController.sleepTimerRemainingSec
    val sleepAfterCurrentTrack: StateFlow<Boolean> = playerController.sleepAfterCurrentTrack
    val djLiveState: StateFlow<DjLiveState> = djLiveController.state
    val availableDeviceSpanishVoices: StateFlow<List<com.example.player.DeviceTtsVoiceInfo>> =
        djLiveController.availableDeviceSpanishVoices
    val activeDownloads: StateFlow<Map<String, Float>> = offlineDownloadManager.activeDownloads

    val settings: StateFlow<SonoraSettings> = preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SonoraSettings())

    val localTracks: StateFlow<List<Track>> = localMusicRepository.observeLocalTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val listeningStats: StateFlow<ListeningStatsSummary> = repository.observeListeningStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListeningStatsSummary())

    val listeningHistory: StateFlow<List<HistoryDayGroup>> = repository.observeListeningHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasMoreYouTubeHistory: StateFlow<Boolean> = repository.hasMoreYouTubeHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isLoadingMoreHistory: StateFlow<Boolean> = repository.isLoadingMoreHistory

    private val _statsPeriod = MutableStateFlow(StatsPeriod.ALL)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val replayStats: StateFlow<ListeningStatsSummary> = _statsPeriod
        .flatMapLatest { period -> repository.observeReplayStats(period) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListeningStatsSummary())

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    private val _homeState = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val homeState: StateFlow<UiState<List<HomeShelf>>> = _homeState.asStateFlow()

    private val _homeChips = MutableStateFlow<List<HomeFilterChip>>(emptyList())
    val homeChips: StateFlow<List<HomeFilterChip>> = _homeChips.asStateFlow()

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

    private val _mixerSearchState = MutableStateFlow(MixerSearchState())
    val mixerSearchState: StateFlow<MixerSearchState> = _mixerSearchState.asStateFlow()

    private val _detailState = MutableStateFlow<UiState<BrowseDetail>>(UiState.Loading)
    val detailState: StateFlow<UiState<BrowseDetail>> = _detailState.asStateFlow()

    val accountSession: StateFlow<AccountSessionEntity?> = repository.observeAccountSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _ytPlaylists = MutableStateFlow<List<BrowseCard>>(emptyList())
    val ytPlaylists: StateFlow<List<BrowseCard>> = _ytPlaylists.asStateFlow()

    private val _ytSavedCollections = MutableStateFlow<List<BrowseCard>>(emptyList())
    val ytSavedCollections: StateFlow<List<BrowseCard>> = _ytSavedCollections.asStateFlow()

    val ytLikedSongs: StateFlow<List<Track>> = repository.observeYtLikedSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ytLibraryArtists = MutableStateFlow<List<BrowseCard>>(emptyList())
    val ytLibraryArtists: StateFlow<List<BrowseCard>> = _ytLibraryArtists.asStateFlow()

    private val _ytUploadedSongs = MutableStateFlow<List<Track>>(emptyList())
    val ytUploadedSongs: StateFlow<List<Track>> = _ytUploadedSongs.asStateFlow()

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

    private val _trackOptionsMetadata = MutableStateFlow(TrackOptionsMetadataState())
    val trackOptionsMetadata: StateFlow<TrackOptionsMetadataState> = _trackOptionsMetadata.asStateFlow()

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

    val allLikedSongs: StateFlow<List<Track>> = combine(ytLikedSongs, likedSongs) { yt, local ->
        (yt + local).distinctBy { it.videoId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val likedCount: StateFlow<Int> = combine(likedSongs, ytLikedSongs) { local, yt ->
        (local + yt).distinctBy { it.videoId }.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null
    private var mixerSearchJob: Job? = null
    private var detailJob: Job? = null
    private var importJob: Job? = null
    private var recommendationsJob: Job? = null
    private var suggestionsSetJob: Job? = null
    private var homeChipJob: Job? = null
    private var trackOptionsMetadataJob: Job? = null
    private var homeRefreshGeneration: Int = 0
    private var lastHomeLoadedAtMs: Long = 0L
    private var rawHomeShelves: List<HomeShelf> = emptyList()
    private var rawPersonalizedShelves: List<HomeShelf> = emptyList()
    private var selectedHomeChip: HomeFilterChip? = null

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
                ytLikedSongs
            ) { recent, mostPlayed, localLiked, ytLiked ->
                Quad(recent, mostPlayed, localLiked, ytLiked)
            }
                .distinctUntilChanged { old, new ->
                    old.first.firstOrNull()?.videoId == new.first.firstOrNull()?.videoId &&
                        old.second.take(3).map { it.videoId } == new.second.take(3).map { it.videoId } &&
                        old.third.firstOrNull()?.videoId == new.third.firstOrNull()?.videoId &&
                        old.fourth.size == new.fourth.size &&
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
                likedTracks = likedTracks,
                homeShelves = rawHomeShelves,
                refreshSeed = homeRefreshGeneration
            )
            if (shelves.isNotEmpty()) {
                rawPersonalizedShelves = shelves
                applyHomeCrossSectionDeduplication(rawHomeShelves, shelves)
            }
        }
    }

    private fun updateSuggestedSongSet(
        homeShelves: List<HomeShelf>,
        personalized: List<HomeShelf> = rawPersonalizedShelves
    ) {
        suggestionsSetJob?.cancel()
        suggestionsSetJob = viewModelScope.launch {
            applyHomeCrossSectionDeduplication(homeShelves, personalized)
        }
    }

    private suspend fun applyHomeCrossSectionDeduplication(
        homeShelves: List<HomeShelf>,
        personalized: List<HomeShelf>
    ) {
        val combinedLiked = (likedSongs.value + ytLikedSongs.value).distinctBy { it.videoId }
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
        val effectiveSuggested = if (suggested.isNotEmpty()) suggested else _suggestedSongSet.value
        val (excluded24hIds, excluded24hKeys) = repository.getExcludedLast24hForHome()
        val claimedIds = effectiveSuggested.mapNotNull { it.videoId.takeIf(String::isNotBlank) }.toMutableSet()
        val claimedKeys = effectiveSuggested
            .mapNotNull { QueueBuilder.normalizeDedupeKey(it.title, it.artist).takeIf(String::isNotBlank) }
            .toMutableSet()

        val dedupedPersonalized = HomeFeedCurator.deduplicateHomeShelves(
            shelves = personalized,
            excluded24hIds = excluded24hIds,
            excluded24hDedupeKeys = excluded24hKeys,
            preClaimedIds = claimedIds,
            preClaimedDedupeKeys = claimedKeys
        )
        dedupedPersonalized.flatMap { it.tracks }.forEach { t ->
            if (t.videoId.isNotBlank()) claimedIds.add(t.videoId)
            QueueBuilder.normalizeDedupeKey(t.title, t.artist).takeIf { it.isNotBlank() }?.let { claimedKeys.add(it) }
        }
        _personalizedShelves.value = dedupedPersonalized

        if (homeShelves.isNotEmpty()) {
            val dedupedHome = HomeFeedCurator.deduplicateHomeShelves(
                shelves = homeShelves,
                excluded24hIds = excluded24hIds,
                excluded24hDedupeKeys = excluded24hKeys,
                preClaimedIds = claimedIds,
                preClaimedDedupeKeys = claimedKeys
            )
            _homeState.value = UiState.Success(dedupedHome)
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

    fun setDjVoiceName(voiceName: String) {
        viewModelScope.launch { preferences.setDjVoiceName(voiceName) }
    }

    fun previewDjVoice(voiceName: String) {
        djLiveController.previewVoice(voiceName)
    }

    fun setDjDeviceVoiceName(voiceName: String) {
        viewModelScope.launch { preferences.setDjDeviceVoiceName(voiceName) }
    }

    fun setDjDeviceSpeechRate(rate: Float) {
        viewModelScope.launch { preferences.setDjDeviceSpeechRate(rate) }
    }

    fun setDjDevicePitch(pitch: Float) {
        viewModelScope.launch { preferences.setDjDevicePitch(pitch) }
    }

    fun previewDeviceDjVoice() {
        djLiveController.previewDeviceVoice()
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
        onYouTubeSessionCaptured(YouTubeAuthSessionPayload(cookies = cookies), onSuccess)
    }

    fun onYouTubeSessionCaptured(payload: YouTubeAuthSessionPayload, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isSyncingYt.value = true
            _authError.value = null
            _ytSyncError.value = null
            repository.connectYouTubeAccount(payload)
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
                _ytLibraryArtists.value = synced.artists
                _ytUploadedSongs.value = synced.uploadedSongs
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
            _ytLibraryArtists.value = emptyList()
            _ytUploadedSongs.value = emptyList()
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

    fun onAppForegrounded() {
        val now = System.currentTimeMillis()
        if (lastHomeLoadedAtMs > 0L && (now - lastHomeLoadedAtMs >= 30L * 60_000L || repository.isHomeFeedCacheStale(now))) {
            refreshHomeFeed(silent = true)
        }
    }

    fun loadHome() {
        homeChipJob?.cancel()
        homeChipJob = viewModelScope.launch {
            if (rawHomeShelves.isEmpty()) {
                _homeState.value = UiState.Loading
            }
            repository.getHomeFeed(selectedHomeChip, forceRefresh = false)
                .onSuccess { (shelves, chips) ->
                    lastHomeLoadedAtMs = System.currentTimeMillis()
                    rawHomeShelves = shelves
                    _homeChips.value = chips
                    applyHomeCrossSectionDeduplication(shelves, rawPersonalizedShelves)
                }
                .onFailure { err ->
                    if (rawHomeShelves.isEmpty()) {
                        _homeState.value = UiState.Error(err.localizedMessage ?: "No se pudo conectar con YouTube Music.")
                    }
                }
        }
    }

    fun selectHomeChip(chip: HomeFilterChip) {
        val isReset = chip.params.isNullOrBlank() && chip.fallbackSearchQuery.isNullOrBlank()
        selectedHomeChip = if (isReset) null else chip.copy(isSelected = true)
        val optimistic = _homeChips.value.map { item ->
            val match = if (isReset) {
                item.params.isNullOrBlank() && item.fallbackSearchQuery.isNullOrBlank()
            } else {
                item.title.equals(chip.title, ignoreCase = true)
            }
            item.copy(isSelected = match)
        }
        if (optimistic.isNotEmpty()) {
            _homeChips.value = optimistic
        }
        homeChipJob?.cancel()
        homeChipJob = viewModelScope.launch {
            _isRefreshingHome.value = true
            try {
                repository.getHomeFeed(selectedHomeChip, forceRefresh = false)
                    .onSuccess { (shelves, chips) ->
                        lastHomeLoadedAtMs = System.currentTimeMillis()
                        rawHomeShelves = shelves
                        if (chips.isNotEmpty()) {
                            _homeChips.value = chips
                        }
                        applyHomeCrossSectionDeduplication(shelves, rawPersonalizedShelves)
                    }
                    .onFailure { err ->
                        if (_homeState.value !is UiState.Success) {
                            _homeState.value = UiState.Error(err.localizedMessage ?: "No se pudo cargar este ambiente.")
                        }
                    }
            } finally {
                _isRefreshingHome.value = false
            }
        }
    }

    fun refreshHomeFeed(silent: Boolean = false) {
        if (_isRefreshingHome.value) return
        viewModelScope.launch {
            if (!silent) {
                _isRefreshingHome.value = true
                homeRefreshGeneration++
            }
            try {
                if (accountSession.value != null) {
                    performYouTubeSync()
                }
                val combinedLiked = (likedSongs.value + ytLikedSongs.value).distinctBy { it.videoId }
                repository.getHomeFeed(selectedHomeChip, forceRefresh = true)
                    .onSuccess { (shelves, chips) ->
                        lastHomeLoadedAtMs = System.currentTimeMillis()
                        rawHomeShelves = shelves
                        if (chips.isNotEmpty()) {
                            _homeChips.value = chips
                        }
                        val recs = if (recentTracks.value.isNotEmpty() || mostPlayedTracks.value.isNotEmpty() || combinedLiked.isNotEmpty()) {
                            repository.getPersonalizedRecommendations(
                                seedTracks = recentTracks.value,
                                mostPlayedTracks = mostPlayedTracks.value,
                                likedTracks = combinedLiked,
                                homeShelves = shelves,
                                refreshSeed = homeRefreshGeneration
                            )
                        } else {
                            emptyList()
                        }
                        if (recs.isNotEmpty()) {
                            rawPersonalizedShelves = recs
                        }
                        applyHomeCrossSectionDeduplication(shelves, rawPersonalizedShelves)
                    }
                    .onFailure { err ->
                        if (_homeState.value !is UiState.Success) {
                            _homeState.value = UiState.Error(err.localizedMessage ?: "No se pudo conectar con YouTube Music.")
                        }
                    }
            } finally {
                if (!silent) {
                    _isRefreshingHome.value = false
                }
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

    fun onMixerSearchQueryChange(newQuery: String) {
        val trimmed = newQuery.trim()
        _mixerSearchState.update { it.copy(query = newQuery, error = null) }
        mixerSearchJob?.cancel()
        if (trimmed.length < 2) {
            _mixerSearchState.update { it.copy(isSearching = false, results = emptyList(), error = null) }
            return
        }
        mixerSearchJob = viewModelScope.launch {
            delay(360L)
            executeMixerSearch(trimmed)
        }
    }

    fun submitMixerSearch(queryOverride: String? = null) {
        val q = (queryOverride ?: _mixerSearchState.value.query).trim()
        if (q.isBlank()) return
        mixerSearchJob?.cancel()
        _mixerSearchState.update { it.copy(query = q, isSearching = true, error = null) }
        mixerSearchJob = viewModelScope.launch {
            executeMixerSearch(q)
        }
    }

    private suspend fun executeMixerSearch(query: String) {
        _mixerSearchState.update { it.copy(isSearching = true, error = null) }
        val songResults = repository.search(query, SearchFilter.SONGS).getOrNull()?.songs.orEmpty()
        val combined = if (songResults.isNotEmpty()) {
            songResults
        } else {
            val allFallback = repository.search(query, SearchFilter.ALL)
            allFallback.onFailure { err ->
                _mixerSearchState.update {
                    it.copy(
                        isSearching = false,
                        results = emptyList(),
                        error = err.localizedMessage ?: "No se pudieron buscar canciones."
                    )
                }
                return
            }
            val data = allFallback.getOrNull()
            data?.songs.orEmpty().distinctBy { it.videoId }
        }
        _mixerSearchState.update {
            it.copy(
                isSearching = false,
                results = combined,
                error = if (combined.isEmpty()) "No encontramos canciones para \"$query\"" else null
            )
        }
    }

    fun clearMixerSearch() {
        mixerSearchJob?.cancel()
        _mixerSearchState.value = MixerSearchState()
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

    fun resolveMetadataForTrackOptions(track: Track) {
        trackOptionsMetadataJob?.cancel()
        if (track.isLocal || track.videoId.isBlank() || track.videoId.startsWith("local_")) {
            _trackOptionsMetadata.value = TrackOptionsMetadataState(
                videoId = track.videoId,
                isResolving = false
            )
            return
        }
        val splitNames = splitArtistNames(track.artist)
        val initialCredits = splitNames.mapIndexed { idx, name ->
            ArtistCredit(name = name, browseId = if (idx == 0) track.artistId else null)
        }
        val needsLookup = track.artistId.isNullOrBlank() ||
            track.albumId.isNullOrBlank() ||
            splitNames.size > 1

        _trackOptionsMetadata.value = TrackOptionsMetadataState(
            videoId = track.videoId,
            isResolving = needsLookup,
            artistId = track.artistId,
            album = track.album,
            albumId = track.albumId,
            artists = initialCredits
        )
        if (!needsLookup) return

        trackOptionsMetadataJob = viewModelScope.launch {
            val resolved = runCatching { repository.resolveTrackMetadata(track) }.getOrNull()
            if (_trackOptionsMetadata.value.videoId != track.videoId) return@launch
            if (resolved != null) {
                _trackOptionsMetadata.value = TrackOptionsMetadataState(
                    videoId = track.videoId,
                    isResolving = false,
                    artistId = resolved.artistId,
                    album = resolved.album,
                    albumId = resolved.albumId,
                    artists = resolved.artists.ifEmpty { initialCredits }
                )
                playerController.updateTrackMetadata(resolved)
            } else {
                _trackOptionsMetadata.update { it.copy(isResolving = false) }
            }
        }
    }

    fun resolveArtistForNavigation(
        artistName: String,
        knownBrowseId: String?,
        onResolved: (browseId: String, title: String) -> Unit
    ) {
        val cleanId = knownBrowseId?.takeIf { it.isNotBlank() }
        if (cleanId != null) {
            onResolved(cleanId, artistName)
            return
        }
        viewModelScope.launch {
            val found = repository.resolveArtistBrowseId(artistName)
            if (!found.isNullOrBlank()) {
                onResolved(found, artistName)
            } else {
                _uiNotice.value = "No se encontró el perfil de $artistName"
            }
        }
    }

    fun resolveAlbumForNavigation(
        track: Track,
        onResolved: (browseId: String, title: String) -> Unit
    ) {
        val knownAlbumId = track.albumId?.takeIf { it.isNotBlank() }
        if (knownAlbumId != null) {
            onResolved(knownAlbumId, track.album?.takeIf { it.isNotBlank() } ?: track.title)
            return
        }
        if (track.isLocal || track.videoId.isBlank() || track.videoId.startsWith("local_")) {
            _uiNotice.value = "Sin página de álbum para pista local"
            return
        }
        viewModelScope.launch {
            val resolved = runCatching { repository.resolveTrackMetadata(track) }.getOrNull()
            val albumId = resolved?.albumId?.takeIf { it.isNotBlank() }
            if (albumId != null) {
                playerController.updateTrackMetadata(resolved)
                onResolved(albumId, resolved.album?.takeIf { it.isNotBlank() } ?: track.title)
            } else {
                _uiNotice.value = "No se encontró el álbum de ${track.title}"
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

    fun observePlaylistTransitions(playlistId: Long): Flow<List<PlaylistTransitionSpec>> =
        repository.observePlaylistTransitions(playlistId)

    fun savePlaylistTransition(spec: PlaylistTransitionSpec) {
        viewModelScope.launch {
            repository.savePlaylistTransition(spec)
            _uiNotice.value = "Transición guardada (${spec.crossfadeMs / 1000}s)"
        }
    }

    fun deletePlaylistTransition(playlistId: Long, fromVideoId: String, toVideoId: String) {
        viewModelScope.launch {
            repository.deletePlaylistTransition(playlistId, fromVideoId, toVideoId)
        }
    }

    fun playPlaylistWithTransitions(tracks: List<Track>, startIndex: Int = 0, playlistId: Long) {
        playerController.playPlaylistWithTransitions(tracks, startIndex, playlistId)
    }

    fun playCustomMixFragments(fragments: List<CustomMixFragment>) {
        playerController.playCustomMixFragments(fragments)
    }

    fun previewTransitionPair(
        outgoingTrack: Track,
        fromExitMs: Long,
        incomingTrack: Track,
        toEntryMs: Long,
        overlapMs: Int,
        curveType: TransitionCurveType
    ) {
        playerController.previewTransitionPair(
            outgoingTrack = outgoingTrack,
            fromExitMs = fromExitMs,
            incomingTrack = incomingTrack,
            toEntryMs = toEntryMs,
            overlapMs = overlapMs,
            curveType = curveType
        )
    }

    fun retuneUpcomingQueue(mood: QueueMoodFilter) {
        playerController.retuneUpcomingQueue(mood)
    }

    fun setAudioQuality(quality: AudioQuality) {
        viewModelScope.launch { preferences.setWifiAudioQuality(quality) }
    }

    fun setWifiAudioQuality(quality: AudioQuality) {
        viewModelScope.launch { preferences.setWifiAudioQuality(quality) }
    }

    fun setMobileAudioQuality(quality: AudioQuality) {
        viewModelScope.launch { preferences.setMobileAudioQuality(quality) }
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

    fun setCrossfadeSeconds(seconds: Int) {
        viewModelScope.launch { preferences.setCrossfadeSeconds(seconds) }
    }

    fun setSmoothSkipFadeEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setSmoothSkipFadeEnabled(enabled) }
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

    fun setAnimatedArtworkMobileData(enabled: Boolean) {
        viewModelScope.launch { preferences.setAnimatedArtworkMobileData(enabled) }
    }

    fun setAutoHidePlayerControls(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoHidePlayerControls(enabled) }
    }

    fun setSpotifyCanvasEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setSpotifyCanvasEnabled(enabled) }
    }

    fun setPlayerBackdropStyle(style: PlayerBackdropStyle) {
        viewModelScope.launch { preferences.setPlayerBackdropStyle(style) }
    }

    fun setStatsPeriod(period: StatsPeriod) {
        _statsPeriod.value = period
    }

    fun ensureYouTubeHistoryLoaded() {
        viewModelScope.launch { repository.loadInitialYouTubeHistoryIfNeeded() }
    }

    fun loadMoreYouTubeHistory() {
        viewModelScope.launch { repository.loadMoreYouTubeHistory() }
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
            val videoId = playerController.state.value.currentTrack?.videoId.orEmpty()
            val current = settings.value.lyricsOffsetFor(videoId)
            val next = (current + deltaMs).coerceIn(-2_000L, 2_000L)
            if (videoId.isNotBlank()) {
                preferences.setTrackLyricsOffsetMs(videoId, next)
            } else {
                preferences.setLyricsOffsetMs(next)
            }
        }
    }

    fun resetLyricsOffsetMs() {
        viewModelScope.launch {
            val videoId = playerController.state.value.currentTrack?.videoId.orEmpty()
            if (videoId.isNotBlank()) {
                preferences.setTrackLyricsOffsetMs(videoId, 0L)
            }
            preferences.setLyricsOffsetMs(0L)
        }
    }

    fun setGlassStyle(style: GlassStyle) {
        viewModelScope.launch { preferences.setGlassStyle(style) }
    }

    fun setLiquidGlassEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setLiquidGlassEnabled(enabled) }
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
