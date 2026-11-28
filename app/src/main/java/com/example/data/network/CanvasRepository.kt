package com.example.data.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.local.SpotifyCanvasCredentialsStore
import com.example.model.CanvasArtwork
import com.example.model.CanvasDiagnosticCandidate
import com.example.model.CanvasDiagnosticReport
import com.example.model.CanvasSource
import com.example.model.CanvasStatusInfo
import com.example.model.CanvasVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CanvasRepository {
    private const val TAG = "Canvas"
    private const val MAX_CACHE_SIZE = 64
    private const val NEGATIVE_TTL_MS = 20 * 60_000L
    private const val TOKEN_BACKOFF_SECONDS = 30 * 60L
    private const val TOKEN_SAFETY_MARGIN_SECONDS = 60L
    private const val MIN_CANDIDATE_SCORE = 12

    internal const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val SCRIPT_SRC_REGEX = Regex(
        """(/assets/(?:index|index-legacy)[-~][^"'\s<>]+\.js)"""
    )
    private val JWT_REGEX = Regex(
        """\b(ey[A-Za-z0-9_-]{8,}\.ey[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})\b"""
    )
    private val ALBUM_URL_ID_REGEX = Regex("""/album/[^/]+/(\d+)""")

    private val COMPILATION_REJECT_KEYWORDS = listOf(
        "Greatest Hits",
        "Best of",
        "Essentials",
        "Hits",
        "Playlist",
        "Radio"
    )

    private val queryMutex = Mutex()
    private val tokenMutex = Mutex()

    @Volatile
    private var configuredClient: OkHttpClient? = null

    internal var nowMillisProvider: () -> Long = { System.currentTimeMillis() }
    internal var defaultCountryProvider: () -> String = { Locale.getDefault().country }

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4_500, TimeUnit.MILLISECONDS)
            .readTimeout(6_000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private data class CachedJwt(
        val token: String,
        val expSec: Long,
        val isPreferred: Boolean
    )

    internal data class AppleEditorialVideos(
        val portraitUrl: String?,
        val squareUrl: String?,
        val rawUrl: String?,
        val keysFound: List<String>,
        val fallbackImageUrl: String?
    ) {
        fun hasAnyVideo(): Boolean =
            !portraitUrl.isNullOrBlank() || !squareUrl.isNullOrBlank() || !rawUrl.isNullOrBlank()
    }

    private data class AppleAlbumCacheEntry(
        val albumId: String,
        val albumTitle: String?,
        val videos: AppleEditorialVideos
    )

    internal data class ParsedJwt(
        val token: String,
        val expSec: Long,
        val hasWebPlayKid: Boolean,
        val hasAmpWebPlay: Boolean
    ) {
        val isPreferred: Boolean get() = hasWebPlayKid || hasAmpWebPlay
    }

    internal data class ScoredSongCandidate(
        val songId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val appleAlbumId: String?,
        val artworkUrl: String?,
        val inlineEditorialJson: JSONObject?,
        val score: Int,
        val rejectedReason: String? = null
    )

    @Volatile
    private var cachedAppleToken: CachedJwt? = null

    @Volatile
    private var appleTokenBackoffUntilSec: Long = 0L

    private val rejectedAppleTokens: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val positiveCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CanvasArtwork>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CanvasArtwork>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    // Shared cache by Apple Music album ID so all tracks on an album share the exact same motion artwork
    private val appleAlbumCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, AppleAlbumCacheEntry>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AppleAlbumCacheEntry>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    // Secondary cache by normalized (primaryArtist, album) -> Apple album ID
    private val albumKeyToIdCache = ConcurrentHashMap<String, String>()

    private val negativeCache = ConcurrentHashMap<String, Long>()
    private val statusByVideoId = ConcurrentHashMap<String, CanvasStatusInfo>()

    fun configure(okHttpClient: OkHttpClient) {
        configuredClient = okHttpClient.newBuilder()
            .connectTimeout(4_500, TimeUnit.MILLISECONDS)
            .readTimeout(6_000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getCached(videoId: String): CanvasArtwork? {
        if (videoId.isBlank()) return null
        return positiveCache[videoId]
    }

    fun getStatusInfo(videoId: String, animatedArtworkEnabled: Boolean = true): CanvasStatusInfo {
        if (!animatedArtworkEnabled) {
            return CanvasStatusInfo(
                videoId = videoId,
                label = "Sin portada animada",
                reason = "Desactivado en ajustes"
            )
        }
        if (videoId.isBlank()) {
            return CanvasStatusInfo(
                videoId = videoId,
                label = "Sin portada animada",
                reason = "Pista sin identificador"
            )
        }
        statusByVideoId[videoId]?.let { return it }
        positiveCache[videoId]?.let { art ->
            return CanvasStatusInfo(
                videoId = videoId,
                label = art.menuStatusLabel(preferPortrait = true),
                source = art.source,
                variant = art.variant
            )
        }
        return CanvasStatusInfo(
            videoId = videoId,
            label = "Sin portada animada",
            reason = "Consultando..."
        )
    }

    fun clearCache() {
        positiveCache.clear()
        appleAlbumCache.clear()
        albumKeyToIdCache.clear()
        negativeCache.clear()
        statusByVideoId.clear()
        cachedAppleToken = null
        appleTokenBackoffUntilSec = 0L
        rejectedAppleTokens.clear()
    }

    private fun logStep(step: String, steps: MutableList<String>? = null) {
        val safe = sanitizeLogLine(step)
        runCatching { Log.d(TAG, safe) }
        steps?.add(safe)
    }

    internal fun sanitizeUrlForLog(rawUrl: String): String {
        return runCatching {
            val uri = Uri.parse(rawUrl)
            val scheme = uri.scheme ?: return@runCatching rawUrl.take(120)
            val host = uri.host ?: ""
            val path = uri.encodedPath ?: ""
            val safeParams = uri.queryParameterNames
                .filterNot { key ->
                    val low = key.lowercase()
                    low.contains("token") ||
                        low.contains("auth") ||
                        low.contains("key") ||
                        low.contains("cookie") ||
                        low.contains("sig")
                }
                .mapNotNull { key ->
                    uri.getQueryParameter(key)?.let { value ->
                        "${Uri.encode(key)}=${Uri.encode(value)}"
                    }
                }
            val querySuffix = if (safeParams.isNotEmpty()) "?${safeParams.joinToString("&")}" else ""
            "$scheme://$host$path$querySuffix"
        }.getOrDefault(rawUrl.substringBefore("?"))
    }

    private fun sanitizeLogLine(msg: String): String {
        return msg
            .replace(Regex("""Bearer\s+[A-Za-z0-9._-]+"""), "Bearer ***")
            .replace(Regex("""sp_dc\s*=\s*[^;\s]+""", RegexOption.IGNORE_CASE), "sp_dc=***")
    }

    suspend fun fetchCanvas(
        videoId: String,
        title: String,
        artist: String,
        album: String? = null,
        context: Context? = null,
        spotifyCanvasEnabled: Boolean = false
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || videoId.startsWith("local_") || title.isBlank() || artist.isBlank()) {
            if (videoId.isNotBlank()) {
                statusByVideoId[videoId] = CanvasStatusInfo(
                    videoId = videoId,
                    label = "Sin portada animada",
                    reason = if (videoId.startsWith("local_")) "Archivo local" else "Metadatos incompletos"
                )
            }
            return@withContext null
        }

        positiveCache[videoId]?.let { cached ->
            if (cached.matches(title, artist, album)) {
                statusByVideoId[videoId] = CanvasStatusInfo(
                    videoId = videoId,
                    label = cached.menuStatusLabel(preferPortrait = true),
                    source = cached.source,
                    variant = cached.variant
                )
                return@withContext cached
            }
        }

        if (!album.isNullOrBlank()) {
            val negExpiresAt = negativeCache[videoId]
            if (negExpiresAt != null && nowMillisProvider() < negExpiresAt) {
                if (!statusByVideoId.containsKey(videoId)) {
                    statusByVideoId[videoId] = CanvasStatusInfo(
                        videoId = videoId,
                        label = "Sin portada animada",
                        reason = "Sin coincidencia reciente en caché"
                    )
                }
                return@withContext null
            }
        }

        queryMutex.withLock {
            positiveCache[videoId]?.let { cached ->
                if (cached.matches(title, artist, album)) {
                    statusByVideoId[videoId] = CanvasStatusInfo(
                        videoId = videoId,
                        label = cached.menuStatusLabel(preferPortrait = true),
                        source = cached.source,
                        variant = cached.variant
                    )
                    return@withLock cached
                }
            }

            // Fast album-level sharing hit across tracks of the same album
            findSharedAlbumCacheHit(title = title, artist = artist, album = album)?.let { shared ->
                positiveCache[videoId] = shared
                negativeCache.remove(videoId)
                statusByVideoId[videoId] = CanvasStatusInfo(
                    videoId = videoId,
                    label = shared.menuStatusLabel(preferPortrait = true),
                    source = shared.source,
                    variant = shared.variant
                )
                logStep("Cache por álbum compartida para '$title' -> ${shared.menuStatusLabel(true)}")
                return@withLock shared
            }

            val report = executeFullPipeline(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                context = context,
                spotifyCanvasEnabled = spotifyCanvasEnabled,
                allowFallbackProviders = true
            )

            val resolved = report.resolvedArtwork
            if (resolved != null && resolved.matches(title, artist, album)) {
                positiveCache[videoId] = resolved
                negativeCache.remove(videoId)
                statusByVideoId[videoId] = CanvasStatusInfo(
                    videoId = videoId,
                    label = resolved.menuStatusLabel(preferPortrait = true),
                    source = resolved.source,
                    variant = resolved.variant
                )
                resolved
            } else {
                if (!album.isNullOrBlank()) {
                    negativeCache[videoId] = nowMillisProvider() + NEGATIVE_TTL_MS
                }
                statusByVideoId[videoId] = CanvasStatusInfo(
                    videoId = videoId,
                    label = "Sin portada animada",
                    reason = report.failureReason ?: "Sin editorialVideo disponible"
                )
                null
            }
        }
    }

    suspend fun runDiagnostic(
        title: String,
        artist: String,
        album: String?,
        context: Context? = null,
        spotifyCanvasEnabled: Boolean = false
    ): CanvasDiagnosticReport = withContext(Dispatchers.IO) {
        queryMutex.withLock {
            executeFullPipeline(
                videoId = "diag_${title.hashCode()}",
                title = title.trim(),
                artist = artist.trim(),
                album = album?.trim()?.ifBlank { null },
                context = context,
                spotifyCanvasEnabled = spotifyCanvasEnabled,
                allowFallbackProviders = true
            )
        }
    }

    private fun findSharedAlbumCacheHit(
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        if (album.isNullOrBlank() || CanvasArtwork.isCompilationTitle(album)) return null
        val key = buildAlbumShareKey(artist, album) ?: return null
        val albumId = albumKeyToIdCache[key] ?: return null
        val cachedAlbum = appleAlbumCache[albumId] ?: return null
        if (!cachedAlbum.videos.hasAnyVideo()) return null
        return buildAppleCanvasArtwork(
            videos = cachedAlbum.videos,
            matchedTitle = title,
            matchedArtist = artist,
            matchedAlbum = cachedAlbum.albumTitle ?: album,
            appleAlbumId = albumId
        ).takeIf { it.matches(title, artist, album) }
    }

    private fun buildAlbumShareKey(artist: String, album: String?): String? {
        val normAlbum = album?.let { CanvasArtwork.normalizeCanvasToken(it) }.orEmpty()
        val normArtist = CanvasArtwork.normalizePrimaryArtist(artist)
        if (normAlbum.isBlank() || normArtist.isBlank()) return null
        return "$normArtist|$normAlbum"
    }

    private suspend fun executeFullPipeline(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        context: Context?,
        spotifyCanvasEnabled: Boolean,
        allowFallbackProviders: Boolean
    ): CanvasDiagnosticReport {
        val steps = mutableListOf<String>()
        val diagCandidates = mutableListOf<CanvasDiagnosticCandidate>()
        val sanitizedUrls = mutableListOf<String>()
        val client = configuredClient ?: defaultClient

        val storefront = resolveStorefront()
        val searchTerm = buildAppleSearchTerm(title = title, artist = artist, album = album)
        logStep("Inicio consulta: título='$title', artista='$artist', álbum='${album ?: "-"}', país=$storefront", steps)
        logStep("Término búsqueda Apple Music: '$searchTerm'", steps)

        var tokenStatus = "No obtenido"
        var tokenExp: Long? = null
        var selectedAlbumId: String? = null
        var editorialKeys: List<String> = emptyList()
        var failureReason: String? = null

        val appleResult: CanvasArtwork? = runCatching {
            val jwt = getOrFetchAppleWebToken(client, steps)
            if (jwt == null) {
                tokenStatus = if ((appleTokenBackoffUntilSec - nowMillisProvider() / 1000L) > 0L) {
                    "En espera (30 min tras fallo)"
                } else {
                    "No disponible"
                }
                failureReason = "No se pudo obtener token web de Apple Music"
                return@runCatching null
            }

            tokenStatus = if (jwt.isPreferred) "Válido (WebPlayKid/AMPWebPlay)" else "Válido (genérico)"
            tokenExp = jwt.expSec
            logStep("Token Apple Music activo ($tokenStatus, exp=${jwt.expSec})", steps)

            var activeToken = jwt.token
            val storefrontsToTry = if (storefront == "us") listOf("us") else listOf(storefront, "us")
            var resolvedArtwork: CanvasArtwork? = null

            for (sf in storefrontsToTry) {
                val searchUrl = buildAppleCatalogSearchUrl(sf, searchTerm)
                sanitizedUrls += sanitizeUrlForLog(searchUrl)
                logStep("Consultando catálogo ($sf): ${sanitizeUrlForLog(searchUrl)}", steps)

                val searchResponse = executeAppleApiGet(client, searchUrl, activeToken)
                if (searchResponse.code == 401) {
                    logStep("Catálogo respondió 401; marcando token como rechazado y renovando", steps)
                    markTokenRejected(activeToken)
                    val refreshed = getOrFetchAppleWebToken(client, steps)
                    if (refreshed == null) {
                        failureReason = "Token rechazado (401) sin reemplazo disponible"
                        break
                    }
                    activeToken = refreshed.token
                    tokenExp = refreshed.expSec
                    val retryResp = executeAppleApiGet(client, searchUrl, activeToken)
                    if (retryResp.body.isNullOrBlank()) {
                        failureReason = "HTTP ${retryResp.code} en reintento de búsqueda"
                        continue
                    }
                    val parsed = processAppleSearchBody(
                        client = client,
                        storefront = sf,
                        body = retryResp.body,
                        activeToken = activeToken,
                        reqTitle = title,
                        reqArtist = artist,
                        reqAlbum = album,
                        diagCandidates = diagCandidates,
                        sanitizedUrls = sanitizedUrls,
                        steps = steps
                    )
                    if (parsed.artwork != null) {
                        selectedAlbumId = parsed.selectedAlbumId
                        editorialKeys = parsed.editorialKeys
                        resolvedArtwork = parsed.artwork
                        break
                    }
                    failureReason = parsed.reason
                    selectedAlbumId = selectedAlbumId ?: parsed.selectedAlbumId
                    if (editorialKeys.isEmpty()) editorialKeys = parsed.editorialKeys
                    continue
                }

                if (searchResponse.body.isNullOrBlank()) {
                    failureReason = if (searchResponse.code > 0) {
                        "Error HTTP ${searchResponse.code} en búsqueda Apple Music"
                    } else {
                        "Sin respuesta de Apple Music"
                    }
                    logStep(failureReason!!, steps)
                    continue
                }

                val parsed = processAppleSearchBody(
                    client = client,
                    storefront = sf,
                    body = searchResponse.body,
                    activeToken = activeToken,
                    reqTitle = title,
                    reqArtist = artist,
                    reqAlbum = album,
                    diagCandidates = diagCandidates,
                    sanitizedUrls = sanitizedUrls,
                    steps = steps
                )
                if (parsed.artwork != null) {
                    selectedAlbumId = parsed.selectedAlbumId
                    editorialKeys = parsed.editorialKeys
                    resolvedArtwork = parsed.artwork
                    break
                }
                failureReason = parsed.reason
                selectedAlbumId = selectedAlbumId ?: parsed.selectedAlbumId
                if (editorialKeys.isEmpty()) editorialKeys = parsed.editorialKeys
            }
            resolvedArtwork
        }.onFailure { err ->
            failureReason = err.message ?: "Error consultando Apple Music"
            logStep("Excepción Apple Music: ${err.message}", steps)
        }.getOrNull()

        if (appleResult != null) {
            listOfNotNull(appleResult.portraitUrl, appleResult.videoUrl, appleResult.fallbackUrl)
                .distinct()
                .forEach { url ->
                    val s = sanitizeUrlForLog(url)
                    if (s !in sanitizedUrls) sanitizedUrls += s
                }
            logStep("Éxito Apple Music -> ${appleResult.menuStatusLabel(true)}", steps)
            return CanvasDiagnosticReport(
                queryTitle = title,
                queryArtist = artist,
                queryAlbum = album,
                storefront = storefront,
                searchTerm = searchTerm,
                tokenStatus = tokenStatus,
                tokenExpiresAtEpochSec = tokenExp,
                candidates = diagCandidates,
                selectedAlbumId = selectedAlbumId,
                editorialKeysFound = editorialKeys,
                resolvedArtwork = appleResult,
                sanitizedUrls = sanitizedUrls,
                steps = steps,
                failureReason = null
            )
        }

        // Optional fallbacks (Spotify if explicitly enabled with sp_dc, Tidal, Community) — NEVER iTunes musicVideo
        var fallbackResult: CanvasArtwork? = null
        if (allowFallbackProviders) {
            if (spotifyCanvasEnabled && context != null) {
                val spDc = SpotifyCanvasCredentialsStore.getSpDc(context)
                if (spDc.isNotBlank()) {
                    logStep("Intentando Spotify Canvas (sesión configurada)...", steps)
                    fallbackResult = runCatching {
                        querySpotifyCanvas(client, spDc, title, artist, album)
                    }.getOrNull()
                    if (fallbackResult != null) {
                        logStep("Éxito Spotify Canvas", steps)
                    }
                }
            }

            if (fallbackResult == null) {
                logStep("Intentando respaldo Tidal Motion...", steps)
                fallbackResult = runCatching {
                    queryTidalVideoCoverCanvas(client, title, artist, album)
                }.getOrNull()
                if (fallbackResult != null) {
                    logStep("Éxito Tidal Motion", steps)
                }
            }

            if (fallbackResult == null) {
                logStep("Intentando índice comunitario de Canvas...", steps)
                fallbackResult = runCatching {
                    queryCommunityCanvasIndex(client, videoId, title, artist, album)
                }.getOrNull()
                if (fallbackResult != null) {
                    logStep("Éxito Community Canvas", steps)
                }
            }
        }

        if (fallbackResult != null) {
            listOfNotNull(fallbackResult.portraitUrl, fallbackResult.videoUrl, fallbackResult.fallbackUrl)
                .distinct()
                .forEach { url ->
                    val s = sanitizeUrlForLog(url)
                    if (s !in sanitizedUrls) sanitizedUrls += s
                }
        } else if (failureReason == null) {
            failureReason = "Sin portada animada disponible"
        }

        return CanvasDiagnosticReport(
            queryTitle = title,
            queryArtist = artist,
            queryAlbum = album,
            storefront = storefront,
            searchTerm = searchTerm,
            tokenStatus = tokenStatus,
            tokenExpiresAtEpochSec = tokenExp,
            candidates = diagCandidates,
            selectedAlbumId = selectedAlbumId,
            editorialKeysFound = editorialKeys,
            resolvedArtwork = fallbackResult,
            sanitizedUrls = sanitizedUrls,
            steps = steps,
            failureReason = if (fallbackResult == null) failureReason else null
        )
    }

    private data class AppleSearchResolution(
        val artwork: CanvasArtwork? = null,
        val selectedAlbumId: String? = null,
        val editorialKeys: List<String> = emptyList(),
        val reason: String? = null
    )

    private suspend fun processAppleSearchBody(
        client: OkHttpClient,
        storefront: String,
        body: String,
        activeToken: String,
        reqTitle: String,
        reqArtist: String,
        reqAlbum: String?,
        diagCandidates: MutableList<CanvasDiagnosticCandidate>,
        sanitizedUrls: MutableList<String>,
        steps: MutableList<String>
    ): AppleSearchResolution {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return AppleSearchResolution(reason = "JSON inválido desde Apple Music")
        val songsArray = root.optJSONObject("results")
            ?.optJSONObject("songs")
            ?.optJSONArray("data")
            ?: return AppleSearchResolution(reason = "Sin canciones en results.songs.data")

        if (songsArray.length() == 0) {
            logStep("0 resultados en $storefront", steps)
            return AppleSearchResolution(reason = "Sin resultados en catálogo")
        }

        val scored = mutableListOf<ScoredSongCandidate>()
        for (i in 0 until songsArray.length()) {
            val item = songsArray.optJSONObject(i) ?: continue
            val candidate = scoreAppleSongCandidate(
                item = item,
                reqTitle = reqTitle,
                reqArtist = reqArtist,
                reqAlbum = reqAlbum
            )
            scored += candidate
            diagCandidates += CanvasDiagnosticCandidate(
                title = candidate.title,
                artist = candidate.artist,
                album = candidate.album,
                appleAlbumId = candidate.appleAlbumId,
                score = candidate.score,
                rejectedReason = candidate.rejectedReason
            )
            logStep(
                "Candidato '${candidate.title}' - ${candidate.artist} [${candidate.album ?: "-"}] " +
                    "score=${candidate.score}" + (candidate.rejectedReason?.let { " (rechazado: $it)" } ?: ""),
                steps
            )
        }

        val accepted = scored
            .filter { it.rejectedReason == null && it.score >= MIN_CANDIDATE_SCORE }
            .sortedByDescending { it.score }

        if (accepted.isEmpty()) {
            return AppleSearchResolution(reason = "Ningún candidato alcanzó puntaje mínimo ($MIN_CANDIDATE_SCORE)")
        }

        var lastAlbumId: String? = null
        var lastKeys: List<String> = emptyList()
        var lastReason: String? = null

        for (cand in accepted) {
            // 1) Inline editorialVideo directly inside song attributes
            cand.inlineEditorialJson?.let { edJson ->
                val videos = parseEditorialVideoObject(edJson, cand.artworkUrl)
                lastKeys = videos.keysFound
                if (videos.hasAnyVideo()) {
                    cand.appleAlbumId?.let { albId ->
                        appleAlbumCache[albId] = AppleAlbumCacheEntry(albId, cand.album, videos)
                        buildAlbumShareKey(cand.artist, cand.album)?.let { key ->
                            albumKeyToIdCache[key] = albId
                        }
                    }
                    return AppleSearchResolution(
                        artwork = buildAppleCanvasArtwork(
                            videos = videos,
                            matchedTitle = cand.title,
                            matchedArtist = cand.artist,
                            matchedAlbum = cand.album,
                            appleAlbumId = cand.appleAlbumId
                        ),
                        selectedAlbumId = cand.appleAlbumId,
                        editorialKeys = videos.keysFound
                    )
                }
            }

            // 2) Album lookup by Apple album ID
            val albumId = cand.appleAlbumId ?: continue
            lastAlbumId = albumId

            appleAlbumCache[albumId]?.let { cachedAlbum ->
                lastKeys = cachedAlbum.videos.keysFound
                if (cachedAlbum.videos.hasAnyVideo()) {
                    logStep("Álbum $albumId encontrado en caché compartida", steps)
                    return AppleSearchResolution(
                        artwork = buildAppleCanvasArtwork(
                            videos = cachedAlbum.videos,
                            matchedTitle = cand.title,
                            matchedArtist = cand.artist,
                            matchedAlbum = cachedAlbum.albumTitle ?: cand.album,
                            appleAlbumId = albumId
                        ),
                        selectedAlbumId = albumId,
                        editorialKeys = cachedAlbum.videos.keysFound
                    )
                }
            }

            val albumUrl = buildAppleCatalogAlbumUrl(storefront, albumId)
            sanitizedUrls += sanitizeUrlForLog(albumUrl)
            logStep("Consultando álbum $albumId: ${sanitizeUrlForLog(albumUrl)}", steps)

            var albResp = executeAppleApiGet(client, albumUrl, activeToken)
            if (albResp.code == 401) {
                markTokenRejected(activeToken)
                val refreshed = getOrFetchAppleWebToken(client, steps)
                if (refreshed != null) {
                    albResp = executeAppleApiGet(client, albumUrl, refreshed.token)
                }
            }

            val albBody = albResp.body
            if (albBody.isNullOrBlank()) {
                lastReason = "Álbum $albumId sin respuesta HTTP (${albResp.code})"
                logStep(lastReason, steps)
                continue
            }

            val albumObj = runCatching { JSONObject(albBody) }.getOrNull()
                ?.optJSONArray("data")
                ?.optJSONObject(0)
            val albAttrs = albumObj?.optJSONObject("attributes")
            val albTitle = albAttrs?.optString("name")?.trim()?.ifBlank { null } ?: cand.album
            val isCompilation = albAttrs?.optBoolean("isCompilation", false) == true ||
                containsCompilationKeyword(albTitle)
            if (isCompilation) {
                lastReason = "Álbum $albumId rechazado (recopilación: ${albTitle ?: ""})"
                logStep(lastReason, steps)
                continue
            }

            val edObj = albAttrs?.optJSONObject("editorialVideo")
            if (edObj == null) {
                lastReason = "Álbum $albumId sin bloque editorialVideo"
                logStep(lastReason, steps)
                continue
            }

            val albArtworkTemplate = albAttrs.optJSONObject("artwork")?.optString("url")
                ?.replace("{w}", "1000")
                ?.replace("{h}", "1000")
                ?.takeIf { it.startsWith("https://") }
                ?: cand.artworkUrl

            val videos = parseEditorialVideoObject(edObj, albArtworkTemplate)
            lastKeys = videos.keysFound
            logStep("Claves editorialVideo álbum $albumId: ${videos.keysFound.joinToString()}", steps)

            if (videos.hasAnyVideo()) {
                appleAlbumCache[albumId] = AppleAlbumCacheEntry(albumId, albTitle, videos)
                buildAlbumShareKey(cand.artist, albTitle)?.let { key ->
                    albumKeyToIdCache[key] = albumId
                }
                return AppleSearchResolution(
                    artwork = buildAppleCanvasArtwork(
                        videos = videos,
                        matchedTitle = cand.title,
                        matchedArtist = cand.artist,
                        matchedAlbum = albTitle,
                        appleAlbumId = albumId
                    ),
                    selectedAlbumId = albumId,
                    editorialKeys = videos.keysFound
                )
            } else {
                lastReason = "editorialVideo sin enlaces de video válidos"
            }
        }

        return AppleSearchResolution(
            selectedAlbumId = lastAlbumId,
            editorialKeys = lastKeys,
            reason = lastReason ?: "El álbum no tiene editorialVideo"
        )
    }

    internal fun buildAppleCanvasArtwork(
        videos: AppleEditorialVideos,
        matchedTitle: String,
        matchedArtist: String,
        matchedAlbum: String?,
        appleAlbumId: String?
    ): CanvasArtwork {
        val portrait = videos.portraitUrl?.takeIf { it.isNotBlank() }
        val square = videos.squareUrl?.takeIf { it.isNotBlank() }
        val raw = videos.rawUrl?.takeIf { it.isNotBlank() }
        val primary = square ?: portrait ?: raw.orEmpty()
        val backup = listOfNotNull(square, raw, portrait).firstOrNull { it != primary && it != portrait }
        val variant = when {
            portrait != null -> CanvasVariant.APPLE_VERTICAL
            square != null -> CanvasVariant.APPLE_SQUARE
            else -> CanvasVariant.APPLE_RAW
        }
        return CanvasArtwork(
            videoUrl = primary,
            fallbackUrl = backup,
            portraitUrl = portrait,
            fallbackImageUrl = videos.fallbackImageUrl,
            matchedTitle = matchedTitle,
            matchedArtist = matchedArtist,
            album = matchedAlbum,
            appleAlbumId = appleAlbumId,
            source = CanvasSource.APPLE,
            variant = variant,
            sourceLabel = "Apple Music"
        )
    }

    internal fun resolveStorefront(): String {
        val country = defaultCountryProvider().trim().lowercase(Locale.ROOT)
        return if (country.length == 2 && country.all { it in 'a'..'z' }) country else "us"
    }

    internal fun buildAppleSearchTerm(title: String, artist: String, album: String?): String {
        val normTitle = CanvasArtwork.normalizeStrictAscii(title)
        val primaryArtist = CanvasArtwork.normalizePrimaryArtist(artist)
        val parts = mutableListOf<String>()
        if (primaryArtist.isNotBlank() && !normTitle.contains(primaryArtist)) {
            parts += artist.trim()
        }
        parts += title.trim()
        val trimmedAlbum = album?.trim().orEmpty()
        val normAlbum = if (trimmedAlbum.isNotBlank()) CanvasArtwork.normalizeCanvasToken(trimmedAlbum) else ""
        if (normAlbum.isNotBlank() && !normTitle.contains(normAlbum)) {
            parts += trimmedAlbum
        }
        return parts.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
    }

    internal fun buildAppleCatalogSearchUrl(storefront: String, term: String): String {
        return "https://amp-api.music.apple.com/v1/catalog/$storefront/search" +
            "?term=${Uri.encode(term)}&types=songs&limit=10&extend=editorialVideo&include=albums"
    }

    internal fun buildAppleCatalogAlbumUrl(storefront: String, albumId: String): String {
        return "https://amp-api.music.apple.com/v1/catalog/$storefront/albums/${Uri.encode(albumId)}?extend=editorialVideo"
    }

    internal fun scoreAppleSongCandidate(
        item: JSONObject,
        reqTitle: String,
        reqArtist: String,
        reqAlbum: String?
    ): ScoredSongCandidate {
        val songId = item.optString("id").trim()
        val attrs = item.optJSONObject("attributes") ?: JSONObject()
        val candTitle = attrs.optString("name").trim()
        val candArtist = attrs.optString("artistName").trim()
        val candAlbum = attrs.optString("albumName").trim().ifBlank { null }
        val candUrl = attrs.optString("url").trim()
        val inlineEd = attrs.optJSONObject("editorialVideo")
        val artTemplate = attrs.optJSONObject("artwork")?.optString("url")
            ?.replace("{w}", "1000")
            ?.replace("{h}", "1000")
            ?.takeIf { it.startsWith("https://") }

        val albumIdFromRel = item.optJSONObject("relationships")
            ?.optJSONObject("albums")
            ?.optJSONArray("data")
            ?.let { arr ->
                (0 until arr.length())
                    .asSequence()
                    .mapNotNull { arr.optJSONObject(it)?.optString("id")?.trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("pl.", ignoreCase = true) }
            }
        val albumIdFromUrl = ALBUM_URL_ID_REGEX.find(candUrl)?.groupValues?.getOrNull(1)
        val appleAlbumId = albumIdFromRel ?: albumIdFromUrl

        // Compilation rejection
        if (containsCompilationKeyword(candTitle) || containsCompilationKeyword(candAlbum)) {
            return ScoredSongCandidate(
                songId = songId,
                title = candTitle,
                artist = candArtist,
                album = candAlbum,
                appleAlbumId = appleAlbumId,
                artworkUrl = artTemplate,
                inlineEditorialJson = inlineEd,
                score = Int.MIN_VALUE,
                rejectedReason = "Recopilación/Playlist"
            )
        }

        // Strict requested-artist presence check
        val reqArtists = CanvasArtwork.splitNormalizedArtists(reqArtist)
        val candArtists = CanvasArtwork.splitNormalizedArtists(candArtist)
        val missingArtist = reqArtists.firstOrNull { req -> candArtists.none { it == req } }
        if (reqArtists.isEmpty() || candArtists.isEmpty() || missingArtist != null) {
            return ScoredSongCandidate(
                songId = songId,
                title = candTitle,
                artist = candArtist,
                album = candAlbum,
                appleAlbumId = appleAlbumId,
                artworkUrl = artTemplate,
                inlineEditorialJson = inlineEd,
                score = Int.MIN_VALUE,
                rejectedReason = if (missingArtist != null) "Falta artista '$missingArtist'" else "Sin artista"
            )
        }

        var score = 10
        val reqTitleNorm = CanvasArtwork.normalizeCanvasToken(reqTitle)
        val candTitleNorm = CanvasArtwork.normalizeCanvasToken(candTitle)
        score += when {
            reqTitleNorm.isNotBlank() && candTitleNorm == reqTitleNorm -> 15
            reqTitleNorm.isNotBlank() && candTitleNorm.isNotBlank() &&
                (candTitleNorm.contains(reqTitleNorm) || reqTitleNorm.contains(candTitleNorm)) -> 7
            else -> -10
        }

        val reqAlbumNorm = reqAlbum?.let { CanvasArtwork.normalizeCanvasToken(it) }.orEmpty()
        val candAlbumNorm = candAlbum?.let { CanvasArtwork.normalizeCanvasToken(it) }.orEmpty()
        if (reqAlbumNorm.isNotBlank() && candAlbumNorm.isNotBlank()) {
            score += when {
                candAlbumNorm == reqAlbumNorm -> 20
                candAlbumNorm.contains(reqAlbumNorm) || reqAlbumNorm.contains(candAlbumNorm) -> 10
                else -> 0
            }
        }

        // Edition words: Deluxe, Remastered, Taylor's Version (+5 if both have, -3 if only result has)
        val reqEditionSpace = CanvasArtwork.normalizeStrictAscii("$reqTitle ${reqAlbum.orEmpty()}")
        val candEditionSpace = CanvasArtwork.normalizeStrictAscii("$candTitle ${candAlbum.orEmpty()}")
        val editionTokens = listOf("deluxe", "remastered", "taylors version", "taylor s version")
        val seenEditionGroup = mutableSetOf<String>()
        for (token in editionTokens) {
            val group = if (token.startsWith("taylor")) "taylors_version" else token
            if (!seenEditionGroup.add(group)) continue
            val reqHas = if (group == "taylors_version") {
                reqEditionSpace.contains("taylors version") || reqEditionSpace.contains("taylor s version")
            } else {
                reqEditionSpace.contains(token)
            }
            val candHas = if (group == "taylors_version") {
                candEditionSpace.contains("taylors version") || candEditionSpace.contains("taylor s version")
            } else {
                candEditionSpace.contains(token)
            }
            if (reqHas && candHas) {
                score += 5
            } else if (!reqHas && candHas) {
                score -= 3
            }
        }

        val rejected = if (score < MIN_CANDIDATE_SCORE) "Puntaje < $MIN_CANDIDATE_SCORE" else null
        return ScoredSongCandidate(
            songId = songId,
            title = candTitle,
            artist = candArtist,
            album = candAlbum,
            appleAlbumId = appleAlbumId,
            artworkUrl = artTemplate,
            inlineEditorialJson = inlineEd,
            score = score,
            rejectedReason = rejected
        )
    }

    private fun containsCompilationKeyword(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return false
        val ascii = CanvasArtwork.normalizeStrictAscii(text)
        return COMPILATION_REJECT_KEYWORDS.any { kw ->
            val kwNorm = CanvasArtwork.normalizeStrictAscii(kw)
            Regex("""\b${Regex.escape(kwNorm)}\b""").containsMatchIn(ascii)
        }
    }

    internal fun parseEditorialVideoObject(
        edObj: JSONObject,
        fallbackArtworkUrl: String? = null
    ): AppleEditorialVideos {
        val keysFound = edObj.keys().asSequence().toList()

        fun extractLinkFromNode(node: JSONObject?): String? {
            if (node == null) return null
            val direct = listOf("video", "videoUrl", "hlsUrl", "url")
                .asSequence()
                .map { node.optString(it).trim() }
                .firstOrNull { it.startsWith("https://") }
            if (direct != null) return direct
            // Check nested keys if Apple nests under preview/asset objects
            val nestedIter = node.keys()
            while (nestedIter.hasNext()) {
                val k = nestedIter.next()
                val child = node.optJSONObject(k) ?: continue
                val nestedUrl = listOf("video", "videoUrl", "hlsUrl", "url")
                    .asSequence()
                    .map { child.optString(it).trim() }
                    .firstOrNull { it.startsWith("https://") }
                if (nestedUrl != null) return nestedUrl
            }
            return null
        }

        val tallKeys = listOf("motionDetailTall", "motionTallVideo3x4")
        val squareKeys = listOf("motionDetailSquare", "motionSquareVideo1x1")
        val rawKeys = listOf("motionDetailRaw")

        val portraitUrl = tallKeys.firstNotNullOfOrNull { k -> extractLinkFromNode(edObj.optJSONObject(k)) }
        val squareUrl = squareKeys.firstNotNullOfOrNull { k -> extractLinkFromNode(edObj.optJSONObject(k)) }
        val rawUrl = rawKeys.firstNotNullOfOrNull { k -> extractLinkFromNode(edObj.optJSONObject(k)) }

        return AppleEditorialVideos(
            portraitUrl = portraitUrl,
            squareUrl = squareUrl,
            rawUrl = rawUrl,
            keysFound = keysFound,
            fallbackImageUrl = fallbackArtworkUrl
        )
    }

    private suspend fun getOrFetchAppleWebToken(
        client: OkHttpClient,
        steps: MutableList<String>? = null
    ): CachedJwt? {
        val nowSec = nowMillisProvider() / 1000L
        cachedAppleToken?.let { current ->
            if (!rejectedAppleTokens.contains(current.token) &&
                current.expSec > nowSec + TOKEN_SAFETY_MARGIN_SECONDS
            ) {
                return current
            }
        }
        if (nowSec < appleTokenBackoffUntilSec) {
            logStep("Backoff de token Apple Music activo (${appleTokenBackoffUntilSec - nowSec}s restantes)", steps)
            return null
        }

        return tokenMutex.withLock {
            val recheckSec = nowMillisProvider() / 1000L
            cachedAppleToken?.let { current ->
                if (!rejectedAppleTokens.contains(current.token) &&
                    current.expSec > recheckSec + TOKEN_SAFETY_MARGIN_SECONDS
                ) {
                    return@withLock current
                }
            }
            if (recheckSec < appleTokenBackoffUntilSec) {
                return@withLock null
            }

            val browseReq = Request.Builder()
                .url("https://music.apple.com/us/browse")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val browseHtml = runCatching {
                client.newCall(browseReq).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }.getOrNull()

            if (browseHtml.isNullOrBlank()) {
                appleTokenBackoffUntilSec = recheckSec + TOKEN_BACKOFF_SECONDS
                logStep("Falló descarga de https://music.apple.com/us/browse; espera 30 min", steps)
                return@withLock null
            }

            val scriptPaths = extractAppleBundlePaths(browseHtml)
            logStep("Bundles JS detectados en /us/browse: ${scriptPaths.size}", steps)

            var bestFallback: CachedJwt? = null
            for (path in scriptPaths) {
                val scriptUrl = if (path.startsWith("http")) path else "https://music.apple.com$path"
                val jsReq = Request.Builder()
                    .url(scriptUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Origin", "https://music.apple.com")
                    .header("Referer", "https://music.apple.com/")
                    .build()
                val jsBody = runCatching {
                    client.newCall(jsReq).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.string()
                    }
                }.getOrNull() ?: continue

                val candidate = selectBestJwtFromScript(
                    scriptText = jsBody,
                    nowSec = recheckSec,
                    rejected = rejectedAppleTokens
                ) ?: continue

                val cached = CachedJwt(candidate.token, candidate.expSec, candidate.isPreferred)
                if (cached.isPreferred) {
                    cachedAppleToken = cached
                    appleTokenBackoffUntilSec = 0L
                    return@withLock cached
                } else if (bestFallback == null) {
                    bestFallback = cached
                }
            }

            if (bestFallback != null) {
                cachedAppleToken = bestFallback
                appleTokenBackoffUntilSec = 0L
                return@withLock bestFallback
            }

            appleTokenBackoffUntilSec = recheckSec + TOKEN_BACKOFF_SECONDS
            logStep("Sin JWT utilizable en scripts de Apple Music; espera 30 min", steps)
            null
        }
    }

    internal fun extractAppleBundlePaths(html: String): List<String> {
        return SCRIPT_SRC_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    internal fun selectBestJwtFromScript(
        scriptText: String,
        nowSec: Long,
        rejected: Set<String> = emptySet()
    ): ParsedJwt? {
        var fallback: ParsedJwt? = null
        for (match in JWT_REGEX.findAll(scriptText)) {
            val rawJwt = match.groupValues[1]
            if (rejected.contains(rawJwt)) continue
            val parsed = parseJwtInfo(rawJwt) ?: continue
            if (parsed.expSec <= nowSec + TOKEN_SAFETY_MARGIN_SECONDS) continue
            if (parsed.isPreferred) return parsed
            if (fallback == null) fallback = parsed
        }
        return fallback
    }

    internal fun parseJwtInfo(rawJwt: String): ParsedJwt? {
        val parts = rawJwt.split('.')
        if (parts.size != 3) return null
        val headerStr = decodeBase64UrlUtf8(parts[0]) ?: return null
        val payloadStr = decodeBase64UrlUtf8(parts[1]) ?: return null
        val headerJson = runCatching { JSONObject(headerStr) }.getOrNull()
        val payloadJson = runCatching { JSONObject(payloadStr) }.getOrNull() ?: return null
        val expSec = payloadJson.optLong("exp", -1L)
        if (expSec <= 0L) return null

        val hasWebPlayKid = headerStr.contains("WebPlayKid", ignoreCase = false) ||
            headerJson?.optString("kid")?.contains("WebPlay", ignoreCase = true) == true
        val hasAmpWebPlay = payloadStr.contains("AMPWebPlay", ignoreCase = false)

        return ParsedJwt(
            token = rawJwt,
            expSec = expSec,
            hasWebPlayKid = hasWebPlayKid,
            hasAmpWebPlay = hasAmpWebPlay
        )
    }

    private fun decodeBase64UrlUtf8(segment: String): String? {
        if (segment.isBlank()) return null
        val remainder = segment.length % 4
        val padded = when (remainder) {
            2 -> "$segment=="
            3 -> "$segment="
            else -> segment
        }
        return runCatching {
            val bytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun markTokenRejected(token: String) {
        rejectedAppleTokens.add(token)
        if (cachedAppleToken?.token == token) {
            cachedAppleToken = null
        }
    }

    private data class HttpResult(val code: Int, val body: String?)

    private fun executeAppleApiGet(
        client: OkHttpClient,
        url: String,
        bearerToken: String
    ): HttpResult {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $bearerToken")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = if (resp.isSuccessful) resp.body?.string() else null
                HttpResult(resp.code, text)
            }
        }.getOrDefault(HttpResult(-1, null))
    }

    private fun querySpotifyCanvas(
        client: OkHttpClient,
        spDc: String,
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        // 1) Obtain anonymous/session web access token using user-supplied sp_dc cookie (never logged)
        val tokenReq = Request.Builder()
            .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
            .header("Cookie", "sp_dc=$spDc")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val tokenBody = client.newCall(tokenReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null
        val accessToken = runCatching { JSONObject(tokenBody).optString("accessToken") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null

        // 2) Search Spotify track URI
        val q = Uri.encode("track:${CanvasArtwork.normalizeCanvasToken(title)} artist:${CanvasArtwork.normalizePrimaryArtist(artist)}")
        val searchReq = Request.Builder()
            .url("https://api.spotify.com/v1/search?q=$q&type=track&limit=5")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
        val searchBody = client.newCall(searchReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null

        val items = runCatching {
            JSONObject(searchBody).optJSONObject("tracks")?.optJSONArray("items")
        }.getOrNull() ?: return null

        var matchedUri: String? = null
        var candTitle = ""
        var candArtist = ""
        var candAlbum: String? = null
        var fallbackImg: String? = null

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val tTitle = item.optString("name").trim()
            val tArtists = item.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.trim() }.joinToString(", ")
            }.orEmpty()
            val albumObj = item.optJSONObject("album")
            val tAlbum = albumObj?.optString("name")?.trim()?.ifBlank { null }
            val uri = item.optString("uri").trim()
            if (uri.isBlank()) continue

            val probe = CanvasArtwork(
                videoUrl = "probe",
                matchedTitle = tTitle,
                matchedArtist = tArtists,
                album = tAlbum,
                source = CanvasSource.SPOTIFY
            )
            if (probe.matches(title, artist, album)) {
                matchedUri = uri
                candTitle = tTitle
                candArtist = tArtists
                candAlbum = tAlbum
                fallbackImg = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                break
            }
        }
        val trackUri = matchedUri ?: return null

        // 3) Query spclient JSON canvas endpoint if available
        val payload = """{"trackUri":"$trackUri"}""".toRequestBody()
        val canvasReq = Request.Builder()
            .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
            .post(payload)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()

        val canvasBody = client.newCall(canvasReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null

        val canvasUrl = runCatching {
            JSONObject(canvasBody).optJSONArray("canvases")?.optJSONObject(0)?.optString("url")
        }.getOrNull()?.trim()?.takeIf { it.startsWith("https://") } ?: return null

        return CanvasArtwork(
            videoUrl = canvasUrl,
            portraitUrl = canvasUrl,
            fallbackImageUrl = fallbackImg,
            matchedTitle = candTitle,
            matchedArtist = candArtist,
            album = candAlbum,
            source = CanvasSource.SPOTIFY,
            variant = CanvasVariant.SPOTIFY_CANVAS,
            sourceLabel = "Spotify Canvas"
        )
    }

    private fun queryTidalVideoCoverCanvas(
        client: OkHttpClient,
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        val cleanArtist = CanvasArtwork.normalizePrimaryArtist(artist).ifBlank { artist }
        val cleanTitle = CanvasArtwork.normalizeCanvasToken(title).ifBlank { title }
        val encoded = Uri.encode("$cleanTitle $cleanArtist")

        val endpoints = listOf(
            "https://api.tidal.com/v1/search/tracks?query=$encoded&limit=6&countryCode=US",
            "https://tidal.401658.xyz/search/?s=$encoded"
        )

        for (url in endpoints) {
            val body = executeGet(
                client = client,
                url = url,
                headers = mapOf("x-tidal-token" to "CzET4vdadNUFQ5JU")
            ) ?: continue

            val items = parseTidalItems(body) ?: continue
            for (i in 0 until items.length()) {
                val trackObj = items.optJSONObject(i) ?: continue
                val candTitle = trackObj.optString("title").trim()
                val candArtist = trackObj.optJSONArray("artists")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.trim() }
                            .joinToString(", ")
                    }
                    ?.ifBlank { null }
                    ?: trackObj.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
                val albumObj = trackObj.optJSONObject("album")
                val candAlbum = albumObj?.optString("title")?.trim()?.ifBlank { null }
                val videoCover = albumObj?.optString("videoCover")?.trim().orEmpty()
                if (videoCover.length < 8) continue

                val uuidPath = videoCover.replace("-", "/")
                val mp4Url = "https://resources.tidal.com/videos/$uuidPath/1280x1280.mp4"
                val coverUuid = albumObj?.optString("cover")?.trim().orEmpty()
                val coverImg = if (coverUuid.isNotBlank()) {
                    "https://resources.tidal.com/images/${coverUuid.replace("-", "/")}/1280x1280.jpg"
                } else null

                val candidate = CanvasArtwork(
                    videoUrl = mp4Url,
                    fallbackUrl = null,
                    portraitUrl = null,
                    fallbackImageUrl = coverImg,
                    matchedTitle = candTitle,
                    matchedArtist = candArtist,
                    album = candAlbum,
                    source = CanvasSource.TIDAL,
                    variant = CanvasVariant.TIDAL_SQUARE,
                    sourceLabel = "Tidal Motion"
                )
                if (candidate.matches(title, artist, album)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun parseTidalItems(body: String): JSONArray? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return root.optJSONArray("items")
            ?: root.optJSONObject("data")?.optJSONArray("items")
            ?: root.optJSONObject("tracks")?.optJSONArray("items")
    }

    private fun queryCommunityCanvasIndex(
        client: OkHttpClient,
        videoId: String,
        title: String,
        artist: String,
        album: String?
    ): CanvasArtwork? {
        val encodedTitle = Uri.encode(CanvasArtwork.normalizeCanvasToken(title))
        val encodedArtist = Uri.encode(CanvasArtwork.normalizePrimaryArtist(artist))
        val url = "https://canvas.paxsenix.org/api/canvas?title=$encodedTitle&artist=$encodedArtist&yt=$videoId"
        val body = executeGet(client, url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val canvasUrl = json.optString("canvasUrl")
            .ifBlank { json.optString("url") }
            .ifBlank { json.optString("videoUrl") }
            .trim()
        if (!canvasUrl.startsWith("https://")) return null

        val candTitle = json.optString("title").ifBlank { title }
        val candArtist = json.optString("artist").ifBlank { artist }
        val candAlbum = json.optString("album").trim().ifBlank { null }
        val candidate = CanvasArtwork(
            videoUrl = canvasUrl,
            portraitUrl = canvasUrl,
            matchedTitle = candTitle,
            matchedArtist = candArtist,
            album = candAlbum,
            source = CanvasSource.OTHER,
            variant = CanvasVariant.OTHER,
            sourceLabel = "Community Canvas"
        )
        return candidate.takeIf { it.matches(title, artist, album) }
    }

    private fun executeGet(
        client: OkHttpClient,
        url: String,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): String? {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", DESKTOP_USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
