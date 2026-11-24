package com.example.data.network

import com.example.model.ArtistCredit
import com.example.model.BrowseCard
import com.example.model.BrowseDetail
import com.example.model.HomeFilterChip
import com.example.model.HomeShelf
import com.example.model.ItemKind
import com.example.model.MoodCategory
import com.example.model.ResolvedTrackMetadata
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.Track
import com.example.model.YouTubeAccountInfo
import com.example.model.YouTubeAuthSessionPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

class YouTubeMusicClient(
    private val httpClient: OkHttpClient
) {
    @Volatile
    private var sessionContext: YouTubeAuthSessionPayload? = null

    @Volatile
    private var bootstrappedVisitorData: String = ""

    fun setSessionContext(payload: YouTubeAuthSessionPayload?) {
        val clean = payload?.takeIf { it.cookies.isNotBlank() }
        sessionContext = clean
        if (!clean?.visitorData.isNullOrBlank()) {
            bootstrappedVisitorData = clean!!.visitorData
        }
    }

    fun setSessionCookies(cookies: String?) {
        val clean = cookies?.takeIf { it.isNotBlank() }
        if (clean == null) {
            sessionContext = null
            return
        }
        val existing = sessionContext
        sessionContext = if (existing != null && existing.cookies == clean) {
            existing
        } else {
            YouTubeAuthSessionPayload(cookies = clean)
        }
    }

    fun getCurrentSessionContext(): YouTubeAuthSessionPayload? = sessionContext

    companion object {
        private const val BASE_URL = "https://music.youtube.com/youtubei/v1/"
        private const val DEFAULT_CLIENT_VERSION = "1.20250115.01.00"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val ORIGIN = "https://music.youtube.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val YTCFG_KV_REGEX = Regex(
            """"(VISITOR_DATA|DATASYNC_ID|DELEGATED_SESSION_ID|INNERTUBE_CLIENT_VERSION)"\s*:\s*"([^"]*)""""
        )
        private val YTCFG_AUTH_USER_REGEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)"?""")
        private val YTCFG_LOGGED_IN_REGEX = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")

        internal fun extractYtCfgFromHtml(html: String, cookies: String = ""): YouTubeAuthSessionPayload? {
            if (html.isBlank()) return null
            val values = mutableMapOf<String, String>()
            YTCFG_KV_REGEX.findAll(html).forEach { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                    .replace("\\u003d", "=")
                    .replace("\\/", "/")
                if (value.isNotBlank() && key !in values) {
                    values[key] = value
                }
            }
            val authUser = YTCFG_AUTH_USER_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "0" }
            val loggedIn = YTCFG_LOGGED_IN_REGEX.find(html)?.groupValues?.getOrNull(1) == "true"
            if (!loggedIn && values.isEmpty()) return null
            return YouTubeAuthSessionPayload(
                cookies = cookies,
                visitorData = values["VISITOR_DATA"].orEmpty(),
                dataSyncId = values["DATASYNC_ID"].orEmpty(),
                delegatedSessionId = values["DELEGATED_SESSION_ID"].orEmpty(),
                authUser = authUser,
                clientVersion = values["INNERTUBE_CLIENT_VERSION"].orEmpty()
            )
        }

        fun defaultHomeFilterChips(selectedTitle: String = "Todo"): List<HomeFilterChip> {
            val specs = listOf(
                "Todo" to null,
                "Energía" to "musica energia ritmo hits",
                "Relajación" to "musica chill relajante acustico",
                "Entrenamiento" to "workout gym motivacion hits",
                "Concentración" to "lofi focus instrumental estudiar",
                "Fiesta" to "musica fiesta reggaeton dance hits",
                "Romance" to "baladas pop romantico exitos"
            )
            return specs.map { (label, query) ->
                HomeFilterChip(
                    title = label,
                    browseId = "FEmusic_home",
                    params = null,
                    isSelected = label.equals(selectedTitle, ignoreCase = true) ||
                        (selectedTitle.isBlank() && label == "Todo"),
                    fallbackSearchQuery = query
                )
            }
        }
    }

    private fun buildContextJson(): JSONObject {
        val locale = Locale.getDefault()
        val hl = if (locale.language.isNotBlank()) locale.language else "es"
        val gl = if (locale.country.length == 2) locale.country else "US"
        val active = sessionContext
        val clientVersion = active?.clientVersion?.ifBlank { DEFAULT_CLIENT_VERSION } ?: DEFAULT_CLIENT_VERSION
        val visitor = active?.visitorData?.ifBlank { bootstrappedVisitorData } ?: bootstrappedVisitorData

        val clientJson = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", clientVersion)
            .put("hl", hl)
            .put("gl", gl)
        if (visitor.isNotBlank()) {
            clientJson.put("visitorData", visitor)
        }

        val contextJson = JSONObject().put("client", clientJson)
        val userJson = JSONObject().put("lockedSafetyMode", false)
        val delegated = active?.delegatedSessionId?.trim().orEmpty()
        if (delegated.isNotBlank()) {
            userJson.put("onBehalfOfUser", delegated)
        }
        contextJson.put("user", userJson)
        return contextJson
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

    /**
     * If session cookies were loaded without ytcfg metadata (e.g. pasted cookies or older DB row),
     * fetch music.youtube.com once with the active cookies so visitorData / delegatedSessionId / authUser
     * are populated exactly like BitChord's WebView YTCFG_PROBE.
     */
    suspend fun ensureSessionBootstrapped(): YouTubeAuthSessionPayload? = withContext(Dispatchers.IO) {
        val current = sessionContext ?: return@withContext null
        if (current.visitorData.isNotBlank() && current.clientVersion.isNotBlank()) {
            return@withContext current
        }
        runCatching {
            val req = Request.Builder()
                .url("$ORIGIN/")
                .header("User-Agent", USER_AGENT)
                .header("Cookie", current.cookies)
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use current
                val html = resp.body?.string().orEmpty()
                val extracted = extractYtCfgFromHtml(html, current.cookies) ?: return@use current
                val merged = current.copy(
                    visitorData = current.visitorData.ifBlank { extracted.visitorData },
                    dataSyncId = current.dataSyncId.ifBlank { extracted.dataSyncId },
                    delegatedSessionId = current.delegatedSessionId.ifBlank { extracted.delegatedSessionId },
                    authUser = if (current.authUser.isNotBlank() && current.authUser != "0") current.authUser else extracted.authUser,
                    clientVersion = current.clientVersion.ifBlank { extracted.clientVersion }
                )
                sessionContext = merged
                if (merged.visitorData.isNotBlank()) {
                    bootstrappedVisitorData = merged.visitorData
                }
                merged
            }
        }.getOrDefault(current)
    }

    private suspend fun post(
        endpoint: String,
        body: JSONObject,
        continuationToken: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val payloadBody = JSONObject(body.toString())
        payloadBody.put("context", buildContextJson())
        if (!continuationToken.isNullOrBlank()) {
            payloadBody.put("continuation", continuationToken)
        }

        val active = sessionContext
        val clientVersion = active?.clientVersion?.ifBlank { DEFAULT_CLIENT_VERSION } ?: DEFAULT_CLIENT_VERSION
        val visitor = active?.visitorData?.ifBlank { bootstrappedVisitorData } ?: bootstrappedVisitorData

        val url = if (!continuationToken.isNullOrBlank()) {
            val enc = URLEncoder.encode(continuationToken, "UTF-8")
            "$BASE_URL$endpoint?ctoken=$enc&continuation=$enc&type=next&prettyPrint=false"
        } else {
            "$BASE_URL$endpoint?prettyPrint=false"
        }

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", clientVersion)

        if (visitor.isNotBlank()) {
            builder.header("X-Goog-Visitor-Id", visitor)
        }

        val cookies = active?.cookies
        if (!cookies.isNullOrBlank()) {
            builder.header("Cookie", cookies)
            builder.header("X-Origin", ORIGIN)
            builder.header("X-Goog-AuthUser", active.authUser.ifBlank { "0" })
            if (active.delegatedSessionId.isNotBlank()) {
                builder.header("X-Goog-PageId", active.delegatedSessionId)
            }
            buildAuthorizationHeader(cookies, ORIGIN)?.let { auth ->
                builder.header("Authorization", auth)
            }
        }

        val request = builder
            .post(payloadBody.toString().toRequestBody(JSON_MEDIA_TYPE))
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
        if (sessionContext?.cookies.isNullOrBlank()) return null
        ensureSessionBootstrapped()
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
        if (sessionContext?.cookies.isNullOrBlank()) return emptyList()
        ensureSessionBootstrapped()

        val primaryItems = runCatching {
            browseAllShelvesPaginated("FEmusic_liked_playlists", maxPages = 4).flatMap { it.items }
        }.getOrDefault(emptyList())

        val landingItems = runCatching {
            parseShelvesFromBrowse(post("browse", JSONObject().put("browseId", "FEmusic_library_landing")))
                .flatMap { it.items }
                .filter { it.kind == ItemKind.PLAYLIST }
        }.getOrDefault(emptyList())

        val combined = (primaryItems + landingItems)
            .filter { it.id.isNotBlank() && it.id != "VLLM" && it.id != "VLSE" }
            .distinctBy { it.id }

        if (combined.isNotEmpty()) return combined
        return (primaryItems + landingItems).filter { it.id.isNotBlank() }.distinctBy { it.id }
    }

    suspend fun getLibrarySavedCollections(): List<BrowseCard> {
        if (sessionContext?.cookies.isNullOrBlank()) return emptyList()
        ensureSessionBootstrapped()

        val albumsItems = runCatching {
            browseAllShelvesPaginated("FEmusic_liked_albums", maxPages = 4).flatMap { it.items }
        }.getOrDefault(emptyList())

        val landingItems = runCatching {
            parseShelvesFromBrowse(post("browse", JSONObject().put("browseId", "FEmusic_library_landing")))
                .flatMap { it.items }
                .filter { it.kind == ItemKind.ALBUM }
        }.getOrDefault(emptyList())

        return (albumsItems + landingItems)
            .filter { it.id.isNotBlank() && it.kind != ItemKind.SONG && it.id != "VLLM" }
            .distinctBy { it.id }
    }

    suspend fun getLibraryArtists(): List<BrowseCard> {
        if (sessionContext?.cookies.isNullOrBlank()) return emptyList()
        ensureSessionBootstrapped()

        val subscribedArtists = runCatching {
            browseAllShelvesPaginated("FEmusic_library_corpus_artists", maxPages = 4).flatMap { it.items }
        }.getOrDefault(emptyList())

        val libraryTrackArtists = runCatching {
            browseAllShelvesPaginated("FEmusic_library_corpus_track_artists", maxPages = 3).flatMap { it.items }
        }.getOrDefault(emptyList())

        return (subscribedArtists + libraryTrackArtists)
            .filter { it.id.isNotBlank() }
            .map { card ->
                if (card.id.startsWith("UC")) card.copy(kind = ItemKind.ARTIST) else card
            }
            .distinctBy { it.id }
    }

    suspend fun getLibraryLikedSongs(): List<Track> {
        if (sessionContext?.cookies.isNullOrBlank()) return emptyList()
        ensureSessionBootstrapped()

        val fromVllm = runCatching {
            getBrowseDetail("VLLM", maxContinuationPages = 5).tracks
        }.getOrDefault(emptyList())
        if (fromVllm.isNotEmpty()) return fromVllm.distinctBy { it.videoId }

        val fromLm = runCatching {
            getBrowseDetail("LM", maxContinuationPages = 5).tracks
        }.getOrDefault(emptyList())
        if (fromLm.isNotEmpty()) return fromLm.distinctBy { it.videoId }

        val fromLikedVideosDetail = runCatching {
            getBrowseDetail("FEmusic_liked_videos", maxContinuationPages = 5).tracks
        }.getOrDefault(emptyList())
        if (fromLikedVideosDetail.isNotEmpty()) return fromLikedVideosDetail.distinctBy { it.videoId }

        val shelves = browseAllShelvesPaginated("FEmusic_liked_videos", maxPages = 5)
        return shelves.flatMap { it.tracks }.distinctBy { it.videoId }
    }

    suspend fun getLibraryUploadedSongs(): List<Track> {
        if (sessionContext?.cookies.isNullOrBlank()) return emptyList()
        ensureSessionBootstrapped()
        val shelves = runCatching {
            browseAllShelvesPaginated("FEmusic_library_privately_owned_tracks", maxPages = 3)
        }.getOrDefault(emptyList())
        return shelves.flatMap { it.tracks }.distinctBy { it.videoId }
    }

    suspend fun getYouTubeHistory(): List<Track> {
        if (sessionContext?.cookies.isNullOrBlank()) return emptyList()
        ensureSessionBootstrapped()
        val shelves = browseAllShelvesPaginated("FEmusic_history", maxPages = 2)
        return shelves.flatMap { it.tracks }.distinctBy { it.videoId }.take(60)
    }

    // --- Two-Way YouTube Music Account Write Operations (BitChord Parity) ---

    suspend fun setTrackLikeStatus(videoId: String, liked: Boolean): Boolean {
        if (sessionContext?.cookies.isNullOrBlank() || videoId.isBlank()) return false
        ensureSessionBootstrapped()
        val endpoint = if (liked) "like/like" else "like/removelike"
        return runCatching {
            post(endpoint, JSONObject().put("target", JSONObject().put("videoId", videoId)))
            true
        }.getOrDefault(false)
    }

    suspend fun setCollectionLikeStatus(playlistId: String, saved: Boolean): Boolean {
        if (sessionContext?.cookies.isNullOrBlank() || playlistId.isBlank()) return false
        ensureSessionBootstrapped()
        val cleanPl = playlistId.removePrefix("VL")
        val endpoint = if (saved) "like/like" else "like/removelike"
        return runCatching {
            post(endpoint, JSONObject().put("target", JSONObject().put("playlistId", cleanPl)))
            true
        }.getOrDefault(false)
    }

    suspend fun setArtistSubscription(channelId: String, subscribed: Boolean): Boolean {
        if (sessionContext?.cookies.isNullOrBlank() || channelId.isBlank()) return false
        ensureSessionBootstrapped()
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        return runCatching {
            post(endpoint, JSONObject().put("channelIds", JSONArray().put(channelId)))
            true
        }.getOrDefault(false)
    }

    suspend fun createYouTubePlaylist(
        title: String,
        description: String = "",
        videoIds: List<String> = emptyList()
    ): String? {
        if (sessionContext?.cookies.isNullOrBlank() || title.isBlank()) return null
        ensureSessionBootstrapped()
        return runCatching {
            val body = JSONObject()
                .put("title", title.trim())
                .put("privacyStatus", "PRIVATE")
            if (description.isNotBlank()) body.put("description", description.trim())
            if (videoIds.isNotEmpty()) body.put("videoIds", JSONArray(videoIds))
            val resp = post("playlist/create", body)
            resp.optString("playlistId").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun addVideoToYouTubePlaylist(playlistId: String, videoId: String): Boolean {
        if (sessionContext?.cookies.isNullOrBlank() || playlistId.isBlank() || videoId.isBlank()) return false
        ensureSessionBootstrapped()
        val cleanPl = playlistId.removePrefix("VL")
        val actions = JSONArray().put(
            JSONObject()
                .put("action", "ACTION_ADD_VIDEO")
                .put("addedVideoId", videoId)
                .put("dedupeOption", "DEDUPE_OPTION_SKIP")
        )
        return runCatching {
            post(
                "browse/edit_playlist",
                JSONObject()
                    .put("playlistId", cleanPl)
                    .put("actions", actions)
            )
            true
        }.getOrDefault(false)
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
        return getHomeFeed(params = null).first
    }

    suspend fun getHomeFeed(params: String? = null): Pair<List<HomeShelf>, List<HomeFilterChip>> {
        val body = JSONObject().put("browseId", "FEmusic_home")
        if (!params.isNullOrBlank()) {
            body.put("params", params)
        }
        val json = post("browse", body)
        val shelves = parseShelvesFromBrowse(json)
        val parsedChips = parseHomeChips(json, activeParams = params)
        val chips = if (parsedChips.isNotEmpty()) parsedChips else defaultHomeFilterChips(selectedTitle = if (params.isNullOrBlank()) "Todo" else "")
        return shelves to chips
    }

    suspend fun getHomeFeedForChip(chip: HomeFilterChip): Pair<List<HomeShelf>, List<HomeFilterChip>> {
        if (chip.params.isNullOrBlank() && chip.fallbackSearchQuery.isNullOrBlank()) {
            return getHomeFeed(params = null)
        }
        if (!chip.params.isNullOrBlank() || chip.browseId != "FEmusic_home") {
            val browseResult = runCatching {
                val body = JSONObject().put("browseId", chip.browseId.ifBlank { "FEmusic_home" })
                chip.params?.takeIf { it.isNotBlank() }?.let { body.put("params", it) }
                val json = post("browse", body)
                val shelves = parseShelvesFromBrowse(json)
                val chips = parseHomeChips(json, activeParams = chip.params)
                shelves to chips
            }.getOrNull()
            if (browseResult != null && browseResult.first.isNotEmpty()) {
                val finalChips = browseResult.second.ifEmpty {
                    defaultHomeFilterChips(selectedTitle = chip.title)
                }
                return browseResult.first to finalChips
            }
        }
        val query = chip.fallbackSearchQuery?.takeIf { it.isNotBlank() } ?: "${chip.title} musica mix"
        val songsRes = runCatching { search(query, SearchFilter.SONGS).songs }.getOrDefault(emptyList())
        val playlistsRes = runCatching { search(query, SearchFilter.PLAYLISTS).playlists }.getOrDefault(emptyList())
        val shelves = buildList {
            if (songsRes.isNotEmpty()) {
                add(
                    HomeShelf(
                        title = "Selección rápida • ${chip.title}",
                        subtitle = "Ambiente seleccionado",
                        tracks = songsRes.take(16),
                        isQuickPicks = true
                    )
                )
            }
            if (playlistsRes.isNotEmpty()) {
                add(
                    HomeShelf(
                        title = "Playlists de ${chip.title}",
                        subtitle = "Listas recomendadas",
                        items = playlistsRes.take(10)
                    )
                )
            }
        }
        return shelves to defaultHomeFilterChips(selectedTitle = chip.title)
    }

    internal fun parseHomeChips(json: JSONObject, activeParams: String? = null): List<HomeFilterChip> {
        val contents = json.optJSONObject("contents") ?: return emptyList()
        val singleTabSectionList = contents
            .optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")

        val chipCloud = singleTabSectionList
            ?.optJSONObject("header")
            ?.optJSONObject("chipCloudRenderer")
            ?: json.optJSONObject("header")?.optJSONObject("chipCloudRenderer")

        val chipsArr = chipCloud?.optJSONArray("chips") ?: return emptyList()
        val parsed = mutableListOf<HomeFilterChip>()
        for (i in 0 until chipsArr.length()) {
            val chipObj = chipsArr.optJSONObject(i)?.optJSONObject("chipCloudChipRenderer") ?: continue
            val title = extractRunsText(chipObj.optJSONObject("text")).trim()
            if (title.isBlank()) continue
            val browseEndpoint = chipObj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val browseId = browseEndpoint?.optString("browseId")?.ifBlank { "FEmusic_home" } ?: "FEmusic_home"
            val params = browseEndpoint?.optString("params")?.takeIf { it.isNotBlank() }
            val selected = chipObj.optBoolean("isSelected", false) ||
                (!activeParams.isNullOrBlank() && params == activeParams)
            parsed.add(
                HomeFilterChip(
                    title = title,
                    browseId = browseId,
                    params = params,
                    isSelected = selected,
                    fallbackSearchQuery = "$title musica"
                )
            )
        }
        if (parsed.isEmpty()) return emptyList()
        val hasResetChip = parsed.any { it.params == null && it.browseId == "FEmusic_home" }
        return if (hasResetChip) {
            parsed
        } else {
            val anySelected = parsed.any { it.isSelected }
            listOf(
                HomeFilterChip(
                    title = "Todo",
                    browseId = "FEmusic_home",
                    params = null,
                    isSelected = !anySelected && activeParams.isNullOrBlank()
                )
            ) + parsed
        }
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
                    val (parsedArtist, parsedDuration) = parseArtistAndDurationFromSubtitle(subtitle)
                    songs.add(
                        Track(
                            videoId = videoId,
                            title = title,
                            artist = parsedArtist.ifBlank { "YouTube Music" },
                            artworkUrl = artwork,
                            durationText = parsedDuration,
                            durationMs = parseDurationMs(parsedDuration)
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

    suspend fun getBrowseDetail(
        browseId: String,
        params: String? = null,
        maxContinuationPages: Int = 4
    ): BrowseDetail {
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

        val fallbackAlbumArg = if (browseId.startsWith("MPREb")) title else null
        val fallbackAlbumIdArg = if (browseId.startsWith("MPREb")) browseId else null
        val fallbackArtistArg = if (browseId.startsWith("MPREb")) {
            subtitle.substringAfter("•").substringBefore("•").trim()
        } else null

        var nextToken: String? = null

        // Parse secondary contents (Album / Playlist tracks in twoColumn layout)
        val secondarySections = twoCol?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

        for (sec in unwrapSectionContents(secondarySections)) {
            val shelfObj = sec.optJSONObject("musicShelfRenderer")
                ?: sec.optJSONObject("musicPlaylistShelfRenderer")
            val shelfItems = shelfObj?.optJSONArray("contents")
            if (shelfItems != null) {
                parseItemsIntoCollections(
                    items = shelfItems,
                    fallbackArtwork = artworkUrl,
                    fallbackAlbum = fallbackAlbumArg,
                    fallbackAlbumId = fallbackAlbumIdArg,
                    fallbackArtist = fallbackArtistArg,
                    outTracks = tracks,
                    outCards = null
                )
                if (nextToken == null) nextToken = extractContinuationFromContainer(shelfObj)
            }
            if (nextToken == null) {
                nextToken = extractContinuationFromContainer(sec)
            }
            sec.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                parseCarouselShelf(carousel)?.let { shelves.add(it) }
            }
        }
        if (nextToken == null) {
            nextToken = extractContinuationFromContainer(
                twoCol?.optJSONObject("secondaryContents")?.optJSONObject("sectionListRenderer")
            )
        }

        // Parse singleColumn sections (Artists, Moods, or legacy Playlists)
        val singleSectionRenderer = singleCol?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
        val singleSections = singleSectionRenderer?.optJSONArray("contents")

        for (sec in unwrapSectionContents(singleSections)) {
            sec.optJSONObject("musicShelfRenderer")?.let { shelf ->
                shelf.optJSONArray("contents")?.let { shelfItems ->
                    parseItemsIntoCollections(shelfItems, artworkUrl, null, null, null, tracks, null)
                }
                if (nextToken == null) nextToken = extractContinuationFromContainer(shelf)
            }
            sec.optJSONObject("musicPlaylistShelfRenderer")?.let { shelf ->
                shelf.optJSONArray("contents")?.let { shelfItems ->
                    parseItemsIntoCollections(shelfItems, artworkUrl, null, null, null, tracks, null)
                }
                if (nextToken == null) nextToken = extractContinuationFromContainer(shelf)
            }
            sec.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                parseCarouselShelf(carousel)?.let { shelves.add(it) }
            }
            sec.optJSONObject("gridRenderer")?.let { grid ->
                parseGridShelf(grid)?.let { shelves.add(it) }
                if (nextToken == null) nextToken = extractContinuationFromContainer(grid)
            }
        }
        if (nextToken == null) {
            nextToken = extractContinuationFromContainer(singleSectionRenderer) ?: extractContinuationToken(json)
        }

        // Fetch continuation pages (important for full Liked Songs VLLM & large YouTube Music playlists)
        var page = 0
        val visitedTokens = mutableSetOf<String>()
        while (!nextToken.isNullOrBlank() && page < maxContinuationPages && visitedTokens.add(nextToken!!)) {
            page++
            val contJson = runCatching { post("browse", JSONObject(), continuationToken = nextToken) }.getOrNull()
                ?: break
            val (contTracks, _, newNext) = parseBrowseContinuationItems(
                json = contJson,
                fallbackArtwork = artworkUrl,
                fallbackAlbum = fallbackAlbumArg,
                fallbackAlbumId = fallbackAlbumIdArg,
                fallbackArtist = fallbackArtistArg
            )
            if (contTracks.isNotEmpty()) {
                tracks.addAll(contTracks)
            }
            nextToken = newNext
        }

        return BrowseDetail(
            browseId = browseId,
            title = title.ifBlank { "Colección" },
            subtitle = subtitle,
            description = description,
            artworkUrl = artworkUrl,
            kind = inferKind(browseId, subtitle),
            tracks = tracks.distinctBy { it.videoId },
            shelves = shelves,
            playlistId = playlistId
        )
    }

    private suspend fun browseAllShelvesPaginated(
        browseId: String,
        maxPages: Int = 4
    ): List<HomeShelf> {
        val firstJson = post("browse", JSONObject().put("browseId", browseId))
        val allShelves = parseShelvesFromBrowse(firstJson).toMutableList()
        val extraTracks = mutableListOf<Track>()
        val extraCards = mutableListOf<BrowseCard>()

        var nextToken = extractContinuationToken(firstJson)
        var page = 0
        val visited = mutableSetOf<String>()
        while (!nextToken.isNullOrBlank() && page < maxPages && visited.add(nextToken!!)) {
            page++
            val contJson = runCatching { post("browse", JSONObject(), continuationToken = nextToken) }.getOrNull()
                ?: break
            val contShelves = parseShelvesFromBrowse(contJson)
            if (contShelves.isNotEmpty()) {
                allShelves.addAll(contShelves)
            }
            val (contTracks, contCards, newToken) = parseBrowseContinuationItems(contJson)
            extraTracks.addAll(contTracks)
            extraCards.addAll(contCards)
            nextToken = newToken
        }

        if (extraTracks.isNotEmpty() || extraCards.isNotEmpty()) {
            allShelves.add(
                HomeShelf(
                    title = "Biblioteca",
                    items = extraCards.distinctBy { it.id },
                    tracks = extraTracks.distinctBy { it.videoId },
                    isQuickPicks = extraTracks.isNotEmpty() && extraCards.isEmpty()
                )
            )
        }
        return allShelves
    }

    internal fun extractContinuationToken(json: JSONObject): String? {
        extractContinuationFromContainer(json)?.let { return it }
        val contents = json.optJSONObject("contents")
        contents?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.let { sectionList ->
                extractContinuationFromContainer(sectionList)?.let { return it }
                val secs = sectionList.optJSONArray("contents")
                if (secs != null) {
                    for (i in 0 until secs.length()) {
                        val s = secs.optJSONObject(i) ?: continue
                        extractContinuationFromContainer(s)?.let { return it }
                        s.optJSONObject("musicShelfRenderer")?.let { extractContinuationFromContainer(it)?.let { t -> return t } }
                        s.optJSONObject("musicPlaylistShelfRenderer")?.let { extractContinuationFromContainer(it)?.let { t -> return t } }
                        s.optJSONObject("gridRenderer")?.let { extractContinuationFromContainer(it)?.let { t -> return t } }
                    }
                }
            }
        contents?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.let { sectionList ->
                extractContinuationFromContainer(sectionList)?.let { return it }
                val secs = sectionList.optJSONArray("contents")
                if (secs != null) {
                    for (i in 0 until secs.length()) {
                        val s = secs.optJSONObject(i) ?: continue
                        extractContinuationFromContainer(s)?.let { return it }
                        s.optJSONObject("musicShelfRenderer")?.let { extractContinuationFromContainer(it)?.let { t -> return t } }
                        s.optJSONObject("musicPlaylistShelfRenderer")?.let { extractContinuationFromContainer(it)?.let { t -> return t } }
                    }
                }
            }
        val contContents = json.optJSONObject("continuationContents")
        if (contContents != null) {
            listOf("musicShelfContinuation", "musicPlaylistShelfContinuation", "gridContinuation", "sectionListContinuation")
                .forEach { key ->
                    contContents.optJSONObject(key)?.let { c ->
                        extractContinuationFromContainer(c)?.let { return it }
                    }
                }
        }
        val actions = json.optJSONArray("onResponseReceivedActions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                val items = actions.optJSONObject(i)
                    ?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems") ?: continue
                extractTokenFromItemArray(items)?.let { return it }
            }
        }
        return null
    }

    private fun extractContinuationFromContainer(obj: JSONObject?): String? {
        if (obj == null) return null
        val continuations = obj.optJSONArray("continuations")
        if (continuations != null) {
            for (i in 0 until continuations.length()) {
                val entry = continuations.optJSONObject(i) ?: continue
                val token = entry.optJSONObject("nextContinuationData")?.optString("continuation")
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.optJSONObject("nextRadioContinuationData")?.optString("continuation")
                        ?.takeIf { it.isNotBlank() }
                    ?: entry.optJSONObject("reloadContinuationData")?.optString("continuation")
                        ?.takeIf { it.isNotBlank() }
                if (token != null) return token
            }
        }
        extractTokenFromItemArray(obj.optJSONArray("contents"))?.let { return it }
        extractTokenFromItemArray(obj.optJSONArray("items"))?.let { return it }
        return null
    }

    private fun extractTokenFromItemArray(items: JSONArray?): String? {
        if (items == null) return null
        for (i in items.length() - 1 downTo 0) {
            val token = items.optJSONObject(i)
                ?.optJSONObject("continuationItemRenderer")
                ?.optJSONObject("continuationEndpoint")
                ?.optJSONObject("continuationCommand")
                ?.optString("token")
                ?.takeIf { it.isNotBlank() }
            if (token != null) return token
        }
        return null
    }

    internal fun parseBrowseContinuationItems(
        json: JSONObject,
        fallbackArtwork: String = "",
        fallbackAlbum: String? = null,
        fallbackAlbumId: String? = null,
        fallbackArtist: String? = null
    ): Triple<List<Track>, List<BrowseCard>, String?> {
        val tracks = mutableListOf<Track>()
        val cards = mutableListOf<BrowseCard>()
        val contContents = json.optJSONObject("continuationContents")
        if (contContents != null) {
            val shelfCont = contContents.optJSONObject("musicShelfContinuation")
                ?: contContents.optJSONObject("musicPlaylistShelfContinuation")
            shelfCont?.optJSONArray("contents")?.let { arr ->
                parseItemsIntoCollections(arr, fallbackArtwork, fallbackAlbum, fallbackAlbumId, fallbackArtist, tracks, cards)
            }
            contContents.optJSONObject("gridContinuation")?.optJSONArray("items")?.let { arr ->
                parseItemsIntoCollections(arr, fallbackArtwork, fallbackAlbum, fallbackAlbumId, fallbackArtist, tracks, cards)
            }
            contContents.optJSONObject("sectionListContinuation")?.optJSONArray("contents")?.let { arr ->
                for (sec in unwrapSectionContents(arr)) {
                    sec.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")?.let { items ->
                        parseItemsIntoCollections(items, fallbackArtwork, fallbackAlbum, fallbackAlbumId, fallbackArtist, tracks, cards)
                    }
                    sec.optJSONObject("musicPlaylistShelfRenderer")?.optJSONArray("contents")?.let { items ->
                        parseItemsIntoCollections(items, fallbackArtwork, fallbackAlbum, fallbackAlbumId, fallbackArtist, tracks, cards)
                    }
                    sec.optJSONObject("gridRenderer")?.optJSONArray("items")?.let { items ->
                        parseItemsIntoCollections(items, fallbackArtwork, fallbackAlbum, fallbackAlbumId, fallbackArtist, tracks, cards)
                    }
                }
            }
        }
        val actions = json.optJSONArray("onResponseReceivedActions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                val arr = actions.optJSONObject(i)
                    ?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems") ?: continue
                parseItemsIntoCollections(arr, fallbackArtwork, fallbackAlbum, fallbackAlbumId, fallbackArtist, tracks, cards)
            }
        }
        return Triple(tracks, cards, extractContinuationToken(json))
    }

    private fun parseItemsIntoCollections(
        items: JSONArray,
        fallbackArtwork: String,
        fallbackAlbum: String?,
        fallbackAlbumId: String?,
        fallbackArtist: String?,
        outTracks: MutableList<Track>,
        outCards: MutableList<BrowseCard>?
    ) {
        for (j in 0 until items.length()) {
            val obj = items.optJSONObject(j) ?: continue
            obj.optJSONObject("musicResponsiveListItemRenderer")?.let { renderer ->
                val track = parseTrackFromListItem(
                    renderer = renderer,
                    fallbackArtwork = fallbackArtwork,
                    fallbackAlbum = fallbackAlbum,
                    fallbackAlbumId = fallbackAlbumId,
                    fallbackArtist = fallbackArtist
                )
                if (track != null) {
                    outTracks.add(track)
                } else {
                    outCards?.let { list -> parseCardFromListItem(renderer, "")?.let { list.add(it) } }
                }
            }
            obj.optJSONObject("musicTwoRowItemRenderer")?.let { twoRow ->
                parseTwoRowItem(twoRow)?.let { card ->
                    if (card.kind == ItemKind.SONG && card.videoId != null) {
                        val (parsedArtist, parsedDuration) = parseArtistAndDurationFromSubtitle(card.subtitle)
                        outTracks.add(
                            Track(
                                videoId = card.videoId,
                                title = card.title,
                                artist = parsedArtist.ifBlank { "YouTube Music" },
                                artworkUrl = card.artworkUrl.ifBlank { fallbackArtwork },
                                durationText = parsedDuration,
                                durationMs = parseDurationMs(parsedDuration)
                            )
                        )
                    } else {
                        outCards?.add(card)
                    }
                }
            }
        }
    }

    suspend fun getUpNextAndLyricsBrowseId(videoId: String): Pair<List<Track>, String?> {
        val radioBody = JSONObject()
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
            .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        val radioResult = parseNextResponse(post("next", radioBody), seedVideoId = videoId)
        if (radioResult.first.size > 1) {
            return radioResult
        }

        val plainBody = JSONObject()
            .put("videoId", videoId)
            .put("isAudioOnly", true)
        val plainResult = parseNextResponse(post("next", plainBody), seedVideoId = videoId)
        val mergedQueue = (radioResult.first + plainResult.first).distinctBy { it.videoId }
        val lyricsId = radioResult.second ?: plainResult.second
        return mergedQueue to lyricsId
    }

    suspend fun resolveTrackMetadataFromNext(videoId: String): ResolvedTrackMetadata? {
        if (videoId.isBlank()) return null
        val body = JSONObject()
            .put("videoId", videoId)
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
        val json = post("next", body)
        return parseNextTrackMetadata(json, videoId)
    }

    internal fun parseNextTrackMetadata(json: JSONObject, targetVideoId: String): ResolvedTrackMetadata? {
        val tabs = json.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs") ?: return null

        var fallbackMatch: ResolvedTrackMetadata? = null
        for (i in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
            val queueContents = tab.optJSONObject("content")
                ?.optJSONObject("musicQueueRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents") ?: continue
            for (j in 0 until queueContents.length()) {
                val rawObj = queueContents.optJSONObject(j) ?: continue
                val panelItem = rawObj.optJSONObject("playlistPanelVideoRenderer")
                    ?: rawObj.optJSONObject("playlistPanelVideoWrapperRenderer")
                        ?.optJSONObject("primaryRenderer")
                        ?.optJSONObject("playlistPanelVideoRenderer")
                    ?: continue
                val parsed = parseQueueItemWithCredits(panelItem) ?: continue
                val (parsedTrack, credits) = parsed
                val meta = ResolvedTrackMetadata(
                    videoId = parsedTrack.videoId,
                    artistId = parsedTrack.artistId,
                    album = parsedTrack.album,
                    albumId = parsedTrack.albumId,
                    artists = credits
                )
                if (parsedTrack.videoId == targetVideoId) {
                    return meta
                }
                if (fallbackMatch == null && j == 0 && (meta.artistId != null || meta.albumId != null)) {
                    fallbackMatch = meta.copy(videoId = targetVideoId)
                }
            }
        }
        return fallbackMatch
    }

    private fun parseNextResponse(json: JSONObject, seedVideoId: String): Pair<List<Track>, String?> {
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
                    val rawObj = queueContents.optJSONObject(j) ?: continue
                    val panelItem = rawObj.optJSONObject("playlistPanelVideoRenderer")
                        ?: rawObj.optJSONObject("playlistPanelVideoWrapperRenderer")
                            ?.optJSONObject("primaryRenderer")
                            ?.optJSONObject("playlistPanelVideoRenderer")
                        ?: continue
                    parseTrackFromQueueItem(panelItem)?.let { track ->
                        if (track.videoId != seedVideoId || queue.none { it.videoId == seedVideoId }) {
                            queue.add(track)
                        }
                    }
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
                        val (parsedArtist, parsedDuration) = parseArtistAndDurationFromSubtitle(card.subtitle)
                        tracks.add(
                            Track(
                                videoId = card.videoId,
                                title = card.title,
                                artist = parsedArtist.ifBlank { "YouTube Music" },
                                artworkUrl = card.artworkUrl,
                                durationText = parsedDuration,
                                durationMs = parseDurationMs(parsedDuration)
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
                        val (parsedArtist, parsedDuration) = parseArtistAndDurationFromSubtitle(card.subtitle)
                        tracks.add(
                            Track(
                                videoId = card.videoId,
                                title = card.title,
                                artist = parsedArtist.ifBlank { "YouTube Music" },
                                artworkUrl = card.artworkUrl,
                                durationText = parsedDuration,
                                durationMs = parseDurationMs(parsedDuration)
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
                        val (parsedArtist, parsedDuration) = parseArtistAndDurationFromSubtitle(card.subtitle)
                        tracks.add(
                            Track(
                                videoId = card.videoId,
                                title = card.title,
                                artist = parsedArtist.ifBlank { "YouTube Music" },
                                artworkUrl = card.artworkUrl,
                                durationText = parsedDuration,
                                durationMs = parseDurationMs(parsedDuration)
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

    private fun parseTrackFromQueueItem(item: JSONObject): Track? =
        parseQueueItemWithCredits(item)?.first

    internal fun parseQueueItemWithCredits(item: JSONObject): Pair<Track, List<ArtistCredit>>? {
        val videoId = item.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = extractRunsText(item.optJSONObject("title")).ifBlank { return null }
        val longByline = item.optJSONObject("longBylineText")?.optJSONArray("runs")
        val shortByline = extractRunsText(item.optJSONObject("shortBylineText"))
        val artistNames = mutableListOf<String>()
        val artistCredits = mutableListOf<ArtistCredit>()
        var artistId: String? = null
        var album: String? = null
        var albumId: String? = null

        if (longByline != null) {
            for (i in 0 until longByline.length()) {
                val run = longByline.optJSONObject(i) ?: continue
                val text = run.optString("text").trim()
                if (text.isBlank() || text == "•" || text == "·" || text == "," || text == "&") continue
                val browseEndpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val browseId = browseEndpoint?.optString("browseId").orEmpty()
                val pageType = browseEndpoint
                    ?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType")
                    .orEmpty()
                val isArtist = browseId.startsWith("UC") || pageType == "MUSIC_PAGE_TYPE_ARTIST"
                val isAlbum = browseId.startsWith("MPREb") || pageType == "MUSIC_PAGE_TYPE_ALBUM"
                when {
                    isArtist && browseId.isNotBlank() -> {
                        artistNames.add(text)
                        artistCredits.add(ArtistCredit(name = text, browseId = browseId))
                        if (artistId == null) artistId = browseId
                    }
                    isAlbum && browseId.isNotBlank() -> {
                        album = text
                        albumId = browseId
                    }
                }
            }
        }
        val artist = artistNames.joinToString(", ").ifBlank { shortByline.ifBlank { "YouTube Music" } }
        val durationText = extractRunsText(item.optJSONObject("lengthText"))
        val artwork = extractBestThumbnail(item.optJSONObject("thumbnail"))
        val track = Track(
            videoId = videoId,
            title = title,
            artist = artist,
            artistId = artistId,
            album = album,
            albumId = albumId,
            artworkUrl = artwork.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
            durationText = durationText,
            durationMs = parseDurationMs(durationText)
        )
        return track to artistCredits
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
        return com.example.ui.components.upgradeArtworkUrl(url)
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

    internal fun parseArtistAndDurationFromSubtitle(subtitle: String): Pair<String, String> {
        val parts = subtitle.split("•", "·")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        var duration = ""
        val artistParts = mutableListOf<String>()
        for (part in parts) {
            when {
                DURATION_REGEX.matches(part) -> if (duration.isBlank()) duration = part
                isCategoryLabel(part) || isStatsLabel(part) -> Unit
                else -> if (artistParts.isEmpty()) artistParts.add(part)
            }
        }
        val artist = artistParts.firstOrNull().orEmpty()
        return artist to duration
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
