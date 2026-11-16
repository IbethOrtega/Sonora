package com.example.data.repository

import com.example.data.local.AccountSessionEntity
import com.example.data.local.LibraryDao
import com.example.data.local.LikedSongEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackEntity
import com.example.data.local.RecentTrackEntity
import com.example.data.local.SavedCollectionEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.toBrowseCard
import com.example.data.local.toTrack
import com.example.data.network.SpotifyImporter
import com.example.data.network.StreamResolver
import com.example.data.network.YouTubeMusicClient
import com.example.model.BrowseCard
import com.example.model.BrowseDetail
import com.example.model.HomeShelf
import com.example.model.MoodCategory
import com.example.model.ResolvedStream
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.SpotifyImportState
import com.example.model.SpotifyParsedPlaylist
import com.example.model.Track
import com.example.model.YouTubeAccountInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SyncedYouTubeLibrary(
    val playlists: List<BrowseCard>,
    val likedSongs: List<Track>,
    val historyTracks: List<Track> = emptyList()
)

class MusicRepository(
    private val ytMusicClient: YouTubeMusicClient,
    private val streamResolver: StreamResolver,
    private val spotifyImporter: SpotifyImporter,
    private val libraryDao: LibraryDao
) {
    suspend fun restoreSessionOnStartup(): AccountSessionEntity? {
        val session = libraryDao.getAccountSession()
        if (session != null) {
            ytMusicClient.setSessionCookies(session.cookies)
            streamResolver.setSessionCookies(session.cookies)
        }
        return session
    }

    fun observeAccountSession(): Flow<AccountSessionEntity?> = libraryDao.observeAccountSession()

    suspend fun connectYouTubeAccount(cookies: String): Result<YouTubeAccountInfo> = runCatching {
        ytMusicClient.setSessionCookies(cookies)
        streamResolver.setSessionCookies(cookies)
        val info = ytMusicClient.getAccountInfo()
            ?: YouTubeAccountInfo(name = "Cuenta de Google", handle = "Sesión de YouTube Music")
        libraryDao.saveAccountSession(
            AccountSessionEntity(
                cookies = cookies,
                accountName = info.name,
                accountHandle = info.handle,
                avatarUrl = info.avatarUrl
            )
        )
        info
    }

    suspend fun disconnectYouTubeAccount() {
        ytMusicClient.setSessionCookies(null)
        streamResolver.setSessionCookies(null)
        libraryDao.clearAccountSession()
    }

    suspend fun syncYouTubeLibrary(): Result<SyncedYouTubeLibrary> = runCatching {
        val playlists = runCatching { ytMusicClient.getLibraryPlaylists() }
            .getOrDefault(emptyList())
            .ifEmpty { runCatching { ytMusicClient.getLibrarySavedCollections() }.getOrDefault(emptyList()) }
        val liked = runCatching { ytMusicClient.getLibraryLikedSongs() }.getOrDefault(emptyList())
        val history = runCatching { ytMusicClient.getYouTubeHistory() }.getOrDefault(emptyList())

        if (history.isNotEmpty()) {
            val baseTime = System.currentTimeMillis()
            history.take(15).asReversed().forEachIndexed { idx, track ->
                libraryDao.insertRecentTrack(
                    RecentTrackEntity(
                        videoId = track.videoId,
                        title = track.title,
                        artist = track.artist,
                        artistId = track.artistId,
                        album = track.album,
                        albumId = track.albumId,
                        artworkUrl = track.artworkUrl,
                        durationText = track.durationText,
                        durationMs = track.durationMs,
                        playedAt = baseTime + idx
                    )
                )
            }
        }
        SyncedYouTubeLibrary(playlists = playlists, likedSongs = liked, historyTracks = history)
    }

    fun importSpotifyUrl(url: String): Flow<SpotifyImportState> = flow {
        emit(SpotifyImportState.FetchingSpotify())
        val parsed = spotifyImporter.fetchSpotifyPlaylist(url).getOrElse { err ->
            emit(SpotifyImportState.Failed(err.localizedMessage ?: "No se pudo leer la playlist de Spotify."))
            return@flow
        }
        matchAndSaveSpotifyPlaylist(parsed).collect { emit(it) }
    }

    fun importSpotifyText(playlistName: String, rawText: String): Flow<SpotifyImportState> = flow {
        emit(SpotifyImportState.FetchingSpotify("Procesando lista de canciones..."))
        val parsed = spotifyImporter.parsePlainTextTracklist(playlistName, rawText)
        if (parsed.tracks.isEmpty()) {
            emit(SpotifyImportState.Failed("No se detectaron canciones en el texto ingresado."))
            return@flow
        }
        matchAndSaveSpotifyPlaylist(parsed).collect { emit(it) }
    }

    private fun matchAndSaveSpotifyPlaylist(parsed: SpotifyParsedPlaylist): Flow<SpotifyImportState> = flow {
        val total = parsed.tracks.size
        val semaphore = Semaphore(4)
        var processed = 0
        var matched = 0

        emit(
            SpotifyImportState.MatchingTracks(
                playlistTitle = parsed.title,
                matchedCount = 0,
                processedCount = 0,
                totalCount = total,
                currentTrackName = parsed.tracks.firstOrNull()?.title.orEmpty()
            )
        )

        val matchedTracks = mutableListOf<Track>()
        // Process in batches of 4 for fast concurrent matching while emitting clean progress
        for (batch in parsed.tracks.chunked(4)) {
            val batchResults = coroutineScope {
                batch.map { spTrack ->
                    async {
                        semaphore.withPermit {
                            val query = listOf(spTrack.title, spTrack.artist)
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                            val searchResult = runCatching {
                                ytMusicClient.search(query, SearchFilter.SONGS)
                            }.getOrNull()
                            pickBestMatch(spTrack.title, spTrack.artist, searchResult?.songs.orEmpty())
                        }
                    }
                }.awaitAll()
            }

            batchResults.forEachIndexed { idx, ytTrack ->
                processed++
                if (ytTrack != null) {
                    matched++
                    matchedTracks.add(ytTrack)
                }
                val currentName = batch[idx].title
                emit(
                    SpotifyImportState.MatchingTracks(
                        playlistTitle = parsed.title,
                        matchedCount = matched,
                        processedCount = processed,
                        totalCount = total,
                        currentTrackName = currentName
                    )
                )
            }
        }

        if (matchedTracks.isEmpty()) {
            emit(SpotifyImportState.Failed("No se encontraron coincidencias en YouTube Music para esta lista."))
            return@flow
        }

        val playlistId = libraryDao.insertPlaylist(
            PlaylistEntity(
                name = parsed.title,
                description = "Importada desde Spotify (${matchedTracks.size}/$total canciones)",
                coverUrl = parsed.coverUrl.ifBlank { matchedTracks.first().artworkUrl }
            )
        )

        // Insert in reverse order so addedAt DESC preserves original playlist order
        matchedTracks.asReversed().forEach { track ->
            addTrackToPlaylist(playlistId, track)
        }

        emit(
            SpotifyImportState.Completed(
                playlistId = playlistId,
                playlistTitle = parsed.title,
                matchedCount = matchedTracks.size,
                totalCount = total
            )
        )
    }

    private fun pickBestMatch(title: String, artist: String, candidates: List<Track>): Track? {
        if (candidates.isEmpty()) return null
        val targetTitle = title.lowercase().trim()
        val targetArtist = artist.lowercase().split(",").firstOrNull()?.trim().orEmpty()

        return candidates.maxByOrNull { candidate ->
            var score = 0
            val cTitle = candidate.title.lowercase()
            val cArtist = candidate.artist.lowercase()
            if (cTitle == targetTitle) score += 50
            else if (cTitle.contains(targetTitle) || targetTitle.contains(cTitle)) score += 25
            if (targetArtist.isNotBlank()) {
                if (cArtist.contains(targetArtist)) score += 35
            }
            if (cTitle.contains("live") && !targetTitle.contains("live")) score -= 10
            if (cTitle.contains("remix") && !targetTitle.contains("remix")) score -= 10
            score
        }
    }

    suspend fun getHomeShelves(): Result<List<HomeShelf>> = runCatching {
        ytMusicClient.getHomeShelves()
    }

    suspend fun getPersonalizedRecommendations(seedTracks: List<Track>): List<HomeShelf> {
        return runCatching { ytMusicClient.getPersonalizedRecommendations(seedTracks) }
            .getOrDefault(emptyList())
    }

    suspend fun getExploreData(): Result<Pair<List<HomeShelf>, List<MoodCategory>>> = runCatching {
        ytMusicClient.getExploreData()
    }

    suspend fun getSearchSuggestions(query: String): List<String> {
        return runCatching { ytMusicClient.getSearchSuggestions(query) }.getOrDefault(emptyList())
    }

    suspend fun search(query: String, filter: SearchFilter): Result<SearchResults> = runCatching {
        if (query.isNotBlank()) {
            libraryDao.insertSearchHistory(SearchHistoryEntity(query = query.trim()))
        }
        ytMusicClient.search(query, filter)
    }

    suspend fun getBrowseDetail(browseId: String, params: String? = null): Result<BrowseDetail> = runCatching {
        ytMusicClient.getBrowseDetail(browseId, params)
    }

    suspend fun getUpNextAndLyricsId(videoId: String): Pair<List<Track>, String?> {
        return runCatching { ytMusicClient.getUpNextAndLyricsBrowseId(videoId) }
            .getOrDefault(emptyList<Track>() to null)
    }

    suspend fun getLyrics(lyricsBrowseId: String): String? {
        return runCatching { ytMusicClient.getLyrics(lyricsBrowseId) }.getOrNull()
    }

    suspend fun resolveStream(videoId: String): Result<ResolvedStream> {
        return streamResolver.resolveAudioStream(videoId)
    }

    suspend fun findAlternatePlayableStream(originalTrack: Track): ResolvedStream? {
        val query = listOf(originalTrack.title, originalTrack.artist, "audio")
            .filter { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
            .joinToString(" ")
        val candidates = runCatching {
            ytMusicClient.search(query, SearchFilter.SONGS).songs
        }.getOrDefault(emptyList())
            .filter { it.videoId != originalTrack.videoId && it.videoId.isNotBlank() }
            .take(1)

        for (candidate in candidates) {
            val stream = streamResolver.resolveAudioStream(candidate).getOrNull()
            if (stream != null) return stream
        }
        return null
    }

    fun clearStreamCache() {
        streamResolver.clearCache()
    }

    suspend fun clearPlaybackHistory() {
        libraryDao.clearRecentTracks()
    }

    suspend fun clearSearchHistory() {
        libraryDao.clearSearchHistory()
    }

    // --- Local Library (Room) ---
    fun observeLikedSongs(): Flow<List<Track>> =
        libraryDao.observeLikedSongs().map { list -> list.map { it.toTrack() } }

    fun observeIsLiked(videoId: String): Flow<Boolean> =
        libraryDao.observeIsLiked(videoId)

    suspend fun toggleLike(track: Track): Boolean {
        val currentlyLiked = libraryDao.isLiked(track.videoId)
        if (currentlyLiked) {
            libraryDao.deleteLikedSong(track.videoId)
            return false
        } else {
            libraryDao.insertLikedSong(
                LikedSongEntity(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    artistId = track.artistId,
                    album = track.album,
                    albumId = track.albumId,
                    artworkUrl = track.artworkUrl,
                    durationText = track.durationText,
                    durationMs = track.durationMs
                )
            )
            return true
        }
    }

    fun observePlaylists(): Flow<List<PlaylistEntity>> = libraryDao.observePlaylists()

    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?> = libraryDao.observePlaylist(playlistId)

    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        libraryDao.observePlaylistTracks(playlistId).map { list -> list.map { it.toTrack() } }

    fun observePlaylistTrackCount(playlistId: Long): Flow<Int> =
        libraryDao.observePlaylistTrackCount(playlistId)

    suspend fun createPlaylist(name: String, description: String = ""): Long {
        return libraryDao.insertPlaylist(
            PlaylistEntity(name = name.trim(), description = description.trim())
        )
    }

    suspend fun deletePlaylist(playlistId: Long) {
        libraryDao.clearPlaylistTracks(playlistId)
        libraryDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: Track) {
        libraryDao.insertPlaylistTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                artistId = track.artistId,
                album = track.album,
                albumId = track.albumId,
                artworkUrl = track.artworkUrl,
                durationText = track.durationText,
                durationMs = track.durationMs
            )
        )
        if (track.artworkUrl.isNotBlank()) {
            libraryDao.updatePlaylistCoverIfEmpty(playlistId, track.artworkUrl)
        }
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, videoId: String) {
        libraryDao.removeTrackFromPlaylist(playlistId, videoId)
    }

    fun observeRecentTracks(): Flow<List<Track>> =
        libraryDao.observeRecentTracks().map { list -> list.map { it.toTrack() } }

    suspend fun recordPlay(track: Track) {
        libraryDao.insertRecentTrack(
            RecentTrackEntity(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                artistId = track.artistId,
                album = track.album,
                albumId = track.albumId,
                artworkUrl = track.artworkUrl,
                durationText = track.durationText,
                durationMs = track.durationMs
            )
        )
    }

    fun observeSavedCollections(): Flow<List<BrowseCard>> =
        libraryDao.observeSavedCollections().map { list -> list.map { it.toBrowseCard() } }

    fun observeIsCollectionSaved(browseId: String): Flow<Boolean> =
        libraryDao.observeIsCollectionSaved(browseId)

    suspend fun toggleSaveCollection(card: BrowseCard, currentlySaved: Boolean) {
        if (currentlySaved) {
            libraryDao.deleteSavedCollection(card.id)
        } else {
            libraryDao.insertSavedCollection(
                SavedCollectionEntity(
                    browseId = card.id,
                    title = card.title,
                    subtitle = card.subtitle,
                    artworkUrl = card.artworkUrl,
                    kind = card.kind.name
                )
            )
        }
    }

    fun observeSearchHistory(): Flow<List<String>> =
        libraryDao.observeSearchHistory().map { list -> list.map { it.query } }

    suspend fun deleteSearchHistory(query: String) {
        libraryDao.deleteSearchHistory(query)
    }
}
