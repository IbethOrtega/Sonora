package com.example.data.network

import com.example.model.BrowseCard
import com.example.model.BrowseDetail
import com.example.model.HomeShelf
import com.example.model.ItemKind
import com.example.model.MoodCategory
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.Track
import com.example.model.YouTubeAccountInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

class YouTubeMusicClient(
    private val httpClient: OkHttpClient
) {
    @Volatile
    private var sessionCookies: String? = null

    fun setSessionCookies(cookies: String?) {
        sessionCookies = cookies?.takeIf { it.isNotBlank() }
    }
    companion object {
        private const val BASE_URL = "https://music.youtube.com/youtubei/v1/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val ORIGIN = "https://music.youtube.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private fun buildContextJson(): JSONObject {
        val locale = Locale.getDefault()
        val hl = if (locale.language.isNotBlank()) locale.language else "es"
        val gl = if (locale.country.length == 2) locale.country else "US"
        return JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20250115.01.00")
                .put("hl", hl)
                .put("gl", gl)
        )
    }

    private fun extractCookieValue(cookies: String, key: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    internal fun buildAuthorizationHeader(
        cookies: String,
        origin: String = ORIGIN,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ): String? {
        val ts = timestampSec.toString()
        val sapisid = extractCookieValue(cookies, "SAPISID")
        val p1 = extractCookieValue(cookies, "__Secure-1PAPISID")
        val p3 = extractCookieValue(cookies, "__Secure-3PAPISID")
        val parts = mutableListOf<String>()
        val mainKey = p3 ?: sapisid
        if (mainKey != null) {
            parts.add(computeSchemeHash("SAPISIDHASH", mainKey, origin, ts))
        }
        if (p1 != null) {
            parts.add(computeSchemeHash("SAPISID1PHASH", p1, origin, ts))
        }
        if (p3 != null) {
            parts.add(computeSchemeHash("SAPISID3PHASH", p3, origin, ts))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun computeSchemeHash(scheme: String, key: String, origin: String, timestamp: String): String {
        val input = "$timestamp $key $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$scheme ${timestamp}_$hex"
    }

    private suspend fun post(endpoint: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        body.put("context", buildContextJson())
        val builder = Request.Builder()
            .url("$BASE_URL$endpoint?prettyPrint=false")
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20250115.01.00")

        val cookies = sessionCookies
        if (!cookies.isNullOrBlank()) {
            builder.header("Cookie", cookies)
            builder.header("X-Origin", ORIGIN)
            builder.header("X-Goog-AuthUser", "0")
            buildAuthorizationHeader(cookies, ORIGIN)?.let { auth ->
                builder.header("Authorization", auth)
            }
        }

        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("YouTube Music HTTP ${response.code}")
            }
            JSONObject(raw)
        }
    }

    suspend fun getAccountInfo(): YouTubeAccountInfo? {
        if (sessionCookies.isNullOrBlank()) return null
        val json = post("account/account_menu", JSONObject())
        val header = json.optJSONArray("actions")
            ?.optJSONObject(0)
            ?.optJSONObject("openPopupAction")
            ?.optJSONObject("popup")
            ?.optJSONObject("multiPageMenuRenderer")
            ?.optJSONObject("header")
            ?.optJSONObject("activeAccountHeaderRenderer") ?: return null

        val name = extractRunsText(header.optJSONObject("accountName")).ifBlank { "Cuenta de Google" }
        val handle = extractRunsText(header.optJSONObject("channelHandle"))
            .ifBlank { extractRunsText(header.optJSONObject("email")) }
        val avatar = extractBestThumbnail(header.optJSONObject("accountPhoto"))
        return YouTubeAccountInfo(name = name, handle = handle, avatarUrl = avatar)
    }

    suspend fun getLibraryPlaylists(): List<BrowseCard> {
        if (sessionCookies.isNullOrBlank()) return emptyList()
        val playlistsJson = runCatching {
            post("browse", JSONObject().put("browseId", "FEmusic_liked_playlists"))
        }.getOrNull()
        val primaryItems = playlistsJson?.let { parseShelvesFromBrowse(it) }
            ?.flatMap { it.items }
            .orEmpty()

        val landingItems = runCatching {
            val landingJson = post("browse", JSONObject().put("browseId", "FEmusic_library_landing"))
            parseShelvesFromBrowse(landingJson).flatMap { it.items }
                .filter { it.kind == ItemKind.PLAYLIST }
        }.getOrDefault(emptyList())

        val combined = (primaryItems + landingItems)
            .filter { it.id.isNotBlank() && it.id != "VLLM" }
            .distinctBy { it.id }

        if (combined.isNotEmpty()) return combined
        return (primaryItems + landingItems).filter { it.id.isNotBlank() }.distinctBy { it.id }
    }

    suspend fun getLibrarySavedCollections(): List<BrowseCard> {
        if (sessionCookies.isNullOrBlank()) return emptyList()
        val albumsItems = runCatching {
            val albumsJson = post("browse", JSONObject().put("browseId", "FEmusic_liked_albums"))
            parseShelvesFromBrowse(albumsJson).flatMap { it.items }
        }.getOrDefault(emptyList())

        val landingItems = runCatching {
            val landingJson = post("browse", JSONObject().put("browseId", "FEmusic_library_landing"))
            parseShelvesFromBrowse(landingJson).flatMap { it.items }
        }.getOrDefault(emptyList())

        return (albumsItems + landingItems)
            .filter { it.id.isNotBlank() && it.kind != ItemKind.SONG && it.id != "VLLM" }
            .distinctBy { it.id }
    }

    suspend fun getLibraryLikedSongs(): List<Track> {
        if (sessionCookies.isNullOrBlank()) return emptyList()
        val fromVllm = runCatching { getBrowseDetail("VLLM").tracks }.getOrDefault(emptyList())
        if (fromVllm.isNotEmpty()) return fromVllm.distinctBy { it.videoId }

        val fromLikedVideosDetail = runCatching {
            getBrowseDetail("FEmusic_liked_videos").tracks
        }.getOrDefault(emptyList())
        if (fromLikedVideosDetail.isNotEmpty()) return fromLikedVideosDetail.distinctBy { it.videoId }

        val likedJson = post("browse", JSONObject().put("browseId", "FEmusic_liked_videos"))
        val shelves = parseShelvesFromBrowse(likedJson)
        return shelves.flatMap { it.tracks }.distinctBy { it.videoId }
    }

    suspend fun getYouTubeHistory(): List<Track> {
        if (sessionCookies.isNullOrBlank()) return emptyList()
        val json = post("browse", JSONObject().put("browseId", "FEmusic_history"))
        val shelves = parseShelvesFromBrowse(json)
        return shelves.flatMap { it.tracks }.distinctBy { it.videoId }.take(30)
    }

    suspend fun getPersonalizedRecommendations(
        seedTracks: List<Track>,
        mostPlayedTracks: List<Track> = emptyList(),
        likedTracks: List<Track> = emptyList()
    ): List<HomeShelf> {
        val cleanSeeds = (seedTracks + mostPlayedTracks + likedTracks)
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }
        if (cleanSeeds.isEmpty()) return emptyList()

        val resultShelves = mutableListOf<HomeShelf>()
        val latest = seedTracks.firstOrNull { it.videoId.isNotBlank() } ?: cleanSeeds.first()
        val favoriteSeed = (mostPlayedTracks + likedTracks)
            .firstOrNull { it.videoId.isNotBlank() && it.videoId != latest.videoId }

        val artistCounts = mutableMapOf<String, Int>()
        (mostPlayedTracks + likedTracks + seedTracks).forEachIndexed { idx, track ->
            val name = track.artist.substringBefore(",").trim()
            if (name.isNotBlank() && !name.equals("YouTube Music", ignoreCase = true)) {
                val weight = if (idx < mostPlayedTracks.size) 3 else 1
                artistCounts[name] = (artistCounts[name] ?: 0) + weight
            }
        }
        val topArtist = artistCounts.maxByOrNull { it.value }?.key

        val seenVideoIds = mutableSetOf(latest.videoId)

        // Shelf 1: Track-seeded radio recommendations
        runCatching {
            val (upNext, _) = getUpNextAndLyricsBrowseId(latest.videoId)
            val filtered = upNext.filter { it.videoId !in seenVideoIds }.distinctBy { it.videoId }.take(10)
            if (filtered.isNotEmpty()) {
                seenVideoIds.addAll(filtered.map { it.videoId })
                resultShelves.add(
                    HomeShelf(
                        title = "Basado en ${latest.title}",
                        subtitle = "Sugerencias según tus preferencias",
                        tracks = filtered,
                        isQuickPicks = true
                    )
                )
            }
        }

        // Shelf 2: Artist-seeded discovery based on most-played & liked artists
        if (topArtist != null) {
            runCatching {
                val searchRes = search(topArtist, SearchFilter.SONGS)
                val relatedSongs = searchRes.songs
                    .filter { it.videoId !in seenVideoIds }
                    .distinctBy { it.videoId }
                    .take(8)
                if (relatedSongs.isNotEmpty()) {
                    seenVideoIds.addAll(relatedSongs.map { it.videoId })
                    resultShelves.add(
                        HomeShelf(
                            title = "Porque escuchas a $topArtist",
                            subtitle = "Adaptado a tus gustos",
                            tracks = relatedSongs,
                            isQuickPicks = true
                        )
                    )
                }
            }
        }

        // Shelf 3: Mix inspired by user's most-listened or liked song
        if (favoriteSeed != null) {
            runCatching {
                val (favUpNext, _) = getUpNextAndLyricsBrowseId(favoriteSeed.videoId)
                val mixTracks = favUpNext
                    .filter { it.videoId != favoriteSeed.videoId && it.videoId !in seenVideoIds }
                    .distinctBy { it.videoId }
                    .take(8)
                if (mixTracks.isNotEmpty()) {
                    resultShelves.add(
                        HomeShelf(
                            title = "Mix de ${favoriteSeed.title}",
                            subtitle = "Inspirado en tus favoritas",
                            tracks = mixTracks,
                            isQuickPicks = true
                        )
                    )
                }
            }
        }

        return resultShelves
    }

    suspend fun getHomeShelves(): List<HomeShelf> {
        val json = post("browse", JSONObject().put("browseId", "FEmusic_home"))
        return parseShelvesFromBrowse(json)
    }

    suspend fun getExploreData(): Pair<List<HomeShelf>, List<MoodCategory>> {
        val exploreJson = post("browse", JSONObject().put("browseId", "FEmusic_explore"))
        val shelves = parseShelvesFromBrowse(exploreJson).toMutableList()

        // Also pull Moods & Genres or Charts if needed
        val moods = runCatching {
            val moodsJson = post("browse", JSONObject().put("browseId", "FEmusic_moods_and_genres"))
            parseMoodCategories(moodsJson)
        }.getOrDefault(defaultMoodCategories())

        val chartsShelves = runCatching {
            val chartsJson = post("browse", JSONObject().put("browseId", "FEmusic_charts"))
            parseShelvesFromBrowse(chartsJson)
        }.getOrDefault(emptyList())

        val combined = (chartsShelves + shelves).distinctBy { it.title }
        return combined to moods.ifEmpty { defaultMoodCategories() }
    }

    suspend fun getSearchSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val json = post("music/get_search_suggestions", JSONObject().put("input", query))
        val results = mutableListOf<String>()
        val contents = json.optJSONArray("contents") ?: return emptyList()
        for (i in 0 until contents.length()) {
            val section = contents.optJSONObject(i)
                ?.optJSONObject("searchSuggestionsSectionRenderer")
                ?.optJSONArray("contents") ?: continue
            for (j in 0 until section.length()) {
                val item = section.optJSONObject(j)
                val renderer = item?.optJSONObject("searchSuggestionRenderer") ?: continue
                val text = extractRunsText(renderer.optJSONObject("suggestion"))
                if (text.isNotBlank()) results.add(text)
            }
        }
        return results.distinct().take(8)
    }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): SearchResults {
        val body = JSONObject().put("query", query)
        filter.params?.let { body.put("params", it) }
        val json = post("search", body)

        val songs = mutableListOf<Track>()
        val albums = mutableListOf<BrowseCard>()
        val artists = mutableListOf<BrowseCard>()
        val playlists = mutableListOf<BrowseCard>()

        val tabs = json.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
        val sectionContents = tabs?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: JSONArray()

        for (i in 0 until sectionContents.length()) {
            val section = sectionContents.optJSONObject(i) ?: continue
            // Handle musicCardShelfRenderer (Top Result)
            section.optJSONObject("musicCardShelfRenderer")?.let { cardShelf ->
                val title = extractRunsText(cardShelf.optJSONObject("title"))
                val subtitle = extractRunsText(cardShelf.optJSONObject("subtitle"))
                val artwork = extractBestThumbnail(cardShelf.optJSONObject("thumbnail"))
                val onTap = cardShelf.optJSONObject("onTap")
                val videoId = onTap?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
                val browseEndpoint = onTap?.optJSONObject("browseEndpoint")
                val browseId = browseEndpoint?.optString("browseId").orEmpty()
                if (videoId.isNotBlank()) {
                    songs.add(
                        Track(
                            videoId = videoId,
                            title = title,
                            artist = subtitle.substringAfter("•").trim().ifBlank { subtitle },
                            artworkUrl = artwork
                        )
                    )
                } else if (browseId.isNotBlank()) {
                    val kind = inferKind(browseId, subtitle)
                    val card = BrowseCard(browseId, title, subtitle, artwork, kind)
                    when (kind) {
                        ItemKind.ARTIST -> artists.add(card)
                        ItemKind.ALBUM -> albums.add(card)
                        else -> playlists.add(card)
                    }
                }
                val cardContents = cardShelf.optJSONArray("contents") ?: JSONArray()
                for (k in 0 until cardContents.length()) {
                    cardContents.optJSONObject(k)?.optJSONObject("musicResponsiveListItemRenderer")?.let { item ->
                        parseTrackFromListItem(item)?.let { songs.add(it) }
                    }
                }
            }

            val shelf = section.optJSONObject("musicShelfRenderer") ?: continue
            val shelfTitle = extractRunsText(shelf.optJSONObject("title")).lowercase()
            val items = shelf.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val renderer = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                val browseEndpoint = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val browseId = browseEndpoint?.optString("browseId").orEmpty()
                val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType").orEmpty()

                val isSong = filter == SearchFilter.SONGS ||
                    pageType.contains("VIDEO") ||
                    shelfTitle.contains("song") ||
                    shelfTitle.contains("cancion") ||
                    shelfTitle.contains("video") ||
                    browseId.isBlank()

                if (isSong) {
                    parseTrackFromListItem(renderer)?.let { songs.add(it) }
                } else {
                    val card = parseCardFromListItem(renderer, pageType)
                    if (card != null) {
                        when (card.kind) {
                            ItemKind.ALBUM -> albums.add(card)
                            ItemKind.ARTIST -> artists.add(card)
                            ItemKind.PLAYLIST -> playlists.add(card)
                            else -> {}
                        }
                    } else {
                        parseTrackFromListItem(renderer)?.let { songs.add(it) }
                    }
                }
            }
        }

        return SearchResults(
            songs = songs.distinctBy { it.videoId },
            albums = albums.distinctBy { it.id },
            artists = artists.distinctBy { it.id },
            playlists = playlists.distinctBy { it.id }
        )
    }

    suspend fun getBrowseDetail(browseId: String, params: String? = null): BrowseDetail {
        val body = JSONObject().put("browseId", browseId)
        if (!params.isNullOrBlank()) body.put("params", params)
        val json = post("browse", body)

        val contents = json.optJSONObject("contents")
        val twoCol = contents?.optJSONObject("twoColumnBrowseResultsRenderer")
        val singleCol = contents?.optJSONObject("singleColumnBrowseResultsRenderer")

        var title = ""
        var subtitle = ""
        var description = ""
        var artworkUrl = ""
        var playlistId: String? = null
        val tracks = mutableListOf<Track>()
        val shelves = mutableListOf<HomeShelf>()

        // Parse header (either top-level header or inside twoColumn primary tab)
        val topHeader = json.optJSONObject("header")
        val primaryTabSections = twoCol?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        val responsiveHeader = primaryTabSections?.optJSONObject(0)?.optJSONObject("musicResponsiveHeaderRenderer")
        val detailHeader = topHeader?.optJSONObject("musicDetailHeaderRenderer")
        val immersiveHeader = topHeader?.optJSONObject("musicImmersiveHeaderRenderer")
        val visualHeader = topHeader?.optJSONObject("musicVisualHeaderRenderer")

        when {
            responsiveHeader != null -> {
                title = extractRunsText(responsiveHeader.optJSONObject("title"))
                subtitle = extractRunsText(responsiveHeader.optJSONObject("subtitle"))
                description = extractRunsText(
                    responsiveHeader.optJSONObject("description")
                        ?.optJSONObject("musicDescriptionShelfRenderer")
                        ?.optJSONObject("description")
                )
                artworkUrl = extractBestThumbnail(responsiveHeader.optJSONObject("thumbnail"))
                playlistId = findPlaylistIdInJson(responsiveHeader)
            }
            detailHeader != null -> {
                title = extractRunsText(detailHeader.optJSONObject("title"))
                subtitle = extractRunsText(detailHeader.optJSONObject("subtitle"))
                description = extractRunsText(detailHeader.optJSONObject("description"))
                artworkUrl = extractBestThumbnail(detailHeader.optJSONObject("thumbnail"))
                playlistId = findPlaylistIdInJson(detailHeader)
            }
            immersiveHeader != null -> {
                title = extractRunsText(immersiveHeader.optJSONObject("title"))
                description = extractRunsText(immersiveHeader.optJSONObject("description"))
                artworkUrl = extractBestThumbnail(immersiveHeader.optJSONObject("thumbnail"))
                subtitle = "Artista"
            }
            visualHeader != null -> {
                title = extractRunsText(visualHeader.optJSONObject("title"))
                artworkUrl = extractBestThumbnail(visualHeader.optJSONObject("thumbnail"))
                subtitle = "Artista"
            }
            else -> {
                title = extractRunsText(
                    topHeader?.optJSONObject("musicHeaderRenderer")?.optJSONObject("title")
                )
            }
        }

        // Parse secondary contents (Album / Playlist tracks in twoColumn layout)
        val secondarySections = twoCol?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        for (sec in unwrapSectionContents(secondarySections)) {
            val shelfItems = sec.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                ?: sec.optJSONObject("musicPlaylistShelfRenderer")?.optJSONArray("contents")
            if (shelfItems != null) {
                for (j in 0 until shelfItems.length()) {
                    val item = shelfItems.optJSONObject(j)
                        ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseTrackFromListItem(
                        renderer = item,
                        fallbackArtwork = artworkUrl,
                        fallbackAlbum = if (browseId.startsWith("MPREb")) title else null,
                        fallbackAlbumId = if (browseId.startsWith("MPREb")) browseId else null,
                        fallbackArtist = if (browseId.startsWith("MPREb")) {
                            subtitle.substringAfter("•").substringBefore("•").trim()
                        } else null
                    )?.let { tracks.add(it) }
                }
            }
            sec.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                parseCarouselShelf(carousel)?.let { shelves.add(it) }
            }
        }

        // Parse singleColumn sections (Artists, Moods, or legacy Playlists)
        val singleSections = singleCol?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        for (sec in unwrapSectionContents(singleSections)) {
            sec.optJSONObject("musicShelfRenderer")?.let { shelf ->
                val shelfItems = shelf.optJSONArray("contents")
                if (shelfItems != null) {
                    for (j in 0 until shelfItems.length()) {
                        val item = shelfItems.optJSONObject(j)
                            ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        parseTrackFromListItem(item, fallbackArtwork = artworkUrl)?.let { tracks.add(it) }
                    }
                }
            }
            sec.optJSONObject("musicPlaylistShelfRenderer")?.let { shelf ->
                val shelfItems = shelf.optJSONArray("contents")
                if (shelfItems != null) {
                    for (j in 0 until shelfItems.length()) {
                        val item = shelfItems.optJSONObject(j)
                            ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        parseTrackFromListItem(item, fallbackArtwork = artworkUrl)?.let { tracks.add(it) }
                    }
                }
            }
            sec.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                parseCarouselShelf(carousel)?.let { shelves.add(it) }
            }
            sec.optJSONObject("gridRenderer")?.let { grid ->
                parseGridShelf(grid)?.let { shelves.add(it) }
            }
        }

        return BrowseDetail(
            browseId = browseId,
            title = title.ifBlank { "Colección" },
            subtitle = subtitle,
            description = description,
            artworkUrl = artworkUrl,
            kind = inferKind(browseId, subtitle),
            tracks = tracks,
            shelves = shelves,
            playlistId = playlistId
        )
    }

    suspend fun getUpNextAndLyricsBrowseId(videoId: String): Pair<List<Track>, String?> {
        val body = JSONObject()
            .put("videoId", videoId)
            .put("isAudioOnly", true)
        val json = post("next", body)

        val tabs = json.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")

        val queue = mutableListOf<Track>()
        var lyricsBrowseId: String? = null

        if (tabs != null) {
            for (i in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
                val endpointBrowseId = tab.optJSONObject("endpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                if (endpointBrowseId != null && endpointBrowseId.startsWith("MPLYt")) {
                    lyricsBrowseId = endpointBrowseId
                }
                val queueContents = tab.optJSONObject("content")
                    ?.optJSONObject("musicQueueRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("playlistPanelRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (j in 0 until queueContents.length()) {
                    val panelItem = queueContents.optJSONObject(j)
                        ?.optJSONObject("playlistPanelVideoRenderer") ?: continue
                    parseTrackFromQueueItem(panelItem)?.let { queue.add(it) }
                }
            }
        }

        return queue to lyricsBrowseId
    }

    suspend fun getLyrics(lyricsBrowseId: String): String? {
        val json = post("browse", JSONObject().put("browseId", lyricsBrowseId))
        val sections = json.optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return null
        for (i in 0 until sections.length()) {
            val descRenderer = sections.optJSONObject(i)
                ?.optJSONObject("musicDescriptionShelfRenderer") ?: continue
            val text = extractRunsText(descRenderer.optJSONObject("description"))
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun unwrapSectionContents(sections: JSONArray?): List<JSONObject> {
        if (sections == null) return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val innerItemSection = section.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
            if (innerItemSection != null) {
                out.addAll(unwrapSectionContents(innerItemSection))
            } else {
                out.add(section)
            }
        }
        return out
    }

    internal fun parseShelvesFromBrowse(json: JSONObject): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        val contents = json.optJSONObject("contents") ?: return shelves

        val singleSectionArray = contents
            .optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        val twoCol = contents.optJSONObject("twoColumnBrowseResultsRenderer")
        val twoColPrimaryArray = twoCol
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        val twoColSecondaryArray = twoCol
            ?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        val allSections = unwrapSectionContents(singleSectionArray) +
            unwrapSectionContents(twoColPrimaryArray) +
            unwrapSectionContents(twoColSecondaryArray)

        for (section in allSections) {
            section.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                parseCarouselShelf(carousel)?.let { shelves.add(it) }
            }
            section.optJSONObject("musicShelfRenderer")?.let { shelf ->
                parseMusicShelf(shelf)?.let { shelves.add(it) }
            }
            section.optJSONObject("musicPlaylistShelfRenderer")?.let { shelf ->
                parseMusicShelf(shelf)?.let { shelves.add(it) }
            }
            section.optJSONObject("gridRenderer")?.let { grid ->
                parseGridShelf(grid)?.let { shelves.add(it) }
            }
        }
        return shelves
    }

    private fun parseMusicShelf(shelf: JSONObject): HomeShelf? {
        val title = extractRunsText(shelf.optJSONObject("title")).ifBlank { "Biblioteca" }
        val contents = shelf.optJSONArray("contents") ?: return null
        val tracks = mutableListOf<Track>()
        val cards = mutableListOf<BrowseCard>()

        for (i in 0 until contents.length()) {
            val container = contents.optJSONObject(i) ?: continue
            container.optJSONObject("musicTwoRowItemRenderer")?.let { twoRow ->
                parseTwoRowItem(twoRow)?.let { card ->
                    if (card.kind == ItemKind.SONG && card.videoId != null) {
                        tracks.add(
                            Track(
                                videoId = card.videoId,
                                title = card.title,
                                artist = card.subtitle.substringAfter("•").trim().ifBlank { card.subtitle },
                                artworkUrl = card.artworkUrl
                            )
                        )
                    } else {
                        cards.add(card)
                    }
                }
            }
            val item = container.optJSONObject("musicResponsiveListItemRenderer") ?: continue
            val track = parseTrackFromListItem(item)
            if (track != null) {
                tracks.add(track)
            } else {
                parseCardFromListItem(item, "")?.let { cards.add(it) }
            }
        }
        return if (tracks.isEmpty() && cards.isEmpty()) {
            null
        } else {
            HomeShelf(
                title = title,
                items = cards,
                tracks = tracks,
                isQuickPicks = tracks.isNotEmpty() && cards.isEmpty()
            )
        }
    }

    private fun parseCarouselShelf(carousel: JSONObject): HomeShelf? {
        val header = carousel.optJSONObject("header")
            ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        val title = extractRunsText(header?.optJSONObject("title")).ifBlank { return null }
        val strapline = extractRunsText(header?.optJSONObject("strapline")).takeIf { it.isNotBlank() }
        val contents = carousel.optJSONArray("contents") ?: return null

        val tracks = mutableListOf<Track>()
        val cards = mutableListOf<BrowseCard>()

        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            item.optJSONObject("musicResponsiveListItemRenderer")?.let { listItem ->
                val t = parseTrackFromListItem(listItem)
                if (t != null) tracks.add(t)
                else parseCardFromListItem(listItem, "")?.let { cards.add(it) }
            }
            item.optJSONObject("musicTwoRowItemRenderer")?.let { twoRow ->
                val card = parseTwoRowItem(twoRow)
                if (card != null) {
                    if (card.kind == ItemKind.SONG && card.videoId != null) {
                        tracks.add(
                            Track(
                                videoId = card.videoId,
                                title = card.title,
                                artist = card.subtitle.substringAfter("•").trim().ifBlank { card.subtitle },
                                artworkUrl = card.artworkUrl
                            )
                        )
                    } else {
                        cards.add(card)
                    }
                }
            }
        }

        if (tracks.isEmpty() && cards.isEmpty()) return null
        return HomeShelf(
            title = title,
            subtitle = strapline,
            items = cards,
            tracks = tracks,
            isQuickPicks = tracks.isNotEmpty() && cards.isEmpty()
        )
    }

    private fun parseGridShelf(grid: JSONObject): HomeShelf? {
        val title = extractRunsText(
            grid.optJSONObject("header")?.optJSONObject("gridHeaderRenderer")?.optJSONObject("title")
        ).ifBlank { "Explorar" }
        val items = grid.optJSONArray("items") ?: return null
        val cards = mutableListOf<BrowseCard>()
        val tracks = mutableListOf<Track>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            item.optJSONObject("musicTwoRowItemRenderer")?.let { twoRow ->
                parseTwoRowItem(twoRow)?.let { card ->
                    if (card.kind == ItemKind.SONG && card.videoId != null) {
                        tracks.add(
                            Track(
                                videoId = card.videoId,
                                title = card.title,
                                artist = card.subtitle.substringAfter("•").trim().ifBlank { card.subtitle },
                                artworkUrl = card.artworkUrl
                            )
                        )
                    } else {
                        cards.add(card)
                    }
                }
            }
            item.optJSONObject("musicResponsiveListItemRenderer")?.let { listItem ->
                parseCardFromListItem(listItem, "")?.let { cards.add(it) }
            }
        }
        return if (cards.isEmpty() && tracks.isEmpty()) null else HomeShelf(title = title, items = cards, tracks = tracks)
    }

    private fun parseTwoRowItem(renderer: JSONObject): BrowseCard? {
        val titleObj = renderer.optJSONObject("title")
        val title = extractRunsText(titleObj)
        if (title.isBlank()) return null
        val subtitle = extractRunsText(renderer.optJSONObject("subtitle"))
        val artwork = extractBestThumbnail(
            renderer.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        )
        val nav = renderer.optJSONObject("navigationEndpoint")
            ?: titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
        val watchVideoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }
        val browse = nav?.optJSONObject("browseEndpoint")
        var browseId = browse?.optString("browseId").orEmpty()
        val params = browse?.optString("params")?.takeIf { it.isNotBlank() }
        val pageType = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType").orEmpty()

        if (watchVideoId != null) {
            return BrowseCard(
                id = watchVideoId,
                title = title,
                subtitle = subtitle,
                artworkUrl = artwork,
                kind = ItemKind.SONG,
                videoId = watchVideoId
            )
        }
        if (browseId.isBlank()) {
            val playPlId = renderer.optJSONObject("thumbnailOverlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchPlaylistEndpoint")
                ?.optString("playlistId")
                ?.takeIf { it.isNotBlank() }
            if (playPlId != null) {
                browseId = if (playPlId.startsWith("VL")) playPlId else "VL$playPlId"
            }
        }
        if (browseId.isBlank()) return null
        return BrowseCard(
            id = browseId,
            title = title,
            subtitle = subtitle,
            artworkUrl = artwork,
            kind = inferKind(browseId, pageType.ifBlank { subtitle }),
            params = params
        )
    }

    private fun parseTrackFromListItem(
        renderer: JSONObject,
        fallbackArtwork: String = "",
        fallbackAlbum: String? = null,
        fallbackAlbumId: String? = null,
        fallbackArtist: String? = null
    ): Track? {
        val overlayVideoId = renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
        val playlistItemVideoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")

        val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
        val col0 = flexColumns.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        val titleRuns = col0?.optJSONObject("text")?.optJSONArray("runs")
        val title = extractRunsArrayText(titleRuns)
        if (title.isBlank()) return null

        val col0VideoId = titleRuns?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")

        val videoId = listOfNotNull(playlistItemVideoId, overlayVideoId, col0VideoId)
            .firstOrNull { it.isNotBlank() } ?: return null

        var artist = ""
        var artistId: String? = null
        var album: String? = fallbackAlbum
        var albumId: String? = fallbackAlbumId
        var durationText = ""

        val col1Runs = flexColumns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        if (col1Runs != null) {
            val artistParts = mutableListOf<String>()
            for (i in 0 until col1Runs.length()) {
                val run = col1Runs.optJSONObject(i) ?: continue
                val text = run.optString("text").trim()
                if (text.isBlank() || text == "•" || text == "·") continue
                val browseId = run.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId").orEmpty()
                when {
                    browseId.startsWith("UC") -> {
                        artistParts.add(text)
                        if (artistId == null) artistId = browseId
                    }
                    browseId.startsWith("MPREb") -> {
                        album = text
                        albumId = browseId
                    }
                    DURATION_REGEX.matches(text) -> {
                        durationText = text
                    }
                    !isCategoryLabel(text) && !isStatsLabel(text) && artistParts.isEmpty() -> {
                        artistParts.add(text)
                    }
                }
            }
            artist = artistParts.joinToString(", ")
        }

        if (artist.isBlank() && !fallbackArtist.isNullOrBlank()) {
            artist = fallbackArtist
        }

        // Fixed column often contains duration on albums & playlists
        if (durationText.isBlank()) {
            val fixedCols = renderer.optJSONArray("fixedColumns")
            val fixedText = extractRunsText(
                fixedCols?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")
            )
            if (DURATION_REGEX.matches(fixedText.trim())) {
                durationText = fixedText.trim()
            }
        }

        val col2Runs = flexColumns.optJSONObject(2)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        if (album.isNullOrBlank() && col2Runs != null) {
            for (i in 0 until col2Runs.length()) {
                val run = col2Runs.optJSONObject(i) ?: continue
                val text = run.optString("text").trim()
                val browseId = run.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId").orEmpty()
                if (browseId.startsWith("MPREb") || (text.isNotBlank() && text != "•")) {
                    album = text
                    if (browseId.isNotBlank()) albumId = browseId
                    break
                }
            }
        }

        val artwork = extractBestThumbnail(
            renderer.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
        ).ifBlank { fallbackArtwork }

        return Track(
            videoId = videoId,
            title = title,
            artist = artist.ifBlank { "YouTube Music" },
            artistId = artistId,
            album = album,
            albumId = albumId,
            artworkUrl = artwork.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
            durationText = durationText,
            durationMs = parseDurationMs(durationText)
        )
    }

    private fun parseCardFromListItem(renderer: JSONObject, pageTypeHint: String): BrowseCard? {
        val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
        val col0TextObj = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val browseEndpoint = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?: col0TextObj?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?: return null
        val browseId = browseEndpoint.optString("browseId").takeIf { it.isNotBlank() } ?: return null
        val title = extractRunsText(col0TextObj)
        if (title.isBlank()) return null
        val subtitle = extractRunsText(
            flexColumns.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        )
        val artwork = extractBestThumbnail(
            renderer.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
        )
        val resolvedPageType = browseEndpoint.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
            ?.ifBlank { pageTypeHint }
            ?: pageTypeHint
        return BrowseCard(
            id = browseId,
            title = title,
            subtitle = subtitle,
            artworkUrl = artwork,
            kind = inferKind(browseId, resolvedPageType.ifBlank { subtitle }),
            params = browseEndpoint.optString("params").takeIf { it.isNotBlank() }
        )
    }

    private fun parseTrackFromQueueItem(item: JSONObject): Track? {
        val videoId = item.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = extractRunsText(item.optJSONObject("title")).ifBlank { return null }
        val longByline = item.optJSONObject("longBylineText")?.optJSONArray("runs")
        val shortByline = extractRunsText(item.optJSONObject("shortBylineText"))
        var artist = ""
        var artistId: String? = null
        var album: String? = null
        var albumId: String? = null

        if (longByline != null) {
            for (i in 0 until longByline.length()) {
                val run = longByline.optJSONObject(i) ?: continue
                val text = run.optString("text").trim()
                val browseId = run.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId").orEmpty()
                if (browseId.startsWith("UC")) {
                    if (artist.isBlank()) {
                        artist = text
                        artistId = browseId
                    }
                } else if (browseId.startsWith("MPREb")) {
                    album = text
                    albumId = browseId
                }
            }
        }
        val durationText = extractRunsText(item.optJSONObject("lengthText"))
        val artwork = extractBestThumbnail(item.optJSONObject("thumbnail"))
        return Track(
            videoId = videoId,
            title = title,
            artist = artist.ifBlank { shortByline.ifBlank { "YouTube Music" } },
            artistId = artistId,
            album = album,
            albumId = albumId,
            artworkUrl = artwork.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
            durationText = durationText,
            durationMs = parseDurationMs(durationText)
        )
    }

    private fun parseMoodCategories(json: JSONObject): List<MoodCategory> {
        val categories = mutableListOf<MoodCategory>()
        val sections = json.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return categories

        for (i in 0 until sections.length()) {
            val items = sections.optJSONObject(i)
                ?.optJSONObject("gridRenderer")
                ?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val btn = items.optJSONObject(j)?.optJSONObject("musicNavigationButtonRenderer") ?: continue
                val title = extractRunsText(btn.optJSONObject("buttonText"))
                val browse = btn.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint") ?: continue
                val browseId = browse.optString("browseId").takeIf { it.isNotBlank() } ?: continue
                val params = browse.optString("params").takeIf { it.isNotBlank() }
                val color = btn.optJSONObject("solid")?.optLong("leftStripeColor") ?: 0xFFFA2D48
                if (title.isNotBlank()) {
                    categories.add(MoodCategory(title, browseId, params, color))
                }
            }
        }
        return categories
    }

    private fun defaultMoodCategories(): List<MoodCategory> = listOf(
        MoodCategory("Pop Latino", "FEmusic_moods_and_genres_category", "ggMPOg1uX1lMbVZ2ck9lS3d3", 0xFFFA2D48),
        MoodCategory("Chill", "FEmusic_moods_and_genres_category", "ggMPOg1uX1JOQWZFeDByc2Jm", 0xFF5E5CE6),
        MoodCategory("Entrenamiento", "FEmusic_moods_and_genres_category", "ggMPOg1uX1lBck5hS1J0Q1E%3D", 0xFFFF375F),
        MoodCategory("Enfoque", "FEmusic_moods_and_genres_category", "ggMPOg1uX1FCN2xUQlZ6Y2c%3D", 0xFF30D158),
        MoodCategory("Fiesta", "FEmusic_moods_and_genres_category", "ggMPOg1uX1lXTVp5dU1YQkU%3D", 0xFFFF9F0A),
        MoodCategory("Romance", "FEmusic_moods_and_genres_category", "ggMPOg1uX1J1VUVuQ1d5S0M%3D", 0xFFBF5AF2)
    )

    private fun extractRunsText(obj: JSONObject?): String {
        return extractRunsArrayText(obj?.optJSONArray("runs"))
    }

    private fun extractRunsArrayText(runs: JSONArray?): String {
        if (runs == null) return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().trim()
    }

    private fun extractBestThumbnail(thumbnailWrapper: JSONObject?): String {
        val list = thumbnailWrapper?.optJSONArray("thumbnails")
            ?: thumbnailWrapper?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
            ?: return ""
        var bestUrl = ""
        var bestWidth = -1
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val url = item.optString("url")
            val width = item.optInt("width", 0)
            if (url.isNotBlank() && width >= bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return upgradeArtworkQuality(bestUrl)
    }

    private fun upgradeArtworkQuality(url: String): String {
        if (url.isBlank()) return ""
        return if (url.contains("lh3.googleusercontent.com") && url.contains("=w")) {
            url.replace(Regex("=w\\d+-h\\d+[^\"&]*"), "=w600-h600-l90-rj")
        } else if (url.startsWith("//")) {
            "https:$url"
        } else {
            url
        }
    }

    private fun inferKind(browseId: String, hint: String): ItemKind {
        val h = hint.lowercase()
        return when {
            browseId.startsWith("MPREb") || h.contains("album") || h.contains("álbum") || h.contains("ep") || h.contains("single") || h.contains("sencillo") -> ItemKind.ALBUM
            browseId.startsWith("UC") || h.contains("artist") || h.contains("artista") -> ItemKind.ARTIST
            browseId.startsWith("FEmusic_moods") -> ItemKind.MOOD
            else -> ItemKind.PLAYLIST
        }
    }

    private fun findPlaylistIdInJson(obj: JSONObject): String? {
        val str = obj.toString()
        val match = Regex("\"playlistId\":\"([^\"]+)\"").find(str)
        return match?.groupValues?.getOrNull(1)
    }

    private fun isCategoryLabel(text: String): Boolean {
        val lower = text.lowercase()
        return lower in setOf("song", "canción", "video", "album", "álbum", "playlist", "artist", "artista", "single", "sencillo", "ep")
    }

    private fun isStatsLabel(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("views") || lower.contains("vistas") || lower.contains("plays") || lower.contains("reproducciones")
    }

    private fun parseDurationMs(durationText: String): Long {
        val parts = durationText.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            else -> 0L
        }
    }

    private val DURATION_REGEX = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
}
