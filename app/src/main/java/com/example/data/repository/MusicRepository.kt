package com.example.data.repository

import com.example.data.local.AccountSessionEntity
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.LibraryDao
import com.example.data.local.LikedSongEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackEntity
import com.example.data.local.RecentTrackEntity
import com.example.data.local.SavedCollectionEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.toBrowseCard
import com.example.data.local.toTrack
import com.example.data.network.LyricsResolver
import com.example.data.network.NewPipeSearchClient
import com.example.data.network.SpotifyImporter
import com.example.data.network.StreamResolver
import com.example.data.network.YouTubeMusicClient
import com.example.model.ArtistPlayStat
import com.example.model.BrowseCard
import com.example.model.BrowseDetail
import com.example.model.HomeShelf
import com.example.model.ListeningStatsSummary
import com.example.model.MoodCategory
import com.example.model.ResolvedStream
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.SpotifyImportState
import com.example.model.SpotifyParsedPlaylist
import com.example.model.Track
import com.example.model.TrackLyrics
import com.example.model.TrackPlayStat
import com.example.model.YouTubeAccountInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.Normalizer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class SyncedYouTubeLibrary(
    val playlists: List<BrowseCard>,
    val likedSongs: List<Track>,
    val historyTracks: List<Track> = emptyList(),
    val savedCollections: List<BrowseCard> = emptyList()
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
        coroutineScope {
            val playlistsDeferred = async { runCatching { ytMusicClient.getLibraryPlaylists() } }
            val savedDeferred = async { runCatching { ytMusicClient.getLibrarySavedCollections() } }
            val likedDeferred = async { runCatching { ytMusicClient.getLibraryLikedSongs() } }
            val historyDeferred = async { runCatching { ytMusicClient.getYouTubeHistory() } }

            val plRes = playlistsDeferred.await()
            val savedRes = savedDeferred.await()
            val likedRes = likedDeferred.await()
            val historyRes = historyDeferred.await()

            val allFailed = plRes.isFailure && savedRes.isFailure && likedRes.isFailure && historyRes.isFailure
            if (allFailed) {
                val firstErr = likedRes.exceptionOrNull() ?: plRes.exceptionOrNull()
                val msg = firstErr?.message.orEmpty()
                if (msg.contains("401") || msg.contains("403")) {
                    throw IllegalStateException("La sesión de Google expiró. Vuelve a iniciar sesión.")
                }
                throw firstErr ?: IllegalStateException("No se pudo sincronizar con YouTube Music.")
            }

            val playlists = plRes.getOrDefault(emptyList())
            val savedCollections = savedRes.getOrDefault(emptyList())
            val liked = likedRes.getOrDefault(emptyList())
            val history = historyRes.getOrDefault(emptyList())

            if (history.isNotEmpty()) {
                val baseTime = System.currentTimeMillis()
                history.take(20).asReversed().forEachIndexed { idx, track ->
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
                savedCollections = savedCollections
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

    suspend fun getHomeShelves(): Result<List<HomeShelf>> = runCatching {
        ytMusicClient.getHomeShelves()
    }

    suspend fun getPersonalizedRecommendations(
        seedTracks: List<Track>,
        mostPlayedTracks: List<Track> = emptyList(),
        likedTracks: List<Track> = emptyList()
    ): List<HomeShelf> {
        return runCatching {
            ytMusicClient.getPersonalizedRecommendations(seedTracks, mostPlayedTracks, likedTracks)
        }.getOrDefault(emptyList())
    }

    suspend fun buildSuggestedSongSet(
        homeShelves: List<HomeShelf>,
        personalizedShelves: List<HomeShelf>,
        recentTracks: List<Track>,
        mostPlayedTracks: List<Track>,
        likedTracks: List<Track>,
        refreshSeed: Int = 0
    ): List<Track> {
        val homeTracks = homeShelves.flatMap { it.tracks }
        val personalizedTracks = personalizedShelves.flatMap { it.tracks }
        val expandedTracks = mutableListOf<Track>()

        // Pick a rotating seed (from user history or home tracks) to fetch fresh Up Next songs on refresh
        val poolForSeed = (recentTracks + mostPlayedTracks + likedTracks + personalizedTracks + homeTracks)
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }

        if (poolForSeed.isNotEmpty()) {
            val seedTrack = poolForSeed[refreshSeed.mod(poolForSeed.size)]
            runCatching {
                val (upNext, _) = getUpNextAndLyricsId(seedTrack.videoId)
                expandedTracks.addAll(upNext)
            }
        }

        // If home shelves mostly hold album/playlist cards, unpack a rotating featured playlist for songs
        if (personalizedTracks.size + homeTracks.size + expandedTracks.size < 10) {
            val playlistCards = homeShelves
                .flatMap { it.items }
                .filter { it.id.isNotBlank() && it.videoId == null }
            if (playlistCards.isNotEmpty()) {
                val card = playlistCards[refreshSeed.mod(playlistCards.size)]
                runCatching {
                    getBrowseDetail(card.id, card.params).onSuccess { detail ->
                        expandedTracks.addAll(detail.tracks.take(12))
                    }
                }
            }
        }

        return curateSuggestedSongs(
            personalizedTracks = personalizedTracks,
            expandedTracks = expandedTracks,
            homeTracks = homeTracks,
            userHistoryTracks = recentTracks + mostPlayedTracks + likedTracks,
            refreshSeed = refreshSeed
        )
    }

    companion object {
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
            val ordered = unseen + familiar

            // Rotate slightly on every refresh so the suggested song set feels visibly refreshed
            val shift = if (ordered.size > 3) (refreshSeed * 3).mod(ordered.size) else 0
            val rotated = ordered.drop(shift) + ordered.take(shift)
            return rotated.take(limit)
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
            limit: Int = 24
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

            val artistPool = (cleanArtistSongs + radioSameArtist + cleanContextSameArtist)
                .distinctBy { it.videoId }
            val relatedPool = radioRelated.distinctBy { it.videoId }

            val curated = mutableListOf(seedTrack)
            val usedIds = mutableSetOf(seedTrack.videoId)
            var aIdx = 0
            var rIdx = 0

            // Interleave artist hits and radio recommendations so upcoming songs stay cohesive
            while (curated.size < limit && (aIdx < artistPool.size || rIdx < relatedPool.size)) {
                repeat(2) {
                    while (aIdx < artistPool.size && curated.size < limit) {
                        val t = artistPool[aIdx++]
                        if (usedIds.add(t.videoId)) {
                            curated.add(t)
                            return@repeat
                        }
                    }
                }
                if (rIdx < relatedPool.size && curated.size < limit) {
                    while (rIdx < relatedPool.size) {
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
        contextSongs: List<Track> = emptyList()
    ): Pair<List<Track>, String?> = coroutineScope {
        val upNextDeferred = async {
            runCatching { ytMusicClient.getUpNextAndLyricsBrowseId(seedTrack.videoId) }
                .getOrDefault(emptyList<Track>() to null)
        }
        val artistSongsDeferred = async {
            val primaryArtist = splitArtistTokens(seedTrack.artist).firstOrNull().orEmpty()
            val fromArtistPage = if (!seedTrack.artistId.isNullOrBlank()) {
                runCatching { ytMusicClient.getBrowseDetail(seedTrack.artistId).tracks }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val fromArtistSearch = if (primaryArtist.isNotBlank()) {
                runCatching { ytMusicClient.search(primaryArtist, SearchFilter.SONGS).songs }
                    .getOrDefault(emptyList())
                    .filter { sharesArtistWithSeed(seedTrack, it) }
            } else {
                emptyList()
            }
            val combined = (fromArtistPage + fromArtistSearch).distinctBy { it.videoId }
            if (combined.size < 6 && primaryArtist.isNotBlank() && newPipeSearchClient != null) {
                val fromNewPipe = runCatching {
                    newPipeSearchClient.search(primaryArtist, SearchFilter.SONGS).songs
                }.getOrDefault(emptyList())
                    .filter { sharesArtistWithSeed(seedTrack, it) }
                (combined + fromNewPipe).distinctBy { it.videoId }
            } else {
                combined
            }
        }

        val (upNext, lyricsBrowseId) = upNextDeferred.await()
        val artistSongs = artistSongsDeferred.await()
        val audioPref = audioOnlyPreference
        val curated = curateSmartRelatedQueue(
            seedTrack = seedTrack,
            upNextTracks = prioritizeAudioOnly(upNext, audioPref),
            artistSongs = prioritizeAudioOnly(artistSongs, audioPref),
            contextSongs = prioritizeAudioOnly(contextSongs, audioPref),
            limit = 24
        )
        curated to lyricsBrowseId
    }

    suspend fun buildTailExtensionTracks(
        seedTrack: Track,
        existingQueue: List<Track>,
        limit: Int = 15
    ): List<Track> {
        val (smartQueue, _) = buildSmartRelatedQueueWithLyrics(
            seedTrack = seedTrack,
            contextSongs = emptyList()
        )
        val existingIds = existingQueue.map { it.videoId }.toSet()
        val existingTitles = existingQueue.map { normalizeTitleKey(it.title, it.artist) }
            .filter { it.isNotBlank() }
            .toSet()
        return smartQueue
            .drop(1)
            .filter { cand ->
                cand.videoId !in existingIds &&
                    normalizeTitleKey(cand.title, cand.artist) !in existingTitles
            }
            .take(limit)
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

    suspend fun findAlternatePlayableStream(originalTrack: Track): ResolvedStream? {
        val cleanQuery = com.example.data.network.CatalogAudioFallback.buildCleanQuery(
            originalTrack.title,
            originalTrack.artist
        ).ifBlank {
            com.example.data.network.CatalogAudioFallback.stripDecorations(originalTrack.title)
                .ifBlank { originalTrack.title }
        }
        if (cleanQuery.isBlank()) return null

        val rawCandidates = coroutineScope {
            val ytmDeferred = async {
                runCatching { ytMusicClient.search(cleanQuery, SearchFilter.SONGS).songs }
                    .getOrDefault(emptyList())
            }
            val npDeferred = async {
                newPipeSearchClient?.let { client ->
                    runCatching { client.search(cleanQuery, SearchFilter.SONGS).songs }
                        .getOrDefault(emptyList())
                }.orEmpty()
            }
            (ytmDeferred.await() + npDeferred.await()).distinctBy { it.videoId }
        }

        val audioPref = audioOnlyPreference
        val candidates = rawCandidates
            .filter { it.videoId != originalTrack.videoId && it.videoId.isNotBlank() }
            .map { cand ->
                val baseScore = scoreTrackMatch(
                    targetTitle = originalTrack.title,
                    targetArtist = originalTrack.artist,
                    targetDurationMs = originalTrack.durationMs,
                    candidate = cand
                )
                val adjustedScore = if (audioPref && isVideoHeavyUpload(cand)) baseScore - 18 else baseScore
                cand to adjustedScore
            }
            .filter { it.second >= 130 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(3)

        if (candidates.isEmpty()) return null

        return withTimeoutOrNull(14_000L) {
            coroutineScope {
                val jobs = candidates.map { candidate ->
                    async { streamResolver.resolveAudioStream(candidate).getOrNull() }
                }.toMutableList()
                var winner: ResolvedStream? = null
                while (jobs.isNotEmpty() && winner == null) {
                    val selected = select {
                        jobs.forEach { deferred ->
                            deferred.onAwait { res -> deferred to res }
                        }
                    }
                    jobs.remove(selected.first)
                    if (selected.second != null) {
                        winner = selected.second
                    }
                }
                jobs.forEach { it.cancel() }
                winner
            }
        }
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

    fun observeMostPlayedTracks(): Flow<List<Track>> =
        libraryDao.observeMostPlayedTracks().map { list -> list.map { it.toTrack() } }

    fun observeListeningStats(): Flow<ListeningStatsSummary> =
        libraryDao.observeAllRecentTracksForStats().map { entries ->
            if (entries.isEmpty()) {
                ListeningStatsSummary()
            } else {
                val totalPlays = entries.sumOf { it.playCount.coerceAtLeast(1) }
                val totalMinutes = entries.sumOf { entity ->
                    val durMs = entity.durationMs.takeIf { it > 0L } ?: 210_000L
                    (durMs * entity.playCount.coerceAtLeast(1)) / 60_000L
                }
                val topTracks = entries
                    .sortedByDescending { it.playCount }
                    .take(5)
                    .map { TrackPlayStat(track = it.toTrack(), playCount = it.playCount.coerceAtLeast(1)) }
                val artistGroups = entries.groupBy { entity ->
                    entity.artist
                        .split(",", "&", " feat.", " feat ", " ft.", " ft ")
                        .firstOrNull()
                        ?.trim()
                        ?.ifBlank { "Artista desconocido" }
                        ?: "Artista desconocido"
                }
                val topArtists = artistGroups.map { (artistName, items) ->
                    val plays = items.sumOf { it.playCount.coerceAtLeast(1) }
                    val mins = items.sumOf { entity ->
                        val durMs = entity.durationMs.takeIf { it > 0L } ?: 210_000L
                        (durMs * entity.playCount.coerceAtLeast(1)) / 60_000L
                    }
                    ArtistPlayStat(artist = artistName, totalPlays = plays, estimatedMinutes = mins)
                }
                    .sortedByDescending { it.totalPlays }
                    .take(5)
                ListeningStatsSummary(
                    totalPlays = totalPlays,
                    uniqueTracks = entries.size,
                    estimatedMinutes = totalMinutes,
                    topTracks = topTracks,
                    topArtists = topArtists
                )
            }
        }

    suspend fun recordPlay(track: Track) {
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
                playedAt = System.currentTimeMillis(),
                playCount = currentCount + 1
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

    fun observeDownloadedTracks(): Flow<List<DownloadedTrackEntity>> =
        libraryDao.observeDownloadedTracks()

    fun observeIsDownloaded(videoId: String): Flow<Boolean> =
        libraryDao.observeIsDownloaded(videoId)
}
