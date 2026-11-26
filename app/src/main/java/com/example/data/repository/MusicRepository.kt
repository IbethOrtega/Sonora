package com.example.data.repository

import android.util.Log
import com.example.data.local.AccountSessionEntity
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.LibraryDao
import com.example.data.local.LikedSongEntity
import com.example.data.local.ListeningHistoryEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackEntity
import com.example.data.local.PlaylistTransitionEntity
import com.example.data.local.QueueSeedHistoryEntity
import com.example.data.local.RecentTrackEntity
import com.example.data.local.SavedCollectionEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.YtLikedSongEntity
import com.example.data.local.toBrowseCard
import com.example.data.local.toHistoryEntry
import com.example.data.local.toSpec
import com.example.data.local.toTrack
import com.example.data.network.CatalogAudioFallback
import com.example.data.network.InnerTubeXResolver
import com.example.data.network.LyricsResolver
import com.example.data.network.NewPipeSearchClient
import com.example.data.network.PlaybackDiagnostics
import com.example.data.network.SpotifyImporter
import com.example.data.network.StreamResolver
import com.example.data.network.YouTubeMusicClient
import com.example.model.AlbumPlayStat
import com.example.model.ArtistCredit
import com.example.model.ArtistPlayStat
import com.example.model.BrowseCard
import com.example.model.BrowseDetail
import com.example.model.HistoryDayGroup
import com.example.model.HomeFilterChip
import com.example.model.HomeShelf
import com.example.model.ItemKind
import com.example.model.ListeningHistoryEntry
import com.example.model.ListeningStatsSummary
import com.example.model.MonthlyListeningStat
import com.example.model.MoodCategory
import com.example.model.PlaylistTransitionSpec
import com.example.model.QueueMoodFilter
import com.example.model.ResolvedStream
import com.example.model.ResolvedTrackMetadata
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.SpotifyImportState
import com.example.model.SpotifyParsedPlaylist
import com.example.model.StatsPeriod
import com.example.model.Track
import com.example.model.TrackLyrics
import com.example.model.TrackPlayStat
import com.example.model.YouTubeAccountInfo
import com.example.model.YouTubeAuthSessionPayload
import com.example.model.YouTubeHistorySection
import com.example.model.splitArtistNames
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.random.Random

data class SyncedYouTubeLibrary(
    val playlists: List<BrowseCard>,
    val likedSongs: List<Track>,
    val historyTracks: List<Track> = emptyList(),
    val savedCollections: List<BrowseCard> = emptyList(),
    val artists: List<BrowseCard> = emptyList(),
    val uploadedSongs: List<Track> = emptyList()
)

