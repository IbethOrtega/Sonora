package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AccountSessionEntity
import com.example.data.local.PlaylistEntity
import com.example.data.repository.MusicRepository
import com.example.model.BrowseCard
import com.example.model.BrowseDetail
import com.example.model.HomeShelf
import com.example.model.MoodCategory
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.SpotifyImportState
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class SonoraViewModel(
    private val repository: MusicRepository,
    val playerController: PlayerController
) : ViewModel() {

    val playbackState: StateFlow<PlaybackUiState> = playerController.state

    private val _homeState = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val homeState: StateFlow<UiState<List<HomeShelf>>> = _homeState.asStateFlow()

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

    private val _ytLikedSongs = MutableStateFlow<List<Track>>(emptyList())
    val ytLikedSongs: StateFlow<List<Track>> = _ytLikedSongs.asStateFlow()

    private val _isSyncingYt = MutableStateFlow(false)
    val isSyncingYt: StateFlow<Boolean> = _isSyncingYt.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _spotifyImportState = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val spotifyImportState: StateFlow<SpotifyImportState> = _spotifyImportState.asStateFlow()

    val likedSongs: StateFlow<List<Track>> = repository.observeLikedSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTracks: StateFlow<List<Track>> = repository.observeRecentTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedCollections: StateFlow<List<BrowseCard>> = repository.observeSavedCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchHistory: StateFlow<List<String>> = repository.observeSearchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var importJob: Job? = null

    init {
        viewModelScope.launch {
            val session = repository.restoreSessionOnStartup()
            if (session != null) {
                syncYouTubeLibrary()
            }
            loadHome()
            loadExplore()
        }
    }

    fun onYouTubeCookiesCaptured(cookies: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isSyncingYt.value = true
            _authError.value = null
            repository.connectYouTubeAccount(cookies)
                .onSuccess {
                    syncYouTubeLibrary()
                    loadHome()
                    onSuccess()
                }
                .onFailure { err ->
                    _authError.value = err.localizedMessage ?: "No se pudo validar la sesión de Google."
                    _isSyncingYt.value = false
                }
        }
    }

    fun syncYouTubeLibrary() {
        viewModelScope.launch {
            _isSyncingYt.value = true
            repository.syncYouTubeLibrary()
                .onSuccess { synced ->
                    _ytPlaylists.value = synced.playlists
                    _ytLikedSongs.value = synced.likedSongs
                }
            _isSyncingYt.value = false
        }
    }

    fun signOutYouTube() {
        viewModelScope.launch {
            repository.disconnectYouTubeAccount()
            _ytPlaylists.value = emptyList()
            _ytLikedSongs.value = emptyList()
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
                }
                .onFailure { err ->
                    _homeState.value = UiState.Error(err.localizedMessage ?: "No se pudo conectar con YouTube Music.")
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

    class Factory(
        private val repository: MusicRepository,
        private val playerController: PlayerController
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SonoraViewModel(repository, playerController) as T
        }
    }
}