class MusicRepository(
    private val ytMusicClient: YouTubeMusicClient,
    private val streamResolver: StreamResolver,
    private val spotifyImporter: SpotifyImporter,
    private val libraryDao: LibraryDao,
    private val lyricsResolver: LyricsResolver? = null,
    private val newPipeSearchClient: NewPipeSearchClient? = null
) {
    @Volatile
    var audioOnlyPreference: Boolean = false

    private val _cloudHistorySections = MutableStateFlow<List<YouTubeHistorySection>>(emptyList())
    private val _cloudHistoryNextToken = MutableStateFlow<String?>(null)
    private val _isLoadingMoreHistory = MutableStateFlow(false)
    val isLoadingMoreHistory: StateFlow<Boolean> = _isLoadingMoreHistory.asStateFlow()
    val hasMoreYouTubeHistory: Flow<Boolean> = combine(
        _cloudHistoryNextToken,
        libraryDao.observeAccountSession()
    ) { token, session ->
        !token.isNullOrBlank() && session != null
    }

    private data class CachedAlternateVideo(
        val alternateVideoId: String,
        val cachedAtMs: Long
    )

    internal enum class AlternatePriorityTier(val rank: Int, val label: String) {
        OFFICIAL_VIDEO(0, "Official Video"),
        LYRIC_VIDEO(1, "Lyric Video"),
        OFFICIAL_AUDIO(2, "Official Audio"),
        YTM_SONG(3, "YTM Song")
    }

    internal data class ScoredAlternateCandidate(
        val track: Track,
        val score: Int,
        val tier: AlternatePriorityTier
    )

    private val alternateVideoCache = ConcurrentHashMap<String, CachedAlternateVideo>()
    private val trackMetadataCache = ConcurrentHashMap<String, ResolvedTrackMetadata>()

    @Volatile
    internal var alternateClockMs: () -> Long = { System.currentTimeMillis() }

    suspend fun restoreSessionOnStartup(): AccountSessionEntity? {
        val session = libraryDao.getAccountSession()
        if (session != null) {
            val payload = YouTubeAuthSessionPayload(
                cookies = session.cookies,
                visitorData = session.visitorData,
                dataSyncId = session.dataSyncId,
                delegatedSessionId = session.delegatedSessionId,
                authUser = session.authUser.ifBlank { "0" },
                clientVersion = session.clientVersion
            )
            ytMusicClient.setSessionContext(payload)
            streamResolver.setSessionContext(payload)
            InnerTubeXResolver.onSessionChanged()
        }
        return session
    }

    fun observeAccountSession(): Flow<AccountSessionEntity?> = libraryDao.observeAccountSession()

    suspend fun connectYouTubeAccount(cookies: String): Result<YouTubeAccountInfo> =
        connectYouTubeAccount(YouTubeAuthSessionPayload(cookies = cookies))

    suspend fun connectYouTubeAccount(payload: YouTubeAuthSessionPayload): Result<YouTubeAccountInfo> = runCatching {
        ytMusicClient.setSessionContext(payload)
        streamResolver.setSessionContext(payload)
        val bootstrapped = ytMusicClient.ensureSessionBootstrapped() ?: payload
        val info = ytMusicClient.getAccountInfo()
            ?: YouTubeAccountInfo(name = "Cuenta de Google", handle = "Sesión de YouTube Music")
        val latestCtx = ytMusicClient.getCurrentSessionContext() ?: bootstrapped
        if (latestCtx != payload) {
            streamResolver.setSessionContext(latestCtx)
        }
        InnerTubeXResolver.onSessionChanged()
        libraryDao.saveAccountSession(
            AccountSessionEntity(
                cookies = latestCtx.cookies,
                accountName = info.name,
                accountHandle = info.handle,
                avatarUrl = info.avatarUrl,
                visitorData = latestCtx.visitorData,
                dataSyncId = latestCtx.dataSyncId,
                delegatedSessionId = latestCtx.delegatedSessionId,
                authUser = latestCtx.authUser.ifBlank { "0" },
                clientVersion = latestCtx.clientVersion
            )
        )
        info
    }

    suspend fun disconnectYouTubeAccount() {
        ytMusicClient.setSessionContext(null)
        streamResolver.setSessionContext(null)
        InnerTubeXResolver.onSessionChanged()
        _cloudHistorySections.value = emptyList()
        _cloudHistoryNextToken.value = null
        libraryDao.clearAccountSession()
        libraryDao.clearYtLikedSongs()
    }

    fun observeYtLikedSongs(): Flow<List<Track>> =
        libraryDao.observeYtLikedSongs().map { rows -> rows.map { it.toTrack() } }

    suspend fun syncYouTubeLibrary(): Result<SyncedYouTubeLibrary> = runCatching {
        coroutineScope {
            val playlistsDeferred = async { runCatching { ytMusicClient.getLibraryPlaylists() } }
            val savedDeferred = async { runCatching { ytMusicClient.getLibrarySavedCollections() } }
            val artistsDeferred = async { runCatching { ytMusicClient.getLibraryArtists() } }
            val likedDeferred = async { runCatching { ytMusicClient.getLibraryLikedSongs() } }
            val uploadsDeferred = async { runCatching { ytMusicClient.getLibraryUploadedSongs() } }
            val historyPageDeferred = async { runCatching { ytMusicClient.getYouTubeHistoryPage(null) } }

            val plRes = playlistsDeferred.await()
            val savedRes = savedDeferred.await()
            val artistsRes = artistsDeferred.await()
            val likedRes = likedDeferred.await()
            val uploadsRes = uploadsDeferred.await()
            val historyPageRes = historyPageDeferred.await()

            val allFailed = plRes.isFailure &&
                savedRes.isFailure &&
                artistsRes.isFailure &&
                likedRes.isFailure &&
                uploadsRes.isFailure &&
                historyPageRes.isFailure
            if (allFailed) {
                val firstErr = likedRes.exceptionOrNull() ?: plRes.exceptionOrNull()
                val msg = firstErr?.message.orEmpty()
                if (msg.contains("401") || msg.contains("403")) {
                    throw IllegalStateException("La sesión de Google expiró. Vuelve a iniciar sesión.")
                }
                throw firstErr ?: IllegalStateException("No se pudo sincronizar con YouTube Music.")
            }

            // Persist any freshly bootstrapped ytcfg metadata (visitorData, delegatedSessionId, clientVersion)
            val existingSession = libraryDao.getAccountSession()
            val activeCtx = ytMusicClient.getCurrentSessionContext()
            if (existingSession != null && activeCtx != null && (
                    existingSession.visitorData != activeCtx.visitorData ||
                    existingSession.delegatedSessionId != activeCtx.delegatedSessionId ||
                    existingSession.dataSyncId != activeCtx.dataSyncId ||
                    existingSession.clientVersion != activeCtx.clientVersion
                )
            ) {
                libraryDao.saveAccountSession(
                    existingSession.copy(
                        visitorData = activeCtx.visitorData.ifBlank { existingSession.visitorData },
                        dataSyncId = activeCtx.dataSyncId.ifBlank { existingSession.dataSyncId },
                        delegatedSessionId = activeCtx.delegatedSessionId.ifBlank { existingSession.delegatedSessionId },
                        authUser = activeCtx.authUser.ifBlank { existingSession.authUser },
                        clientVersion = activeCtx.clientVersion.ifBlank { existingSession.clientVersion }
                    )
                )
            }

            val playlists = plRes.getOrDefault(emptyList())
            val savedCollections = savedRes.getOrDefault(emptyList())
            val artists = artistsRes.getOrDefault(emptyList())
            val liked = likedRes.getOrDefault(emptyList())
            val uploads = uploadsRes.getOrDefault(emptyList())
            val historyPage = historyPageRes.getOrNull()
            val history = historyPage?.flatTracks.orEmpty()
            if (historyPage != null) {
                _cloudHistorySections.value = historyPage.sections
                _cloudHistoryNextToken.value = historyPage.nextContinuationToken
            }

            if (likedRes.isSuccess) {
                val syncNow = System.currentTimeMillis()
                libraryDao.replaceYtLikedSongs(
                    liked.mapIndexed { idx, track ->
                        YtLikedSongEntity(
                            videoId = track.videoId,
                            title = track.title,
                            artist = track.artist,
                            artistId = track.artistId,
                            album = track.album,
                            albumId = track.albumId,
                            artworkUrl = track.artworkUrl,
                            durationText = track.durationText,
                            durationMs = track.durationMs,
                            isExplicit = track.isExplicit,
                            positionIndex = idx,
                            syncedAtMs = syncNow
                        )
                    }
                )
            }

            if (history.isNotEmpty()) {
                val baseTime = System.currentTimeMillis()
                history.take(50).asReversed().forEachIndexed { idx, track ->
                    val existingCount = libraryDao.getRecentPlayCount(track.videoId)
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
                            playedAt = baseTime + idx,
                            playCount = existingCount ?: 1
                        )
                    )
                }
            }
            SyncedYouTubeLibrary(
                playlists = playlists,
                likedSongs = liked,
                historyTracks = history,
                savedCollections = savedCollections,
                artists = artists,
                uploadedSongs = uploads
            )
        }
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
                            pickBestMatch(
                                title = spTrack.title,
                                artist = spTrack.artist,
                                durationMs = spTrack.durationMs,
                                candidates = searchResult?.songs.orEmpty()
                            )
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

    private fun pickBestMatch(
        title: String,
        artist: String,
        durationMs: Long,
        candidates: List<Track>
    ): Track? {
        if (candidates.isEmpty()) return null
        val minScore = if (artist.isNotBlank()) 120 else 80
        return candidates
            .map { it to scoreTrackMatch(title, artist, durationMs, it) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }
            ?.first
    }

    internal fun scoreTrackMatch(
        targetTitle: String,
        targetArtist: String,
        targetDurationMs: Long,
        candidate: Track
    ): Int {
        return com.example.data.network.CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = targetTitle,
            targetArtist = targetArtist,
            targetDurationMs = targetDurationMs,
            candTitle = candidate.title,
            candArtist = candidate.artist,
            candDurationSec = (candidate.durationMs / 1000L).toInt(),
            isVerified = true
        )
    }

    @Volatile
    private var cachedHomeDefaultFeed: Pair<Long, Pair<List<HomeShelf>, List<HomeFilterChip>>>? = null

    @Volatile
    private var cachedExploreFeed: Pair<Long, Pair<List<HomeShelf>, List<MoodCategory>>>? = null

    suspend fun getHomeShelves(): Result<List<HomeShelf>> = runCatching {
        ytMusicClient.getHomeShelves()
    }

    suspend fun getHomeFeed(
        chip: HomeFilterChip? = null,
        forceRefresh: Boolean = false
    ): Result<Pair<List<HomeShelf>, List<HomeFilterChip>>> = runCatching {
        val now = System.currentTimeMillis()
        val isDefaultFeed = chip == null || (chip.params.isNullOrBlank() && chip.fallbackSearchQuery.isNullOrBlank())
        if (isDefaultFeed && !forceRefresh) {
            cachedHomeDefaultFeed?.let { (ts, cached) ->
                if (now - ts < HOME_FEED_CACHE_TTL_MS && cached.first.isNotEmpty()) {
                    return@runCatching cached
                }
            }
        }
        val fetched = if (isDefaultFeed) {
            ytMusicClient.getHomeFeed(params = null)
        } else {
            ytMusicClient.getHomeFeedForChip(chip!!)
        }
        if (isDefaultFeed && fetched.first.isNotEmpty()) {
            cachedHomeDefaultFeed = now to fetched
        }
        fetched
    }

    fun isHomeFeedCacheStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cached = cachedHomeDefaultFeed ?: return true
        return nowMs - cached.first >= HOME_FEED_CACHE_TTL_MS
    }

    suspend fun getPersonalizedRecommendations(
        seedTracks: List<Track>,
        mostPlayedTracks: List<Track> = emptyList(),
        likedTracks: List<Track> = emptyList(),
        homeShelves: List<HomeShelf> = emptyList(),
        refreshSeed: Int = 0
    ): List<HomeShelf> = coroutineScope {
        val nowMs = System.currentTimeMillis()
        val dailySeed = HomeFeedCurator.dailyRotationSeed(nowMs, refreshSeed)
        val historyRows = runCatching { libraryDao.getAllListeningHistoryOnce() }.getOrDefault(emptyList())
        val recentRows = runCatching { libraryDao.observeRecentTracks().first() }.getOrDefault(emptyList())
        val (excluded24hIds, excluded24hKeys) = HomeFeedCurator.buildPlayedLast24hExclusion(
            historyRows = historyRows,
            recentRows = recentRows,
            nowMs = nowMs
        )

        val tasteDeferred = async { loadTasteProfile() }
        val basePersonalizedDeferred = async {
            runCatching {
                ytMusicClient.getPersonalizedRecommendations(seedTracks, mostPlayedTracks, likedTracks)
            }.getOrDefault(emptyList())
        }
        val exploreDeferred = async {
            val cached = cachedExploreFeed
            if (cached != null && nowMs - cached.first < HOME_FEED_CACHE_TTL_MS) {
                cached.second
            } else {
                runCatching { ytMusicClient.getExploreData() }
                    .onSuccess { cachedExploreFeed = nowMs to it }
                    .getOrNull()
            }
        }

        val taste = tasteDeferred.await()
        val basePersonalized = basePersonalizedDeferred.await()
        val exploreData = exploreDeferred.await()

        val resultShelves = mutableListOf<HomeShelf>()

        // 1. "Mixes para ti" ("Mix de [artista]" cards from top weighted artists)
        val allHistoryAndLikedTracks = (mostPlayedTracks + likedTracks + seedTracks + taste.recentTracks)
            .filter { it.videoId.isNotBlank() && it.artist.isNotBlank() && !it.artist.equals("YouTube Music", ignoreCase = true) }
            .distinctBy { it.videoId }
        val topArtistsOrdered = taste.artistWeights.entries
            .sortedByDescending { it.value }
            .map { it.key }
        val artistRepresentativeTracks = mutableListOf<Track>()
        val seenMixArtist = mutableSetOf<String>()
        for (artistKey in HomeFeedCurator.rotateDeterministically(topArtistsOrdered.take(10), dailySeed + 1L)) {
            val rep = allHistoryAndLikedTracks.firstOrNull { t ->
                val primary = QueueBuilder.splitArtistTokens(t.artist).firstOrNull().orEmpty()
                primary == artistKey
            }
            if (rep != null && seenMixArtist.add(artistKey)) {
                artistRepresentativeTracks.add(rep)
            }
            if (artistRepresentativeTracks.size >= 8) break
        }
        if (artistRepresentativeTracks.size < 4) {
            for (t in HomeFeedCurator.rotateDeterministically(allHistoryAndLikedTracks, dailySeed + 3L)) {
                val primary = QueueBuilder.splitArtistTokens(t.artist).firstOrNull().orEmpty()
                if (primary.isNotBlank() && seenMixArtist.add(primary)) {
                    artistRepresentativeTracks.add(t)
                }
                if (artistRepresentativeTracks.size >= 8) break
            }
        }
        if (artistRepresentativeTracks.isNotEmpty()) {
            resultShelves.add(
                HomeShelf(
                    title = "Mixes para ti",
                    subtitle = "RADIOS POR ARTISTA",
                    tracks = artistRepresentativeTracks
                )
            )
        }

        // 2. "Para esta mañana / tarde / noche" from time-of-day history
        val timeSlot = HomeFeedCurator.timeOfDaySlot(nowMs)
        var timeSlotTracks = HomeFeedCurator.findTimeOfDayTracks(
            historyRows = historyRows,
            slot = timeSlot,
            excluded24hIds = excluded24hIds,
            excluded24hDedupeKeys = excluded24hKeys,
            seed = dailySeed,
            limit = 12
        )
        if (timeSlotTracks.size < 4) {
            val fallbackPool = basePersonalized.flatMap { it.tracks } +
                homeShelves.flatMap { it.tracks }
            timeSlotTracks = (timeSlotTracks + HomeFeedCurator.rotateDeterministically(fallbackPool, dailySeed + 5L))
                .distinctBy { it.videoId }
                .take(12)
        }
        if (timeSlotTracks.isNotEmpty()) {
            resultShelves.add(
                HomeShelf(
                    title = timeSlot.title,
                    subtitle = timeSlot.subtitle.uppercase(),
                    tracks = timeSlotTracks,
                    isQuickPicks = true
                )
            )
        }

        // 3. "Redescubre" (liked songs not heard in > 30 days)
        val rediscoverTracks = HomeFeedCurator.findRediscoverTracks(
            likedTracks = likedTracks,
            historyRows = historyRows,
            excluded24hIds = excluded24hIds,
            excluded24hDedupeKeys = excluded24hKeys,
            nowMs = nowMs,
            seed = dailySeed,
            limit = 12
        )
        if (rediscoverTracks.isNotEmpty()) {
            resultShelves.add(
                HomeShelf(
                    title = "Redescubre",
                    subtitle = "FAVORITAS QUE HACE TIEMPO NO ESCUCHAS",
                    tracks = rediscoverTracks,
                    isQuickPicks = true
                )
            )
        }

        // 4. "Artistas similares a [artista escuchado]"
        val anchorTrack = allHistoryAndLikedTracks.firstOrNull { !it.artistId.isNullOrBlank() }
            ?: allHistoryAndLikedTracks.firstOrNull()
        val anchorArtistDisplay = anchorTrack?.artist?.substringBefore(",")?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
        if (anchorArtistDisplay != null) {
            val similarCards = mutableListOf<BrowseCard>()
            val similarTracks = mutableListOf<Track>()
            val artistId = anchorTrack.artistId
            if (!artistId.isNullOrBlank()) {
                runCatching { ytMusicClient.getBrowseDetail(artistId) }.onSuccess { detail ->
                    similarCards.addAll(
                        detail.shelves.flatMap { it.items }
                            .filter { it.kind == ItemKind.ARTIST }
                            .distinctBy { it.id }
                            .take(10)
                    )
                }
            }
            if (similarCards.isEmpty()) {
                runCatching {
                    ytMusicClient.search("artistas similares a $anchorArtistDisplay", SearchFilter.ARTISTS).artists
                }.onSuccess { similarCards.addAll(it.take(10)) }
            }
            val relArtists = taste.topRelatedArtistsForSeed(anchorTrack, limit = 2)
            for (rel in relArtists) {
                runCatching {
                    ytMusicClient.search(rel, SearchFilter.SONGS).songs.take(4)
                }.onSuccess { similarTracks.addAll(it) }
            }
            if (similarCards.isNotEmpty() || similarTracks.isNotEmpty()) {
                resultShelves.add(
                    HomeShelf(
                        title = "Artistas similares a $anchorArtistDisplay",
                        subtitle = "DESCUBRIMIENTO",
                        items = HomeFeedCurator.rotateDeterministically(similarCards, dailySeed + 7L),
                        tracks = HomeFeedCurator.rotateDeterministically(similarTracks.distinctBy { it.videoId }, dailySeed + 9L).take(8),
                        isQuickPicks = true
                    )
                )
            }
        }

        // 5. "Álbumes para ti"
        val savedAlbums = runCatching {
            libraryDao.observeSavedCollections().first()
                .map { it.toBrowseCard() }
                .filter { it.kind == ItemKind.ALBUM }
        }.getOrDefault(emptyList())
        val homeAlbumCards = (homeShelves + exploreData?.first.orEmpty())
            .flatMap { it.items }
            .filter { it.kind == ItemKind.ALBUM }
        val combinedAlbums = (savedAlbums + homeAlbumCards).distinctBy { it.id }
        if (combinedAlbums.isNotEmpty()) {
            resultShelves.add(
                HomeShelf(
                    title = "Álbumes para ti",
                    subtitle = "RECOMENDADOS",
                    items = HomeFeedCurator.rotateDeterministically(combinedAlbums, dailySeed + 11L).take(12)
                )
            )
        }

        // 6. "Nuevos lanzamientos"
        val newReleaseShelves = exploreData?.first.orEmpty().filter { s ->
            val t = s.title.lowercase()
            t.contains("nuevo") || t.contains("new") || t.contains("lanzamiento") || t.contains("release")
        }
        val newReleaseCards = newReleaseShelves.flatMap { it.items }.distinctBy { it.id }
        val newReleaseTracks = newReleaseShelves.flatMap { it.tracks }.distinctBy { it.videoId }
        if (newReleaseCards.isNotEmpty() || newReleaseTracks.isNotEmpty()) {
            resultShelves.add(
                HomeShelf(
                    title = "Nuevos lanzamientos",
                    subtitle = "LO MÁS RECIENTE",
                    items = HomeFeedCurator.rotateDeterministically(newReleaseCards, dailySeed + 13L).take(12),
                    tracks = HomeFeedCurator.rotateDeterministically(newReleaseTracks, dailySeed + 15L).take(12),
                    isQuickPicks = newReleaseTracks.isNotEmpty()
                )
            )
        }

        // 7. Base YTM personalized shelves (Basado en..., Porque escuchas a..., Mix de...)
        resultShelves.addAll(basePersonalized)

        HomeFeedCurator.deduplicateHomeShelves(
            shelves = resultShelves.distinctBy { it.title },
            excluded24hIds = excluded24hIds,
            excluded24hDedupeKeys = excluded24hKeys
        )
    }

    suspend fun buildSuggestedSongSet(
        homeShelves: List<HomeShelf>,
        personalizedShelves: List<HomeShelf>,
        recentTracks: List<Track>,
        mostPlayedTracks: List<Track>,
        likedTracks: List<Track>,
        refreshSeed: Int = 0
    ): List<Track> {
        val nowMs = System.currentTimeMillis()
        val dailySeed = HomeFeedCurator.dailyRotationSeed(nowMs, refreshSeed)
        val historyRows = runCatching { libraryDao.getAllListeningHistoryOnce() }.getOrDefault(emptyList())
        val recentRows = runCatching { libraryDao.observeRecentTracks().first() }.getOrDefault(emptyList())
        val (excluded24hIds, excluded24hKeys) = HomeFeedCurator.buildPlayedLast24hExclusion(
            historyRows = historyRows,
            recentRows = recentRows,
            nowMs = nowMs
        )

        val homeTracks = homeShelves.flatMap { it.tracks }
        val personalizedTracks = personalizedShelves.flatMap { it.tracks }
        val expandedTracks = mutableListOf<Track>()

        val poolForSeed = (recentTracks + mostPlayedTracks + likedTracks + personalizedTracks + homeTracks)
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }

        if (poolForSeed.isNotEmpty()) {
            val rotatedSeeds = HomeFeedCurator.rotateDeterministically(poolForSeed, dailySeed)
            val seedTrack = rotatedSeeds.first()
            runCatching {
                val (upNext, _) = getUpNextAndLyricsId(seedTrack.videoId)
                expandedTracks.addAll(upNext)
            }
        }

        if (personalizedTracks.size + homeTracks.size + expandedTracks.size < 12) {
            val playlistCards = homeShelves
                .flatMap { it.items }
                .filter { it.id.isNotBlank() && it.videoId == null }
            if (playlistCards.isNotEmpty()) {
                val card = HomeFeedCurator.rotateDeterministically(playlistCards, dailySeed + 1L).first()
                runCatching {
                    getBrowseDetail(card.id, card.params).onSuccess { detail ->
                        expandedTracks.addAll(detail.tracks.take(12))
                    }
                }
            }
        }

        val candidates = curateSuggestedSongs(
            personalizedTracks = personalizedTracks,
            expandedTracks = expandedTracks,
            homeTracks = homeTracks,
            userHistoryTracks = recentTracks + mostPlayedTracks + likedTracks,
            refreshSeed = dailySeed.toInt(),
            limit = 24
        )
        return candidates.filter { track ->
            val key = QueueBuilder.normalizeDedupeKey(track.title, track.artist)
            track.videoId !in excluded24hIds && (key.isBlank() || key !in excluded24hKeys)
        }.take(16)
    }

    suspend fun getExcludedLast24hForHome(): Pair<Set<String>, Set<String>> {
        val nowMs = System.currentTimeMillis()
        val historyRows = runCatching { libraryDao.getAllListeningHistoryOnce() }.getOrDefault(emptyList())
        val recentRows = runCatching { libraryDao.observeRecentTracks().first() }.getOrDefault(emptyList())
        return HomeFeedCurator.buildPlayedLast24hExclusion(historyRows, recentRows, nowMs)
    }

    companion object {
        private const val HOME_FEED_CACHE_TTL_MS = 30L * 60_000L

        fun curateSuggestedSongs(
            personalizedTracks: List<Track>,
            expandedTracks: List<Track>,
            homeTracks: List<Track>,
            userHistoryTracks: List<Track>,
            refreshSeed: Int = 0,
            limit: Int = 12
        ): List<Track> {
            val knownIds = userHistoryTracks.map { it.videoId }.toSet()
            val combined = (expandedTracks + personalizedTracks + homeTracks)
                .filter { it.videoId.isNotBlank() }
                .distinctBy { it.videoId }

            if (combined.isEmpty()) return emptyList()

            val unseen = combined.filter { it.videoId !in knownIds }
            val familiar = combined.filter { it.videoId in knownIds }
            val ordered = HomeFeedCurator.rotateDeterministically(unseen, refreshSeed.toLong()) +
                HomeFeedCurator.rotateDeterministically(familiar, refreshSeed.toLong() + 7L)

            return ordered.take(limit)
        }

        private val FOREIGN_SCRIPT_REGEX =
            Regex("[\\u0600-\\u06FF\\u0900-\\u0D7F\\u0E00-\\u0E7F\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF\\u0400-\\u04FF]")

        private val COVER_EDIT_TOKENS = listOf(
            "cover", "karaoke", "tributo", "tribute", "homenaje", "nightcore", "8d",
            "slowed", "sped up", "speed up", "bass boosted", "parodia", "parody",
            "tutorial", "ringtone", "piano version", "guitar cover", "ai cover",
            "ia cover", "voice cover"
        )

        private val VIDEO_CLIP_TOKENS = listOf(
            "official video", "official music video", "music video", "video oficial",
            "videoclip", "live at", "en vivo", "concierto", "mtv unplugged"
        )

        fun isVideoHeavyUpload(track: Track): Boolean {
            val lower = track.title.lowercase()
            return VIDEO_CLIP_TOKENS.any { tok -> lower.contains(tok) }
        }

        fun prioritizeAudioOnly(candidates: List<Track>, audioOnlyEnabled: Boolean): List<Track> {
            if (!audioOnlyEnabled || candidates.size <= 1) return candidates
            val studio = candidates.filterNot { isVideoHeavyUpload(it) }
            val video = candidates.filter { isVideoHeavyUpload(it) }
            return if (studio.isNotEmpty()) studio + video else candidates
        }

        fun filterRelatedCleanTracks(seedTrack: Track?, candidates: List<Track>): List<Track> {
            val seedText = "${seedTrack?.title.orEmpty()} ${seedTrack?.artist.orEmpty()}".lowercase()
            val seedHasForeignScript = seedTrack != null &&
                FOREIGN_SCRIPT_REGEX.containsMatchIn(seedText)
            val allowedCoverTokens = COVER_EDIT_TOKENS.filter { tok ->
                Regex("\\b${Regex.escape(tok)}\\b").containsMatchIn(seedText)
            }.toSet()
            val seedBaseTitle = seedTrack?.let { cleanBaseTitleForDedupe(it.title) }.orEmpty()

            val seenTitles = mutableSetOf<String>()
            if (seedTrack != null) {
                seenTitles.add(normalizeTitleKey(seedTrack.title, seedTrack.artist))
            }

            return candidates.filter { track ->
                if (track.videoId.isBlank()) return@filter false
                val combined = "${track.title} ${track.artist}"
                val lower = combined.lowercase()
                if (!seedHasForeignScript && FOREIGN_SCRIPT_REGEX.containsMatchIn(combined)) {
                    return@filter false
                }
                val hasUnwantedVariant = COVER_EDIT_TOKENS.any { tok ->
                    tok !in allowedCoverTokens &&
                        Regex("\\b${Regex.escape(tok)}\\b").containsMatchIn(lower)
                }
                if (hasUnwantedVariant) return@filter false

                // Never queue homonym songs sharing the exact same title as the seed song
                // (e.g., searching "Style - Taylor Swift" must never queue unrelated songs named "Style")
                val candBaseTitle = cleanBaseTitleForDedupe(track.title)
                if (seedBaseTitle.isNotBlank() && candBaseTitle == seedBaseTitle) {
                    return@filter false
                }

                val key = normalizeTitleKey(track.title, track.artist)
                if (key.isNotBlank() && !seenTitles.add(key)) {
                    return@filter false
                }
                true
            }
        }

        fun sharesArtistWithSeed(seedTrack: Track, candidate: Track): Boolean {
            val seedArtists = splitArtistTokens(seedTrack.artist)
            if (seedArtists.isEmpty()) return false
            val candArtists = splitArtistTokens(candidate.artist)
            val candTitleLower = stripDiacritics(candidate.title).lowercase()
            return seedArtists.any { seedArtist ->
                val seedCollapsed = seedArtist.replace(" ", "")
                candArtists.any { cand ->
                    val candCollapsed = cand.replace(" ", "")
                    cand == seedArtist ||
                        cand.contains(seedArtist) ||
                        seedArtist.contains(cand) ||
                        (seedCollapsed.length >= 4 && candCollapsed == seedCollapsed)
                } || Regex("\\b${Regex.escape(seedArtist)}\\b").containsMatchIn(candTitleLower)
            }
        }

        fun curateSmartRelatedQueue(
            seedTrack: Track,
            upNextTracks: List<Track>,
            artistSongs: List<Track>,
            contextSongs: List<Track> = emptyList(),
            limit: Int = 24,
            previouslyShownIds: Set<String> = emptySet(),
            rotationSeed: Long = 0L,
            moodFilter: QueueMoodFilter = QueueMoodFilter.BALANCED,
            lastGenerationIds: Set<String> = emptySet()
        ): List<Track> {
            val cleanContextSameArtist = filterRelatedCleanTracks(
                seedTrack,
                contextSongs.filter {
                    it.videoId != seedTrack.videoId && sharesArtistWithSeed(seedTrack, it)
                }
            )
            val cleanArtistSongs = filterRelatedCleanTracks(
                seedTrack,
                artistSongs.filter { it.videoId != seedTrack.videoId }
            )
            val cleanUpNext = filterRelatedCleanTracks(
                seedTrack,
                upNextTracks.filter { it.videoId != seedTrack.videoId }
            )

            val radioSameArtist = cleanUpNext.filter { sharesArtistWithSeed(seedTrack, it) }
            val radioRelated = cleanUpNext.filterNot { sharesArtistWithSeed(seedTrack, it) }

            val rawArtistPool = (cleanArtistSongs + radioSameArtist + cleanContextSameArtist)
                .distinctBy { it.videoId }
            val rawRelatedPool = radioRelated.distinctBy { it.videoId }

            fun orderPoolWithMemory(pool: List<Track>, seedOffset: Long): List<Track> {
                if (pool.size <= 1) return pool
                val fresh = pool.filter { it.videoId !in previouslyShownIds && it.videoId !in lastGenerationIds }
                val olderSeen = pool.filter { it.videoId in previouslyShownIds && it.videoId !in lastGenerationIds }
                val immediateLast = pool.filter { it.videoId in lastGenerationIds }
                fun sortForMood(items: List<Track>, salt: Long): List<Track> {
                    val moodOrdered = when (moodFilter) {
                        QueueMoodFilter.CHILL -> items.sortedBy {
                            val dur = it.durationMs
                            val titleLow = it.title.lowercase()
                            val chillHint = if (
                                titleLow.contains("acoustic") ||
                                titleLow.contains("acústic") ||
                                titleLow.contains("slow") ||
                                titleLow.contains("ballad") ||
                                titleLow.contains("love")
                            ) -1 else 0
                            chillHint * 10_000L + (if (dur > 0) -dur / 1000L else 0L)
                        }
                        QueueMoodFilter.MORE_ENERGY -> items.sortedBy {
                            val titleLow = it.title.lowercase()
                            val energyHint = if (
                                titleLow.contains("remix") ||
                                titleLow.contains("dance") ||
                                titleLow.contains("club") ||
                                titleLow.contains("party") ||
                                titleLow.contains("mix")
                            ) -1 else 0
                            energyHint * 10_000L + (it.durationMs / 1000L)
                        }
                        else -> items
                    }
                    if (rotationSeed == 0L || moodOrdered.size <= 1) return moodOrdered
                    // Keep top priority loosely blended while rotating genuinely per seed generation
                    val rng = Random(rotationSeed + salt)
                    return if (moodFilter == QueueMoodFilter.BALANCED || moodFilter == QueueMoodFilter.DISCOVER || moodFilter == QueueMoodFilter.SAME_ARTIST) {
                        moodOrdered.shuffled(rng)
                    } else {
                        val topHalf = (moodOrdered.size / 2).coerceAtLeast(2)
                        moodOrdered.take(topHalf).shuffled(rng) + moodOrdered.drop(topHalf).shuffled(Random(rotationSeed + salt + 99L))
                    }
                }
                return sortForMood(fresh, seedOffset) +
                    sortForMood(olderSeen, seedOffset + 409L) +
                    sortForMood(immediateLast, seedOffset + 997L)
            }

            val artistPool = orderPoolWithMemory(rawArtistPool, 17L)
            val relatedPool = orderPoolWithMemory(rawRelatedPool, 53L)

            val curated = mutableListOf(seedTrack)
            val usedIds = mutableSetOf(seedTrack.videoId)
            var aIdx = 0
            var rIdx = 0

            val artistBatch = when (moodFilter) {
                QueueMoodFilter.SAME_ARTIST -> 4
                QueueMoodFilter.DISCOVER -> 1
                else -> 2
            }
            val relatedBatch = when (moodFilter) {
                QueueMoodFilter.DISCOVER -> 3
                QueueMoodFilter.SAME_ARTIST -> 1
                else -> 1
            }

            // Interleave artist hits and radio recommendations so upcoming songs stay cohesive yet fresh
            while (curated.size < limit && (aIdx < artistPool.size || rIdx < relatedPool.size)) {
                repeat(artistBatch) {
                    while (aIdx < artistPool.size && curated.size < limit) {
                        val t = artistPool[aIdx++]
                        if (usedIds.add(t.videoId)) {
                            curated.add(t)
                            break
                        }
                    }
                }
                repeat(relatedBatch) {
                    while (rIdx < relatedPool.size && curated.size < limit) {
                        val t = relatedPool[rIdx++]
                        if (usedIds.add(t.videoId)) {
                            curated.add(t)
                            break
                        }
                    }
                }
            }

            return curated.take(limit)
        }

        private fun stripDiacritics(raw: String): String {
            val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
            return Regex("\\p{InCombiningDiacriticalMarks}+").replace(nfd, "")
        }

        private fun splitArtistTokens(artistRaw: String): List<String> {
            return stripDiacritics(artistRaw)
                .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("vevo$", RegexOption.IGNORE_CASE), "")
                .split(",", "&", " feat.", " feat ", " ft.", " ft ", " x ", " X ", ";", "•")
                .map { it.trim().lowercase() }
                .filter {
                    it.length >= 2 &&
                        it !in setOf("youtube music", "artista desconocido", "various artists", "topic")
                }
        }

        internal fun cleanBaseTitleForDedupe(title: String): String {
            return stripDiacritics(title)
                .lowercase()
                .replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
                .replace(Regex("\\s+-\\s+(remaster(ed)?|live|en vivo|radio edit|version).*$"), "")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun normalizeTitleKey(title: String, artist: String): String {
            val cleanTitle = cleanBaseTitleForDedupe(title)
            val primaryArtist = splitArtistTokens(artist).firstOrNull().orEmpty()
            return if (cleanTitle.isBlank()) "" else "$cleanTitle|$primaryArtist"
        }

        internal const val ALTERNATE_CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        fun groupHistoryEntriesByDay(
            entries: List<ListeningHistoryEntry>,
            zoneId: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(zoneId)
        ): List<HistoryDayGroup> {
            if (entries.isEmpty()) return emptyList()
            val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es", "ES"))
            val yesterday = today.minusDays(1)
            return entries
                .sortedByDescending { it.playedAtMs }
                .groupBy { entry ->
                    Instant.ofEpochMilli(entry.playedAtMs).atZone(zoneId).toLocalDate()
                }
                .entries
                .sortedByDescending { it.key }
                .map { (date, dayEntries) ->
                    val label = when (date) {
                        today -> "Hoy"
                        yesterday -> "Ayer"
                        else -> date.format(dateFormatter).replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale("es", "ES")) else it.toString()
                        }
                    }
                    HistoryDayGroup(
                        dayKey = date.toString(),
                        dayLabel = label,
                        entries = dayEntries
                    )
                }
        }

        fun computeStatsSummaryForHistory(
            entries: List<ListeningHistoryEntry>,
            period: StatsPeriod = StatsPeriod.ALL,
            zoneId: ZoneId = ZoneId.systemDefault(),
            nowMs: Long = System.currentTimeMillis()
        ): ListeningStatsSummary {
            val cutoffMs = when (period) {
                StatsPeriod.WEEK -> nowMs - 7L * 24L * 60L * 60L * 1000L
                StatsPeriod.MONTH -> nowMs - 30L * 24L * 60L * 60L * 1000L
                StatsPeriod.YEAR -> nowMs - 365L * 24L * 60L * 60L * 1000L
                StatsPeriod.ALL -> 0L
            }
            val filtered = if (cutoffMs > 0L) entries.filter { it.playedAtMs >= cutoffMs } else entries
            if (filtered.isEmpty()) {
                return ListeningStatsSummary(period = period)
            }

            fun effectiveMs(e: ListeningHistoryEntry): Long {
                if (e.listenedMs > 0L) return e.listenedMs
                return e.track.durationMs.takeIf { it > 0L } ?: 210_000L
            }

            val totalListenedMs = filtered.sumOf(::effectiveMs)
            val totalMinutes = totalListenedMs / 60_000L
            val uniqueTracks = filtered.map { it.track.videoId }.distinct().size

            val topTracks = filtered
                .groupBy { it.track.videoId }
                .values
                .map { group ->
                    TrackPlayStat(track = group.first().track, playCount = group.size)
                }
                .sortedByDescending { it.playCount }
                .take(10)

            val artistGroups = filtered.groupBy { entry ->
                splitArtistNames(entry.track.artist).firstOrNull()?.ifBlank { null }
                    ?: entry.track.artist.ifBlank { "Artista desconocido" }
            }
            val topArtists = artistGroups.map { (artistName, items) ->
                ArtistPlayStat(
                    artist = artistName,
                    totalPlays = items.size,
                    estimatedMinutes = items.sumOf(::effectiveMs) / 60_000L
                )
            }
                .sortedByDescending { it.totalPlays }
                .take(8)

            val topAlbums = filtered
                .filter { !it.track.album.isNullOrBlank() }
                .groupBy { it.track.album!!.trim() }
                .map { (albumName, items) ->
                    AlbumPlayStat(
                        album = albumName,
                        albumId = items.firstNotNullOfOrNull { it.track.albumId },
                        artworkUrl = items.firstOrNull { it.track.artworkUrl.isNotBlank() }?.track?.artworkUrl.orEmpty(),
                        totalPlays = items.size,
                        estimatedMinutes = items.sumOf(::effectiveMs) / 60_000L
                    )
                }
                .sortedByDescending { it.totalPlays }
                .take(8)

            val zonedTimes = filtered.map { Instant.ofEpochMilli(it.playedAtMs).atZone(zoneId) }
            val peakHour = zonedTimes
                .groupingBy { it.hour }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
            val peakDayOfWeek = zonedTimes
                .groupingBy { it.dayOfWeek.value }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key

            val distinctDates = zonedTimes.map { it.toLocalDate() }.distinct().sorted()
            var longestStreak = 0
            var runningStreak = 0
            var prevDate: LocalDate? = null
            distinctDates.forEach { d ->
                runningStreak = if (prevDate != null && prevDate!!.plusDays(1) == d) runningStreak + 1 else 1
                if (runningStreak > longestStreak) longestStreak = runningStreak
                prevDate = d
            }
            val dateSet = distinctDates.toHashSet()
            val todayDate = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
            var currentStreak = 0
            var cursor: LocalDate? = when {
                todayDate in dateSet -> todayDate
                todayDate.minusDays(1) in dateSet -> todayDate.minusDays(1)
                else -> null
            }
            while (cursor != null && cursor in dateSet) {
                currentStreak++
                cursor = cursor.minusDays(1)
            }

            val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale("es", "ES"))
            val groupedByMonth = filtered.groupBy { entry ->
                YearMonth.from(Instant.ofEpochMilli(entry.playedAtMs).atZone(zoneId))
            }
            val endMonth = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zoneId))
            val minDataMonth = groupedByMonth.keys.minOrNull() ?: endMonth
            val monthWindowCount = when (period) {
                StatsPeriod.YEAR, StatsPeriod.ALL -> {
                    val monthsBetween = ((endMonth.year - minDataMonth.year) * 12 + (endMonth.monthValue - minDataMonth.monthValue) + 1)
                        .coerceIn(6, 12)
                    monthsBetween
                }
                StatsPeriod.WEEK, StatsPeriod.MONTH -> 6
            }
            val startMonth = endMonth.minusMonths((monthWindowCount - 1).toLong())
            val monthlyHours = (0 until monthWindowCount).map { offset ->
                val ym = startMonth.plusMonths(offset.toLong())
                val monthEntries = groupedByMonth[ym].orEmpty()
                val mins = monthEntries.sumOf(::effectiveMs) / 60_000L
                val rawLabel = ym.format(monthFormatter).replace(".", "").replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale("es", "ES")) else it.toString()
                }
                MonthlyListeningStat(
                    yearMonthKey = ym.toString(),
                    label = rawLabel,
                    hours = mins / 60f,
                    minutes = mins,
                    playCount = monthEntries.size
                )
            }

            return ListeningStatsSummary(
                totalPlays = filtered.size,
                uniqueTracks = uniqueTracks,
                estimatedMinutes = totalMinutes,
                topTracks = topTracks,
                topArtists = topArtists,
                totalListenedMs = totalListenedMs,
                topAlbums = topAlbums,
                peakHour = peakHour,
                peakDayOfWeek = peakDayOfWeek,
                currentStreakDays = currentStreak,
                longestStreakDays = longestStreak,
                monthlyHours = monthlyHours,
                period = period
            )
        }
    }

    suspend fun getExploreData(): Result<Pair<List<HomeShelf>, List<MoodCategory>>> = runCatching {
        ytMusicClient.getExploreData()
    }

    suspend fun getSearchSuggestions(query: String): List<String> {
        val npSuggestions = newPipeSearchClient?.let { client ->
            runCatching { client.getSearchSuggestions(query) }.getOrDefault(emptyList())
        }.orEmpty()
        if (npSuggestions.isNotEmpty()) return npSuggestions
        return runCatching { ytMusicClient.getSearchSuggestions(query) }.getOrDefault(emptyList())
    }

    suspend fun search(query: String, filter: SearchFilter): Result<SearchResults> = runCatching {
        if (query.isNotBlank()) {
            libraryDao.insertSearchHistory(SearchHistoryEntity(query = query.trim()))
        }
        val npResults = newPipeSearchClient?.let { client ->
            runCatching { client.search(query, filter) }.getOrNull()
        }

        val hasAnyNewPipeItem = npResults != null && (
            npResults.songs.isNotEmpty() ||
                npResults.albums.isNotEmpty() ||
                npResults.artists.isNotEmpty() ||
                npResults.playlists.isNotEmpty()
            )
        if (hasAnyNewPipeItem) {
            npResults!!
        } else {
            ytMusicClient.search(query, filter).copy(sourceLabel = "YouTube Music")
        }
    }

    suspend fun getBrowseDetail(browseId: String, params: String? = null): Result<BrowseDetail> = runCatching {
        ytMusicClient.getBrowseDetail(browseId, params)
    }

    suspend fun getUpNextAndLyricsId(videoId: String): Pair<List<Track>, String?> {
        return runCatching { ytMusicClient.getUpNextAndLyricsBrowseId(videoId) }
            .getOrDefault(emptyList<Track>() to null)
    }

    suspend fun buildSmartRelatedQueueWithLyrics(
        seedTrack: Track,
        contextSongs: List<Track> = emptyList(),
        moodFilter: QueueMoodFilter = QueueMoodFilter.BALANCED
    ): Pair<List<Track>, String?> = coroutineScope {
        val seedHistory = runCatching {
            libraryDao.getQueueSeedHistory(seedTrack.videoId)
        }.getOrDefault(emptyList())
        val previouslyShownIds = seedHistory.map { it.suggestedVideoId }.toSet()
        val lastGeneration = seedHistory.maxOfOrNull { it.generation } ?: 0
        val lastGenerationIds = if (lastGeneration > 0) {
            seedHistory.filter { it.generation == lastGeneration }.map { it.suggestedVideoId }.toSet()
        } else {
            emptySet()
        }
        val nextGeneration = lastGeneration + 1
        val primaryArtist = splitArtistTokens(seedTrack.artist).firstOrNull().orEmpty()
        val cleanTitle = cleanBaseTitleForDedupe(seedTrack.title)

        val tasteDeferred = async { loadTasteProfile() }
        val upNextDeferred = async {
            runCatching { ytMusicClient.getUpNextAndLyricsBrowseId(seedTrack.videoId) }
                .getOrDefault(emptyList<Track>() to null)
        }
        val artistDetailDeferred = async {
            if (!seedTrack.artistId.isNullOrBlank()) {
                runCatching { ytMusicClient.getBrowseDetail(seedTrack.artistId) }.getOrNull()
            } else {
                null
            }
        }
        val artistSongsDeferred = async {
            val fromArtistPage = artistDetailDeferred.await()?.tracks.orEmpty()
            val fromArtistSearch = if (primaryArtist.isNotBlank()) {
                runCatching { ytMusicClient.search(primaryArtist, SearchFilter.SONGS).songs }
                    .getOrDefault(emptyList())
                    .filter { sharesArtistWithSeed(seedTrack, it) }
            } else {
                emptyList()
            }
            // On repeat listens (nextGeneration > 1), query deeper rotating catalog slices
            val deeperCatalog = if (nextGeneration > 1) {
                val querySubject = primaryArtist.ifBlank { cleanTitle }
                val variants = listOf(
                    "$querySubject esenciales",
                    "$querySubject mejores canciones",
                    "$querySubject exitos",
                    "$querySubject mix radio",
                    "$querySubject colaboraciones"
                )
                val extraQuery = variants[(nextGeneration - 1) % variants.size]
                runCatching { ytMusicClient.search(extraQuery, SearchFilter.SONGS).songs }
                    .getOrDefault(emptyList())
                    .let { list ->
                        if (primaryArtist.isNotBlank()) list.filter { sharesArtistWithSeed(seedTrack, it) } else list
                    }
            } else {
                emptyList()
            }
            val combined = (fromArtistPage + fromArtistSearch + deeperCatalog).distinctBy { it.videoId }
            if (combined.size < 8 && primaryArtist.isNotBlank() && newPipeSearchClient != null) {
                val fromNewPipe = runCatching {
                    newPipeSearchClient.search(primaryArtist, SearchFilter.SONGS).songs
                }.getOrDefault(emptyList())
                    .filter { sharesArtistWithSeed(seedTrack, it) }
                (combined + fromNewPipe).distinctBy { it.videoId }
            } else {
                combined
            }
        }

        val tasteProfile = tasteDeferred.await()
        val artistDetail = artistDetailDeferred.await()
        val pageRelatedArtistNames = artistDetail?.shelves.orEmpty()
            .flatMap { it.items }
            .filter { it.kind == ItemKind.ARTIST && it.title.isNotBlank() }
            .map { it.title }
        val tasteRelatedArtists = tasteProfile.topRelatedArtistsForSeed(seedTrack, limit = 4)
        val combinedRelatedArtists = (tasteRelatedArtists + pageRelatedArtistNames)
            .map { QueueBuilder.stripDiacritics(it).trim().lowercase() }
            .filter { it.length >= 2 && it != primaryArtist }
            .distinct()
            .take(6)

        val relatedArtistTracksDeferred = async {
            combinedRelatedArtists.take(2).map { relArtist ->
                async {
                    runCatching {
                        ytMusicClient.search(relArtist, SearchFilter.SONGS).songs
                            .filter { track ->
                                splitArtistTokens(track.artist).any { tok -> tok == relArtist || tok.contains(relArtist) }
                            }
                            .take(6)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.videoId }
        }

        val (upNext, lyricsBrowseId) = upNextDeferred.await()
        val artistSongs = artistSongsDeferred.await()
        val relatedArtistTracks = relatedArtistTracksDeferred.await()

        // On repeat listens, branch UpNext from up to 2 distinct radio candidates (preferring unseen tracks)
        val chainedUpNext = if (nextGeneration > 1) {
            val pool = (upNext + artistSongs)
                .filter { it.videoId != seedTrack.videoId && it.videoId.isNotBlank() }
                .distinctBy { it.videoId }
            val unseenBranches = pool.filter { it.videoId !in previouslyShownIds }.take(2)
            val branches = if (unseenBranches.isNotEmpty()) {
                unseenBranches
            } else if (pool.isNotEmpty()) {
                val idxA = nextGeneration % pool.size
                val idxB = (nextGeneration + pool.size / 2) % pool.size
                listOfNotNull(pool.getOrNull(idxA), pool.getOrNull(idxB)).distinctBy { it.videoId }
            } else {
                emptyList()
            }
            branches.map { branch ->
                async {
                    runCatching { ytMusicClient.getUpNextAndLyricsBrowseId(branch.videoId).first }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        } else {
            emptyList()
        }

        val audioPref = audioOnlyPreference
        val rotationSeed = System.currentTimeMillis() xor (nextGeneration.toLong() * 2654435761L)
        val combinedUpNext = (upNext + chainedUpNext).distinctBy { it.videoId }

        val curatedTail = QueueBuilder.buildCohesiveQueue(
            QueueBuilder.CohesiveQueueInput(
                seedTrack = seedTrack,
                recentContextTracks = contextSongs,
                upNextTracks = prioritizeAudioOnly(combinedUpNext, audioPref),
                artistSongs = prioritizeAudioOnly(artistSongs, audioPref),
                relatedArtistTracks = prioritizeAudioOnly(relatedArtistTracks, audioPref),
                tasteProfile = tasteProfile,
                seedRelatedArtists = combinedRelatedArtists.toSet(),
                likedVideoIds = tasteProfile.likedVideoIds,
                likedArtists = tasteProfile.likedArtists,
                historyVideoIds = tasteProfile.historyVideoIds,
                historyArtists = tasteProfile.historyArtists,
                lastHourVideoIds = tasteProfile.recent1HourVideoIds,
                lastHourDedupeKeys = tasteProfile.recent1HourDedupeKeys,
                previouslyShownIds = previouslyShownIds,
                lastGenerationIds = lastGenerationIds,
                moodFilter = moodFilter,
                audioOnlyPreference = audioPref,
                rotationSeed = rotationSeed,
                limit = 23,
                minAffinityScore = 10
            )
        )
        val curated = (listOf(seedTrack) + curatedTail).distinctBy { it.videoId }

        val now = System.currentTimeMillis()
        val shownEntries = curated.drop(1).mapIndexed { idx, track ->
            QueueSeedHistoryEntity(
                seedVideoId = seedTrack.videoId,
                suggestedVideoId = track.videoId,
                generation = nextGeneration,
                shownAtMs = now - idx
            )
        }
        if (shownEntries.isNotEmpty()) {
            runCatching {
                libraryDao.upsertQueueSeedHistory(shownEntries)
                libraryDao.trimQueueSeedHistory(seedTrack.videoId, 120)
            }
        }

        curated to lyricsBrowseId
    }

    suspend fun loadTasteProfile(): TasteProfile {
        val localLiked = runCatching { libraryDao.getAllLikedSongsOnce().map { it.toTrack() } }.getOrDefault(emptyList())
        val ytLiked = runCatching { libraryDao.getYtLikedSongsOnce().map { it.toTrack() } }.getOrDefault(emptyList())
        val historyRows = runCatching { libraryDao.getAllListeningHistoryOnce() }.getOrDefault(emptyList())
        val recentEntities = runCatching { libraryDao.observeRecentTracks().first() }.getOrDefault(emptyList())
        val hasAccount = runCatching { libraryDao.getAccountSession()?.cookies?.isNotBlank() == true }.getOrDefault(false)
        val ytHistoryTracks = if (hasAccount) {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(1_500L) { ytMusicClient.getYouTubeHistory() } ?: emptyList()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return TasteProfileBuilder.build(
            historyRows = historyRows,
            likedTracks = (localLiked + ytLiked).distinctBy { it.videoId },
            ytHistoryTracks = ytHistoryTracks,
            recentTrackEntities = recentEntities
        )
    }

    suspend fun retuneUpcomingQueue(
        seedTrack: Track,
        existingTail: List<Track>,
        moodFilter: QueueMoodFilter
    ): List<Track> {
        val (freshCurated, _) = buildSmartRelatedQueueWithLyrics(
            seedTrack = seedTrack,
            contextSongs = existingTail,
            moodFilter = moodFilter
        )
        return freshCurated.drop(1)
    }

    suspend fun buildTailExtensionTracks(
        seedTrack: Track,
        existingQueue: List<Track>,
        limit: Int = 15
    ): List<Track> = coroutineScope {
        val tasteProfile = loadTasteProfile()
        val recentSeeds = (existingQueue.takeLast(4) + tasteProfile.recentTracks.take(4))
            .filter { it.videoId.isNotBlank() && it.videoId != seedTrack.videoId && !it.isLocal }
            .distinctBy { it.videoId }
            .take(4)

        val extraSeedUpNext = recentSeeds.take(2).map { ctxSeed ->
            async {
                runCatching { ytMusicClient.getUpNextAndLyricsBrowseId(ctxSeed.videoId).first }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        val primaryUpNextDeferred = async {
            runCatching { ytMusicClient.getUpNextAndLyricsBrowseId(seedTrack.videoId).first }
                .getOrDefault(emptyList())
        }
        val primaryArtist = splitArtistTokens(seedTrack.artist).firstOrNull().orEmpty()
        val artistSongsDeferred = async {
            val fromPage = if (!seedTrack.artistId.isNullOrBlank()) {
                runCatching { ytMusicClient.getBrowseDetail(seedTrack.artistId).tracks }.getOrDefault(emptyList())
            } else emptyList()
            val fromSearch = if (primaryArtist.isNotBlank()) {
                runCatching { ytMusicClient.search(primaryArtist, SearchFilter.SONGS).songs }
                    .getOrDefault(emptyList())
                    .filter { sharesArtistWithSeed(seedTrack, it) }
            } else emptyList()
            (fromPage + fromSearch).distinctBy { it.videoId }
        }
        val relatedArtists = tasteProfile.topRelatedArtistsForSeed(seedTrack, limit = 3)
        val relatedArtistTracksDeferred = async {
            relatedArtists.take(2).map { relArtist ->
                async {
                    runCatching {
                        ytMusicClient.search(relArtist, SearchFilter.SONGS).songs
                            .filter { track ->
                                splitArtistTokens(track.artist).any { tok -> tok == relArtist || tok.contains(relArtist) }
                            }
                            .take(5)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.videoId }
        }

        val primaryUpNext = primaryUpNextDeferred.await()
        val artistSongs = artistSongsDeferred.await()
        val relatedArtistTracks = relatedArtistTracksDeferred.await()
        val seedHistory = runCatching { libraryDao.getQueueSeedHistory(seedTrack.videoId) }.getOrDefault(emptyList())

        QueueBuilder.buildCohesiveQueue(
            QueueBuilder.CohesiveQueueInput(
                seedTrack = seedTrack,
                recentContextTracks = recentSeeds,
                upNextTracks = prioritizeAudioOnly(primaryUpNext, audioOnlyPreference),
                artistSongs = prioritizeAudioOnly(artistSongs, audioOnlyPreference),
                relatedArtistTracks = prioritizeAudioOnly(relatedArtistTracks, audioOnlyPreference),
                extraSeedUpNextTracks = prioritizeAudioOnly(extraSeedUpNext, audioOnlyPreference),
                existingQueue = existingQueue,
                tasteProfile = tasteProfile,
                seedRelatedArtists = relatedArtists.toSet(),
                likedVideoIds = tasteProfile.likedVideoIds,
                likedArtists = tasteProfile.likedArtists,
                historyVideoIds = tasteProfile.historyVideoIds,
                historyArtists = tasteProfile.historyArtists,
                lastHourVideoIds = tasteProfile.recent1HourVideoIds,
                lastHourDedupeKeys = tasteProfile.recent1HourDedupeKeys,
                previouslyShownIds = seedHistory.map { it.suggestedVideoId }.toSet(),
                audioOnlyPreference = audioOnlyPreference,
                rotationSeed = System.currentTimeMillis(),
                limit = limit,
                minAffinityScore = 10
            )
        )
    }

    suspend fun getLyrics(lyricsBrowseId: String): String? {
        return runCatching { ytMusicClient.getLyrics(lyricsBrowseId) }.getOrNull()
    }

    suspend fun getTrackLyrics(track: Track, lyricsBrowseId: String?): TrackLyrics? {
        val resolver = lyricsResolver ?: return lyricsBrowseId?.let { id ->
            val plain = getLyrics(id) ?: return null
            val distributed = LyricsResolver.distributePlainLyrics(plain, track.durationMs)
            if (distributed.isEmpty()) null else TrackLyrics(distributed, isSynced = false, sourceLabel = "YouTube Music")
        }
        return resolver.resolveLyrics(track) {
            if (!lyricsBrowseId.isNullOrBlank()) getLyrics(lyricsBrowseId) else null
        }
    }

    suspend fun reloadLyrics(track: Track, lyricsBrowseId: String?): TrackLyrics? {
        lyricsResolver?.invalidate(track.videoId)
        return getTrackLyrics(track, lyricsBrowseId)
    }

    suspend fun warmupResolvers() {
        streamResolver.warmup()
    }

    suspend fun resolveStream(videoId: String): Result<ResolvedStream> {
        return streamResolver.resolveAudioStream(videoId)
    }

    fun evictAlternateVideoCache(originalVideoId: String) {
        if (originalVideoId.isNotBlank()) {
            alternateVideoCache.remove(originalVideoId)
        }
    }

    internal fun getCachedAlternateVideoId(originalVideoId: String): String? {
        if (originalVideoId.isBlank()) return null
        val entry = alternateVideoCache[originalVideoId] ?: return null
        val ageMs = alternateClockMs() - entry.cachedAtMs
        if (ageMs < 0L || ageMs >= ALTERNATE_CACHE_TTL_MS || entry.alternateVideoId == originalVideoId) {
            alternateVideoCache.remove(originalVideoId, entry)
            return null
        }
        return entry.alternateVideoId
    }

    internal fun cacheAlternateVideoId(originalVideoId: String, alternateVideoId: String) {
        if (originalVideoId.isBlank() || alternateVideoId.isBlank() || originalVideoId == alternateVideoId) return
        alternateVideoCache[originalVideoId] = CachedAlternateVideo(
            alternateVideoId = alternateVideoId,
            cachedAtMs = alternateClockMs()
        )
    }

    internal fun scoreAlternateCandidate(
        originalTrack: Track,
        candidate: Track,
        fromSongsFilter: Boolean
    ): ScoredAlternateCandidate? {
        if (candidate.videoId.isBlank() || candidate.videoId == originalTrack.videoId) return null

        val cleanTargetTitle = CatalogAudioFallback.stripDecorations(originalTrack.title)
            .ifBlank { originalTrack.title }
            .trim()
        val isShortTitle = cleanTargetTitle.count { it.isLetter() } <= 8

        val targetArtistsNorm = extractCleanArtistList(originalTrack.artist)
        val candArtistNormList = extractCleanArtistList(candidate.artist)
        val candArtistFullNorm = CatalogAudioFallback.normalizeForMatch(
            candidate.artist
                .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("vevo$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(official|oficial)$", RegexOption.IGNORE_CASE), "")
        )
        val candTitleFullNorm = CatalogAudioFallback.normalizeForMatch(candidate.title)

        // Check exact clean artist match in candidate.artist
        val artistMatchesDirect = targetArtistsNorm.any { targetArt ->
            candArtistNormList.any { candArt ->
                candArt == targetArt ||
                    (candArt.length >= 4 && targetArt.length >= 4 && candArt.replace(" ", "") == targetArt.replace(" ", ""))
            } || candArtistFullNorm == targetArt
        }

        // Handle "Artist - Title (Official Audio / Lyrics)" YouTube uploads cleanly
        var effectiveCandTitle = candidate.title
        var titleMatchedArtist = false
        val dashIdx = candidate.title.indexOf(" - ").takeIf { it > 1 }
            ?: candidate.title.indexOf(" – ").takeIf { it > 1 }
        if (dashIdx != null && dashIdx < candidate.title.length - 3) {
            val leftPart = candidate.title.substring(0, dashIdx).trim()
            val rightPart = candidate.title.substring(dashIdx + 3).trim()
            val leftArtists = extractCleanArtistList(leftPart)
            val leftNorm = CatalogAudioFallback.normalizeForMatch(leftPart)
            val leftMatchesTarget = targetArtistsNorm.any { targetArt ->
                leftNorm == targetArt || leftArtists.any { la -> la == targetArt }
            }
            if (leftMatchesTarget && rightPart.isNotBlank()) {
                effectiveCandTitle = rightPart
                titleMatchedArtist = true
            }
        } else if (!artistMatchesDirect && targetArtistsNorm.isNotEmpty()) {
            val paddedCandTitle = " $candTitleFullNorm "
            titleMatchedArtist = targetArtistsNorm.any { targetArt ->
                targetArt.length >= 4 && paddedCandTitle.contains(" $targetArt ")
            }
        }

        // Strict short-title exact-artist guard (e.g., "Lovers" by "Anna of the North")
        if (isShortTitle && targetArtistsNorm.isNotEmpty()) {
            val isExplicitLyricOrAudioUpload = candTitleFullNorm.contains("lyric") ||
                candTitleFullNorm.contains("letra") ||
                candTitleFullNorm.contains("audio") ||
                candTitleFullNorm.contains("official")
            val artistGuardPassed = artistMatchesDirect || (titleMatchedArtist && isExplicitLyricOrAudioUpload)
            if (!artistGuardPassed) {
                return null
            }
        }

        // Score using CatalogAudioFallback with cleaned candidate title + normalized effective artist if "Artist - Title" upload
        val effectiveArtistForScore = when {
            artistMatchesDirect -> candidate.artist
            titleMatchedArtist && (candTitleFullNorm.contains("lyric") || candTitleFullNorm.contains("letra") ||
                candTitleFullNorm.contains("audio") || candTitleFullNorm.contains("official") ||
                candTitleFullNorm.contains("video")) -> {
                targetArtistsNorm.firstOrNull() ?: originalTrack.artist
            }
            else -> candidate.artist
        }

        var baseScore = CatalogAudioFallback.scoreCatalogCandidate(
            targetTitle = originalTrack.title,
            targetArtist = originalTrack.artist,
            targetDurationMs = originalTrack.durationMs,
            candTitle = effectiveCandTitle,
            candArtist = effectiveArtistForScore,
            candDurationSec = (candidate.durationMs / 1000L).toInt(),
            isVerified = true
        )
        if (baseScore == Int.MIN_VALUE) return null

        // Give slightly higher confidence to direct artist channel vs third-party lyric upload
        if (!artistMatchesDirect && titleMatchedArtist) {
            baseScore -= 14
        }

        val durDeltaMs = if (originalTrack.durationMs > 0L && candidate.durationMs > 0L) {
            abs(originalTrack.durationMs - candidate.durationMs)
        } else {
            Long.MAX_VALUE
        }
        val minRequiredScore = if (artistMatchesDirect && durDeltaMs <= 8_000L) 110 else 115
        if (baseScore < minRequiredScore) return null

        val candLower = candidate.title.lowercase()
        val isLyric = candLower.contains("lyric") || candLower.contains("lyrics") || candLower.contains("letra")
        val isVideoClip = isVideoHeavyUpload(candidate) || candLower.contains("video")
        val isOfficialAudio = candLower.contains("official audio") ||
            candLower.contains("audio oficial") ||
            (!isLyric && !isVideoClip && (candLower.contains("audio") || candidate.artist.contains("topic", ignoreCase = true)))

        val tier = when {
            fromSongsFilter && !isVideoClip && !isLyric -> AlternatePriorityTier.YTM_SONG
            isOfficialAudio || (!isLyric && !isVideoClip && artistMatchesDirect) -> AlternatePriorityTier.OFFICIAL_AUDIO
            isLyric -> AlternatePriorityTier.LYRIC_VIDEO
            else -> AlternatePriorityTier.OFFICIAL_VIDEO
        }

        val adjustedScore = if (audioOnlyPreference && isVideoHeavyUpload(candidate)) baseScore - 12 else baseScore
        if (adjustedScore < minRequiredScore) return null

        return ScoredAlternateCandidate(
            track = candidate,
            score = adjustedScore,
            tier = tier
        )
    }

    private fun extractCleanArtistList(rawArtist: String): List<String> {
        val primarySeg = rawArtist
            .split("•", "·")
            .map { it.trim() }
            .firstOrNull { seg ->
                val low = seg.lowercase()
                seg.isNotBlank() &&
                    low !in setOf("song", "canción", "cancion", "video", "album", "álbum", "playlist", "single", "sencillo", "ep") &&
                    !low.contains("views") && !low.contains("vistas") && !low.contains("plays") &&
                    !Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$").matches(seg)
            } ?: rawArtist
        return primarySeg
            .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("vevo$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(official|oficial)$", RegexOption.IGNORE_CASE), "")
            .split(",", "&", " feat.", " feat ", " ft.", " ft ", " x ", " X ", ";", "/")
            .map { CatalogAudioFallback.normalizeForMatch(it) }
            .filter { it.isNotBlank() && it !in setOf("youtube music", "artista desconocido", "various artists", "topic") }
            .distinct()
    }

    internal fun isMusicVideoSource(track: Track): Boolean {
        if (isVideoHeavyUpload(track)) return true
        val tLow = track.title.lowercase()
        val aLow = track.artist.lowercase()
        return tLow.contains("official video") ||
            tLow.contains("music video") ||
            tLow.contains("video oficial") ||
            tLow.contains("(mv)") ||
            tLow.contains("[mv]") ||
            tLow.contains(" m/v") ||
            aLow.endsWith("vevo")
    }

    private suspend fun probeRankedAlternates(
        originalTrack: Track,
        rankedCandidates: List<ScoredAlternateCandidate>,
        allowHls: Boolean
    ): Pair<ScoredAlternateCandidate, ResolvedStream>? {
        if (rankedCandidates.isEmpty()) return null
        rankedCandidates.forEach { entry ->
            PlaybackDiagnostics.recordAlternateEvent(
                originalTrack.videoId,
                "Candidato ${entry.track.videoId} '${entry.track.title}' (${entry.track.artist}) [${entry.tier.label}] score=${entry.score}"
            )
            Log.i(
                "Sonora",
                "AltStream candidate orig=${originalTrack.videoId} cand=${entry.track.videoId} tier=${entry.tier.label} score=${entry.score} title='${entry.track.title}'"
            )
        }

        return withTimeoutOrNull(6_000L) {
            coroutineScope {
                val jobs = rankedCandidates.map { entry ->
                    entry to async {
                        val stream = streamResolver.resolveAudioStreamFast(entry.track, allowHls = allowHls)
                            .getOrNull()
                            ?.copy(sourceVideoId = entry.track.videoId)
                        PlaybackDiagnostics.recordAlternateEvent(
                            originalTrack.videoId,
                            "Probe ${entry.track.videoId} (score=${entry.score}): ${if (stream != null) "PLAYABLE (${stream.codecLabel})" else "FAILED"}"
                        )
                        stream
                    }
                }
                var picked: Pair<ScoredAlternateCandidate, ResolvedStream>? = null
                for ((entry, deferred) in jobs) {
                    val resolved = runCatching { deferred.await() }.getOrNull()
                    if (resolved != null) {
                        picked = entry to resolved
                        break
                    }
                }
                jobs.forEach { (_, job) ->
                    if (!job.isCompleted) job.cancel()
                }
                picked
            }
        }
    }

    private fun rankAlternatePool(
        originalTrack: Track,
        candidates: List<Pair<Track, Boolean>>,
        excludeVideoIds: Set<String>
    ): List<ScoredAlternateCandidate> {
        val bestByVideoId = linkedMapOf<String, ScoredAlternateCandidate>()
        for ((cand, fromSongs) in candidates) {
            if (cand.videoId.isBlank() || cand.videoId == originalTrack.videoId || cand.videoId in excludeVideoIds) {
                continue
            }
            val scored = scoreAlternateCandidate(originalTrack, cand, fromSongsFilter = fromSongs) ?: continue
            val existing = bestByVideoId[cand.videoId]
            if (existing == null ||
                scored.tier.rank > existing.tier.rank ||
                (scored.tier.rank == existing.tier.rank && scored.score > existing.score)
            ) {
                bestByVideoId[cand.videoId] = scored
            }
        }
        return bestByVideoId.values
            .sortedWith(compareByDescending<ScoredAlternateCandidate> { it.tier.rank }.thenByDescending { it.score })
            .take(5)
    }

    suspend fun findAlternatePlayableStream(
        originalTrack: Track,
        excludeVideoIds: Set<String> = emptySet(),
        allowHls: Boolean = true
    ): ResolvedStream? {
        PlaybackDiagnostics.updateTrackInfo(originalTrack.videoId, originalTrack.title, originalTrack.artist)

        // 1. Check 24h in-memory alternate-video cache first
        getCachedAlternateVideoId(originalTrack.videoId)
            ?.takeIf { it !in excludeVideoIds }
            ?.let { cachedAltId ->
                Log.i("Sonora", "AltStream cache hit original=${originalTrack.videoId} alt=$cachedAltId")
                PlaybackDiagnostics.recordAlternateEvent(originalTrack.videoId, "Caché 24h hit: $cachedAltId")
                val cachedTrack = originalTrack.copy(videoId = cachedAltId)
                val resolved = streamResolver.resolveAudioStreamFast(cachedTrack, allowHls = allowHls)
                    .getOrNull()
                    ?.copy(sourceVideoId = cachedAltId)
                if (resolved != null) {
                    PlaybackDiagnostics.recordAlternateEvent(originalTrack.videoId, "Probe caché $cachedAltId: PLAYABLE")
                    streamResolver.recordResolvedAlternate(originalTrack.videoId, cachedAltId)
                    return resolved
                }
                PlaybackDiagnostics.recordAlternateEvent(originalTrack.videoId, "Probe caché $cachedAltId: FAILED (descartado)")
                Log.w("Sonora", "AltStream cached alt failed; evicting original=${originalTrack.videoId} alt=$cachedAltId")
                evictAlternateVideoCache(originalTrack.videoId)
            }

        val cleanTitle = CatalogAudioFallback.stripDecorations(originalTrack.title)
            .ifBlank { originalTrack.title }
            .trim()
        if (cleanTitle.isBlank()) return null

        val primaryArtist = originalTrack.artist
            .split("•", "·", ",", "&", " feat.", " feat ", " ft.", " ft ")
            .firstOrNull()
            ?.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("youtube music", "artista desconocido", "various artists", "topic") }
            .orEmpty()

        val baseQuery = listOf(primaryArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ").trim()
        val audioQuery = "$baseQuery audio".trim()
        val lyricsQuery = "$baseQuery lyrics".trim()
        val isMvOriginal = isMusicVideoSource(originalTrack)

        Log.i("Sonora", "AltStream lookup start original=${originalTrack.videoId} title='$cleanTitle' mv=$isMvOriginal")

        val testedIds = excludeVideoIds.toMutableSet()

        // 2. If original is a Music Video, search for the YTM "Song" version FIRST
        if (isMvOriginal) {
            PlaybackDiagnostics.recordAlternateEvent(
                originalTrack.videoId,
                "Original detectado como video musical -> buscando versión Song primero"
            )
            val songFirstCandidates = withTimeoutOrNull(3_000L) {
                coroutineScope {
                    val ytmSongsJob = async {
                        runCatching { ytMusicClient.search(baseQuery, SearchFilter.SONGS).songs }
                            .getOrDefault(emptyList())
                            .map { it to true }
                    }
                    val newPipeJob = async {
                        newPipeSearchClient?.let { client ->
                            runCatching { client.search(baseQuery, SearchFilter.SONGS).songs }
                                .getOrDefault(emptyList())
                                .map { it to true }
                        }.orEmpty()
                    }
                    ytmSongsJob.await() + newPipeJob.await()
                }
            }.orEmpty()

            val songRanked = rankAlternatePool(originalTrack, songFirstCandidates, testedIds)
            val songWinner = probeRankedAlternates(originalTrack, songRanked, allowHls)
            if (songWinner != null) {
                val (winningEntry, winningStream) = songWinner
                cacheAlternateVideoId(originalTrack.videoId, winningEntry.track.videoId)
                streamResolver.recordResolvedAlternate(originalTrack.videoId, winningEntry.track.videoId)
                Log.i(
                    "Sonora",
                    "AltStream Song-first success original=${originalTrack.videoId} alt=${winningEntry.track.videoId} score=${winningEntry.score} codec=${winningStream.codecLabel}"
                )
                return winningStream
            }
            testedIds.addAll(songRanked.map { it.track.videoId })
            PlaybackDiagnostics.recordAlternateEvent(
                originalTrack.videoId,
                "Búsqueda Song sin match reproducible -> ampliando a audio/lyrics/video"
            )
        } else {
            PlaybackDiagnostics.recordAlternateEvent(
                originalTrack.videoId,
                "Buscando alternativas: '$baseQuery' / audio / lyrics"
            )
        }

        // 3. Full fallback search ("artista título", "artista título audio", "artista título lyrics")
        val candidateWithOrigin = withTimeoutOrNull(4_500L) {
            coroutineScope {
                val ytmSongsJob = if (!isMvOriginal) {
                    async {
                        runCatching { ytMusicClient.search(baseQuery, SearchFilter.SONGS).songs }
                            .getOrDefault(emptyList())
                            .map { it to true }
                    }
                } else null
                val newPipeJob = if (!isMvOriginal) {
                    async {
                        newPipeSearchClient?.let { client ->
                            runCatching { client.search(baseQuery, SearchFilter.SONGS).songs }
                                .getOrDefault(emptyList())
                                .map { it to true }
                        }.orEmpty()
                    }
                } else null
                val ytmAllJob = async {
                    runCatching { ytMusicClient.search(baseQuery, SearchFilter.ALL).songs }
                        .getOrDefault(emptyList())
                        .map { it to false }
                }
                val audioSearchJob = async {
                    runCatching { ytMusicClient.search(audioQuery, SearchFilter.ALL).songs }
                        .getOrDefault(emptyList())
                        .map { it to false }
                }
                val lyricsSearchJob = async {
                    runCatching { ytMusicClient.search(lyricsQuery, SearchFilter.ALL).songs }
                        .getOrDefault(emptyList())
                        .map { it to false }
                }
                (ytmSongsJob?.await().orEmpty()) +
                    (newPipeJob?.await().orEmpty()) +
                    ytmAllJob.await() +
                    audioSearchJob.await() +
                    lyricsSearchJob.await()
            }
        }.orEmpty()

        val rankedCandidates = rankAlternatePool(originalTrack, candidateWithOrigin, testedIds)
        if (rankedCandidates.isEmpty()) {
            PlaybackDiagnostics.recordAlternateEvent(
                originalTrack.videoId,
                "Ningún candidato superó el puntaje mínimo (búsqueda=${candidateWithOrigin.size})"
            )
            Log.w("Sonora", "AltStream no candidates passed scoring for original=${originalTrack.videoId}")
            return null
        }

        val winner = probeRankedAlternates(originalTrack, rankedCandidates, allowHls)
        if (winner == null) {
            PlaybackDiagnostics.recordAlternateEvent(
                originalTrack.videoId,
                "Falló el probe en los ${rankedCandidates.size} candidatos alternos"
            )
            Log.w("Sonora", "AltStream probing failed for all ${rankedCandidates.size} candidates original=${originalTrack.videoId}")
            return null
        }

        val (winningEntry, winningStream) = winner
        cacheAlternateVideoId(originalTrack.videoId, winningEntry.track.videoId)
        streamResolver.recordResolvedAlternate(originalTrack.videoId, winningEntry.track.videoId)
        Log.i(
            "Sonora",
            "AltStream success original=${originalTrack.videoId} alt=${winningEntry.track.videoId} tier=${winningEntry.tier.label} score=${winningEntry.score} codec=${winningStream.codecLabel}"
        )
        return winningStream
    }

    suspend fun resolveTrackMetadata(track: Track): ResolvedTrackMetadata {
        if (track.isLocal || track.videoId.isBlank() || track.videoId.startsWith("local_")) {
            return ResolvedTrackMetadata(videoId = track.videoId)
        }
        val splitNames = splitArtistNames(track.artist)
        val cached = trackMetadataCache[track.videoId]
        if (cached != null && !cached.artistId.isNullOrBlank() && !cached.albumId.isNullOrBlank() &&
            (splitNames.size <= 1 || cached.artists.count { !it.browseId.isNullOrBlank() } >= splitNames.size)
        ) {
            return cached
        }
        if (!track.artistId.isNullOrBlank() && !track.albumId.isNullOrBlank() && splitNames.size <= 1) {
            val direct = ResolvedTrackMetadata(
                videoId = track.videoId,
                artistId = track.artistId,
                album = track.album,
                albumId = track.albumId,
                artists = splitNames.map { ArtistCredit(name = it, browseId = track.artistId) }
            )
            trackMetadataCache[track.videoId] = direct
            return direct
        }

        var resolvedArtistId = track.artistId?.takeIf { it.isNotBlank() } ?: cached?.artistId
        var resolvedAlbum = track.album?.takeIf { it.isNotBlank() } ?: cached?.album
        var resolvedAlbumId = track.albumId?.takeIf { it.isNotBlank() } ?: cached?.albumId
        var nextArtists: List<ArtistCredit> = cached?.artists.orEmpty()

        // 1. Try YouTube "next" endpoint for exact videoId metadata
        val fromNext = runCatching { ytMusicClient.resolveTrackMetadataFromNext(track.videoId) }.getOrNull()
        if (fromNext != null) {
            if (resolvedArtistId.isNullOrBlank()) resolvedArtistId = fromNext.artistId
            if (resolvedAlbum.isNullOrBlank()) resolvedAlbum = fromNext.album
            if (resolvedAlbumId.isNullOrBlank()) resolvedAlbumId = fromNext.albumId
            if (fromNext.artists.isNotEmpty()) {
                nextArtists = fromNext.artists
            }
        }

        // 2. Fallback to songs-filtered search if artistId or albumId is still missing
        if (resolvedArtistId.isNullOrBlank() || resolvedAlbumId.isNullOrBlank()) {
            val cleanTitle = CatalogAudioFallback.stripDecorations(track.title).ifBlank { track.title }.trim()
            val primaryArtist = splitNames.firstOrNull().orEmpty()
            val query = listOf(primaryArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ").trim()
            if (query.isNotBlank()) {
                val songHits = runCatching {
                    ytMusicClient.search(query, SearchFilter.SONGS).songs
                }.getOrDefault(emptyList())

                val exactVideoMatch = songHits.firstOrNull { it.videoId == track.videoId }
                val bestScoredMatch = exactVideoMatch ?: songHits.firstOrNull { cand ->
                    (!cand.artistId.isNullOrBlank() || !cand.albumId.isNullOrBlank()) &&
                        scoreAlternateCandidate(track, cand, fromSongsFilter = true) != null
                } ?: songHits.firstOrNull { cand ->
                    val normTargetTitle = CatalogAudioFallback.normalizeForMatch(cleanTitle)
                    val normCandTitle = CatalogAudioFallback.normalizeForMatch(
                        CatalogAudioFallback.stripDecorations(cand.title).ifBlank { cand.title }
                    )
                    normTargetTitle.isNotBlank() && normCandTitle == normTargetTitle &&
                        (!cand.artistId.isNullOrBlank() || !cand.albumId.isNullOrBlank())
                }

                if (bestScoredMatch != null) {
                    if (resolvedArtistId.isNullOrBlank()) resolvedArtistId = bestScoredMatch.artistId
                    if (resolvedAlbum.isNullOrBlank()) resolvedAlbum = bestScoredMatch.album
                    if (resolvedAlbumId.isNullOrBlank()) resolvedAlbumId = bestScoredMatch.albumId
                }
            }
        }

        val mergedArtists = if (splitNames.isNotEmpty()) {
            splitNames.mapIndexed { idx, displayName ->
                val targetNorm = CatalogAudioFallback.normalizeForMatch(displayName)
                val matchedId = nextArtists.firstOrNull { credit ->
                    val creditNorm = CatalogAudioFallback.normalizeForMatch(credit.name)
                    !credit.browseId.isNullOrBlank() && (
                        creditNorm == targetNorm ||
                            (creditNorm.length >= 3 && targetNorm.length >= 3 &&
                                (creditNorm.contains(targetNorm) || targetNorm.contains(creditNorm)))
                        )
                }?.browseId ?: if (idx == 0) resolvedArtistId else null
                ArtistCredit(name = displayName, browseId = matchedId)
            }
        } else {
            nextArtists
        }

        val result = ResolvedTrackMetadata(
            videoId = track.videoId,
            artistId = resolvedArtistId,
            album = resolvedAlbum,
            albumId = resolvedAlbumId,
            artists = mergedArtists
        )
        if (!resolvedArtistId.isNullOrBlank() || !resolvedAlbumId.isNullOrBlank() || mergedArtists.any { !it.browseId.isNullOrBlank() }) {
            trackMetadataCache[track.videoId] = result
            runCatching {
                libraryDao.updateLikedSongMetadata(track.videoId, resolvedArtistId, resolvedAlbum, resolvedAlbumId)
                libraryDao.updatePlaylistTrackMetadata(track.videoId, resolvedArtistId, resolvedAlbum, resolvedAlbumId)
                libraryDao.updateRecentTrackMetadata(track.videoId, resolvedArtistId, resolvedAlbum, resolvedAlbumId)
            }
        }
        return result
    }

    suspend fun resolveArtistBrowseId(artistName: String): String? {
        val clean = artistName.trim()
        if (clean.isBlank()) return null
        val targetNorm = CatalogAudioFallback.normalizeForMatch(clean)
        val candidates = runCatching {
            ytMusicClient.search(clean, SearchFilter.ARTISTS).artists
        }.getOrDefault(emptyList())
        return candidates.firstOrNull {
            CatalogAudioFallback.normalizeForMatch(it.title) == targetNorm
        }?.id ?: candidates.firstOrNull {
            val candNorm = CatalogAudioFallback.normalizeForMatch(it.title)
            candNorm.contains(targetNorm) || targetNorm.contains(candNorm)
        }?.id ?: candidates.firstOrNull()?.id
    }

    fun clearStreamCache() {
        streamResolver.clearCache()
    }

    suspend fun clearPlaybackHistory() {
        libraryDao.clearRecentTracks()
        libraryDao.clearListeningHistory()
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
        val newLiked = if (currentlyLiked) {
            libraryDao.deleteLikedSong(track.videoId)
            false
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
            true
        }
        if (!track.isLocal && ytMusicClient.getCurrentSessionContext() != null) {
            runCatching { ytMusicClient.setTrackLikeStatus(track.videoId, newLiked) }
        }
        return newLiked
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

    fun observeMostPlayedTracks(): Flow<List<Track>> =
        libraryDao.observeMostPlayedTracks().map { list -> list.map { it.toTrack() } }

    suspend fun loadInitialYouTubeHistoryIfNeeded() {
        if (_cloudHistorySections.value.isNotEmpty() || _isLoadingMoreHistory.value) return
        if (ytMusicClient.getCurrentSessionContext() == null) return
        _isLoadingMoreHistory.value = true
        try {
            val page = runCatching { ytMusicClient.getYouTubeHistoryPage(null) }.getOrNull()
            if (page != null) {
                _cloudHistorySections.value = page.sections
                _cloudHistoryNextToken.value = page.nextContinuationToken
            }
        } finally {
            _isLoadingMoreHistory.value = false
        }
    }

    suspend fun loadMoreYouTubeHistory() {
        val token = _cloudHistoryNextToken.value ?: return
        if (_isLoadingMoreHistory.value) return
        _isLoadingMoreHistory.value = true
        try {
            val nextPage = runCatching { ytMusicClient.getYouTubeHistoryPage(token) }.getOrNull() ?: return
            if (nextPage.sections.isNotEmpty()) {
                val current = _cloudHistorySections.value.toMutableList()
                nextPage.sections.forEach { sec ->
                    val existingIdx = current.indexOfFirst { it.title.equals(sec.title, ignoreCase = true) }
                    if (existingIdx >= 0) {
                        val mergedTracks = (current[existingIdx].tracks + sec.tracks).distinctBy { it.videoId }
                        current[existingIdx] = current[existingIdx].copy(tracks = mergedTracks)
                    } else {
                        current.add(sec)
                    }
                }
                _cloudHistorySections.value = current
            }
            _cloudHistoryNextToken.value = nextPage.nextContinuationToken
        } finally {
            _isLoadingMoreHistory.value = false
        }
    }

    fun observeListeningHistory(): Flow<List<HistoryDayGroup>> =
        combine(libraryDao.observeListeningHistory(), _cloudHistorySections) { rows, cloudSections ->
            val localEntries = rows.map { it.toHistoryEntry() }
            if (cloudSections.isEmpty()) {
                return@combine groupHistoryEntriesByDay(localEntries)
            }
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val localKeys = localEntries.mapTo(HashSet()) { entry ->
                val date = Instant.ofEpochMilli(entry.playedAtMs).atZone(zoneId).toLocalDate()
                "${date}|${entry.track.videoId}"
            }
            val syntheticEntries = mutableListOf<ListeningHistoryEntry>()
            var synthId = -1L
            cloudSections.forEachIndexed { sectionIdx, section ->
                val lowerTitle = section.title.lowercase(Locale.getDefault())
                val daysAgo = when {
                    lowerTitle.contains("hoy") || lowerTitle.contains("today") -> 0L
                    lowerTitle.contains("ayer") || lowerTitle.contains("yesterday") -> 1L
                    lowerTitle.contains("esta semana") || lowerTitle.contains("this week") -> 3L
                    lowerTitle.contains("semana pasada") || lowerTitle.contains("last week") -> 8L
                    lowerTitle.contains("este mes") || lowerTitle.contains("this month") -> 15L
                    else -> (20L + sectionIdx * 7L)
                }
                val baseDate = today.minusDays(daysAgo)
                val baseMs = baseDate.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
                section.tracks.forEachIndexed { idx, track ->
                    val key = "$baseDate|${track.videoId}"
                    if (localKeys.add(key)) {
                        syntheticEntries.add(
                            ListeningHistoryEntry(
                                id = synthId--,
                                track = track,
                                playedAtMs = baseMs - idx * 60_000L,
                                listenedMs = track.durationMs.takeIf { it > 0L } ?: 0L
                            )
                        )
                    }
                }
            }
            groupHistoryEntriesByDay(localEntries + syntheticEntries)
        }

    fun observeReplayStats(period: StatsPeriod = StatsPeriod.ALL): Flow<ListeningStatsSummary> =
        libraryDao.observeListeningHistory().map { rows ->
            computeStatsSummaryForHistory(
                entries = rows.map { it.toHistoryEntry() },
                period = period
            )
        }

    fun observeListeningStats(): Flow<ListeningStatsSummary> =
        observeReplayStats(StatsPeriod.ALL)

    suspend fun recordListeningSession(
        track: Track,
        playedAtMs: Long = System.currentTimeMillis(),
        listenedMs: Long = 0L
    ): Long {
        val currentCount = libraryDao.getRecentPlayCount(track.videoId) ?: 0
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
                playedAt = playedAtMs,
                playCount = currentCount + 1
            )
        )
        return libraryDao.insertListeningHistory(
            ListeningHistoryEntity(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                artistId = track.artistId,
                album = track.album,
                albumId = track.albumId,
                artworkUrl = track.artworkUrl,
                durationText = track.durationText,
                durationMs = track.durationMs,
                playedAtMs = playedAtMs,
                listenedMs = listenedMs.coerceAtLeast(0L)
            )
        )
    }

    suspend fun updateListeningSessionDuration(rowId: Long, listenedMs: Long) {
        libraryDao.updateListeningHistoryDuration(rowId, listenedMs.coerceAtLeast(0L))
    }

    suspend fun recordPlay(track: Track) {
        recordListeningSession(
            track = track,
            playedAtMs = System.currentTimeMillis(),
            listenedMs = track.durationMs.takeIf { it > 0L } ?: 0L
        )
    }

    fun observeSavedCollections(): Flow<List<BrowseCard>> =
        libraryDao.observeSavedCollections().map { list -> list.map { it.toBrowseCard() } }

    fun observeIsCollectionSaved(browseId: String): Flow<Boolean> =
        libraryDao.observeIsCollectionSaved(browseId)

    suspend fun toggleSaveCollection(card: BrowseCard, currentlySaved: Boolean) {
        val shouldSave = !currentlySaved
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
        if (ytMusicClient.getCurrentSessionContext() != null) {
            runCatching {
                if (card.kind == ItemKind.ARTIST || card.id.startsWith("UC")) {
                    ytMusicClient.setArtistSubscription(card.id, shouldSave)
                } else {
                    val targetPlaylistId = if (card.id.startsWith("VL") || card.id.startsWith("PL")) {
                        card.id.removePrefix("VL")
                    } else {
                        ytMusicClient.getBrowseDetail(card.id, card.params, maxContinuationPages = 0).playlistId
                    }
                    if (!targetPlaylistId.isNullOrBlank()) {
                        ytMusicClient.setCollectionLikeStatus(targetPlaylistId, shouldSave)
                    }
                }
            }
        }
    }

    suspend fun createYouTubePlaylist(
        title: String,
        description: String = "",
        videoIds: List<String> = emptyList()
    ): Result<String> = runCatching {
        ytMusicClient.createYouTubePlaylist(title, description, videoIds)
            ?: throw IllegalStateException("Inicia sesión con YouTube Music para crear playlists en tu cuenta.")
    }

    suspend fun addTrackToYouTubePlaylist(playlistBrowseId: String, videoId: String): Result<Boolean> = runCatching {
        ytMusicClient.addVideoToYouTubePlaylist(playlistBrowseId, videoId)
    }

    fun observeSearchHistory(): Flow<List<String>> =
        libraryDao.observeSearchHistory().map { list -> list.map { it.query } }

    suspend fun deleteSearchHistory(query: String) {
        libraryDao.deleteSearchHistory(query)
    }

    fun observeDownloadedTracks(): Flow<List<DownloadedTrackEntity>> =
        libraryDao.observeDownloadedTracks()

    fun observeIsDownloaded(videoId: String): Flow<Boolean> =
        libraryDao.observeIsDownloaded(videoId)

    // --- Playlist Transitions (Fragment Mixer) ---
    fun observePlaylistTransitions(playlistId: Long): Flow<List<PlaylistTransitionSpec>> =
        libraryDao.observePlaylistTransitions(playlistId).map { list -> list.map { it.toSpec() } }

    suspend fun getPlaylistTransitionsOnce(playlistId: Long): List<PlaylistTransitionSpec> =
        libraryDao.getPlaylistTransitionsOnce(playlistId).map { it.toSpec() }

    suspend fun savePlaylistTransition(spec: PlaylistTransitionSpec) {
        libraryDao.upsertPlaylistTransition(
            PlaylistTransitionEntity(
                playlistId = spec.playlistId,
                fromVideoId = spec.fromVideoId,
                toVideoId = spec.toVideoId,
                fromExitMs = spec.fromExitMs.coerceAtLeast(0L),
                toEntryMs = spec.toEntryMs.coerceAtLeast(0L),
                crossfadeMs = spec.crossfadeMs.coerceIn(1_500, 16_000),
                curveType = spec.curveType.name,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun deletePlaylistTransition(playlistId: Long, fromVideoId: String, toVideoId: String) {
        libraryDao.deletePlaylistTransition(playlistId, fromVideoId, toVideoId)
    }
}
