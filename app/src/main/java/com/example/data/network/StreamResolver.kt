package com.example.data.network

import android.content.Context
import android.util.Log
import com.example.model.AudioQuality
import com.example.model.ResolvedStream
import com.example.model.Track
import com.example.model.YouTubeAuthSessionPayload
import com.metrolist.innertubex.models.YouTubeClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ProbePlan(
    val rangeStartBytes: Long,
    val rangeEndBytes: Long,
    val windowBytes: Long,
    val contentLengthBytes: Long?
) {
    val rangeHeader: String
        get() = "bytes=$rangeStartBytes-$rangeEndBytes"
}

class StreamResolver(
    private val httpClient: OkHttpClient,
    private val context: Context? = null
) {
    private val fastClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4500, TimeUnit.MILLISECONDS)
            .build()
    }

    private val probeClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val trackMetadata = ConcurrentHashMap<String, TrackMetadataHint>()
    private val urlToUserAgent = ConcurrentHashMap<String, String>()
    private val hostToUserAgent = ConcurrentHashMap<String, String>()
    private val urlToHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val hostToHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Result<ResolvedStream>>>()
    private val lastSourceByVideo = ConcurrentHashMap<String, String>()
    private val failedSourcesByVideo = ConcurrentHashMap<String, MutableSet<String>>()
    private val activeAlternateByOriginal = ConcurrentHashMap<String, String>()
    private val mirrorFailStreak = ConcurrentHashMap<String, Int>()
    private val timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogFallback by lazy { CatalogAudioFallback(httpClient, BROWSER_USER_AGENT) }

    @Volatile
    private var cachedVisitorData: String? = null

    @Volatile
    private var sessionCookies: String? = null

    @Volatile
    private var sessionContext: YouTubeAuthSessionPayload? = null

    @Volatile
    private var preferredQuality: AudioQuality = AudioQuality.MAX

    @Volatile
    private var cachedInvidiousInstances: Pair<Long, List<String>>? = null

    @Volatile
    private var cachedPipedInstances: Pair<Long, List<String>>? = null

    @Volatile
    private var lastYouTubeUserAgent: String? = null

    val poTokenWebViewManager: PoTokenWebViewManager? by lazy {
        context?.let { PoTokenWebViewManager(it) }
    }

    init {
        if (context != null) {
            InnerTubeXResolver.updateProviders(
                cookieProvider = { sessionCookies },
                visitorDataProvider = { ensureVisitorData() },
                tokenProvider = poTokenWebViewManager
            )
        }
    }

    suspend fun warmup() = withContext(Dispatchers.IO) {
        runCatching { ensureNewPipeInitialized() }
        coroutineScope {
            val visitorJob = async { withTimeoutOrNull(2_000L) { ensureVisitorData() } }
            val invJob = async { getHealthyInvidiousInstances() }
            val pipedJob = async { getHealthyPipedInstances() }
            val catalogJob = async { catalogFallback.warmup() }
            visitorJob.await()
            invJob.await()
            pipedJob.await()
            catalogJob.await()
        }
        Unit
    }

    fun getSessionCookies(): String? = sessionCookies

    fun setSessionContext(payload: YouTubeAuthSessionPayload?) {
        val clean = payload?.takeIf { it.cookies.isNotBlank() }
        val prevCookies = sessionCookies
        sessionContext = clean
        NewPipeBootstrapper.sessionContext = clean
        if (!clean?.visitorData.isNullOrBlank()) {
            cachedVisitorData = clean!!.visitorData
        }
        setSessionCookies(clean?.cookies)
        if (prevCookies != clean?.cookies) {
            poTokenWebViewManager?.clearCache()
        }
    }

    fun setSessionCookies(cookies: String?) {
        val cleaned = cookies?.takeIf { it.isNotBlank() }
        val changed = sessionCookies != cleaned
        sessionCookies = cleaned
        if (cleaned == null) {
            sessionContext = null
            NewPipeBootstrapper.sessionContext = null
        }
        NewPipeBootstrapper.sessionCookies = cleaned
        if (changed) {
            poTokenWebViewManager?.clearCache()
        }
        cache.clear()
        failedSourcesByVideo.clear()
    }

    fun setPreferredQuality(quality: AudioQuality) {
        if (preferredQuality != quality) {
            preferredQuality = quality
            cache.clear()
        }
    }

    fun clearCache() {
        cache.clear()
        urlToUserAgent.clear()
        hostToUserAgent.clear()
        urlToHeaders.clear()
        hostToHeaders.clear()
        failedSourcesByVideo.clear()
        activeAlternateByOriginal.clear()
        cachedInvidiousInstances = null
        cachedPipedInstances = null
        poTokenWebViewManager?.clearCache()
    }

    fun registerTrackMetadata(track: Track) {
        if (track.videoId.isBlank() || track.title.isBlank()) return
        trackMetadata[track.videoId] = TrackMetadataHint(
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs
        )
    }

    fun registerTracksMetadata(tracks: List<Track>) {
        tracks.forEach { registerTrackMetadata(it) }
    }

    fun getTrackMetadataHint(videoId: String): TrackMetadataHint? = trackMetadata[videoId]

    fun recordResolvedAlternate(originalVideoId: String, alternateVideoId: String?) {
        if (originalVideoId.isBlank()) return
        if (alternateVideoId.isNullOrBlank() || alternateVideoId == originalVideoId) {
            activeAlternateByOriginal.remove(originalVideoId)
        } else {
            activeAlternateByOriginal[originalVideoId] = alternateVideoId
        }
    }

    fun getResolvedAlternate(originalVideoId: String): String? =
        activeAlternateByOriginal[originalVideoId]

    fun invalidate(videoId: String) {
        cache.remove(videoId)?.let { stream ->
            urlToUserAgent.remove(stream.url)
            urlToHeaders.remove(stream.url)
        }
        activeAlternateByOriginal.remove(videoId)
        val lastSource = lastSourceByVideo[videoId]
        if (lastSource != null) {
            failedSourcesByVideo.getOrPut(videoId) { ConcurrentHashMap.newKeySet() }.add(lastSource)
        }
    }

    fun resetFailedSources(videoId: String) {
        cache.remove(videoId)?.let { stream ->
            urlToUserAgent.remove(stream.url)
            urlToHeaders.remove(stream.url)
        }
        activeAlternateByOriginal.remove(videoId)
        failedSourcesByVideo.remove(videoId)
        lastSourceByVideo.remove(videoId)
    }

    fun getUserAgentForUrl(url: String): String? {
        InnerTubeXResolver.headersFor(url)?.entries
            ?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        urlToUserAgent[url]?.let { return it }
        val host = extractHost(url) ?: return null
        hostToUserAgent[host]?.let { return it }
        if (host.endsWith(".googlevideo.com") || host == "googlevideo.com") {
            return inferYouTubeHeadersForUrl(url)["User-Agent"] ?: lastYouTubeUserAgent
        }
        return null
    }

    fun getUserAgentForHost(host: String): String? {
        val lower = host.lowercase()
        hostToUserAgent[lower]?.let { return it }
        if (lower.endsWith(".googlevideo.com") || lower == "googlevideo.com") {
            return lastYouTubeUserAgent
        }
        return null
    }

    fun getHeadersForUrl(url: String): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        val host = extractHost(url)

        // 1. Inferred headers from c= / cver= params (lowest priority baseline)
        if (host != null && (host.endsWith(".googlevideo.com") || host == "googlevideo.com")) {
            merged.putAll(inferYouTubeHeadersForUrl(url))
        }

        // 2. Host-level cached headers
        if (host != null) {
            hostToHeaders[host]?.let { merged.putAll(it) }
        }

        // 3. StreamResolver cached URL headers
        urlToHeaders[url]?.let { merged.putAll(it) }

        // 4. Exact InnerTubeX client headers that generated this URL (highest priority)
        InnerTubeXResolver.headersFor(url)?.let { itxHeaders ->
            merged.putAll(itxHeaders)
        }

        getUserAgentForUrl(url)?.let { ua ->
            merged.putIfAbsent("User-Agent", ua)
        }

        if (host != null && (host.endsWith(".googlevideo.com") || host == "googlevideo.com")) {
            val isMusicClient = url.contains("c=ANDROID_MUSIC", ignoreCase = true) ||
                url.contains("c=WEB_REMIX", ignoreCase = true)
            val defaultOrigin = if (isMusicClient) "https://music.youtube.com" else "https://www.youtube.com"
            merged.putIfAbsent("Origin", defaultOrigin)
            merged.putIfAbsent("Referer", "$defaultOrigin/")
        }
        return merged
    }

    private fun recordUrlHeaders(url: String, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        urlToHeaders[url] = headers
        extractHost(url)?.let { host ->
            hostToHeaders[host] = headers
        }
    }

    private fun extractHost(url: String): String? {
        return runCatching { URI(url).host?.lowercase()?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun getCachedStream(videoId: String, allowHls: Boolean = true): ResolvedStream? {
        val cached = cache[videoId] ?: return null
        if (!allowHls && isHlsStream(cached)) return null
        return cached
    }

    fun getCachedSourceId(videoId: String): String? = lastSourceByVideo[videoId]

    private fun recordResolved(
        videoId: String,
        sourceId: String,
        stream: ResolvedStream
    ): Result<ResolvedStream> {
        cache[videoId] = stream
        urlToUserAgent[stream.url] = stream.userAgent
        if (stream.headers.isNotEmpty()) {
            recordUrlHeaders(stream.url, stream.headers)
        } else {
            recordUrlHeaders(stream.url, getHeadersForUrl(stream.url) + ("User-Agent" to stream.userAgent))
        }
        extractHost(stream.url)?.let { host ->
            hostToUserAgent[host] = stream.userAgent
        }
        lastSourceByVideo[videoId] = sourceId
        if (stream.sourceVideoId.isNullOrBlank() || stream.sourceVideoId == videoId) {
            activeAlternateByOriginal.remove(videoId)
        } else {
            activeAlternateByOriginal[videoId] = stream.sourceVideoId
        }
        if (sourceId.startsWith("itx:") || sourceId == "newpipe") {
            lastYouTubeUserAgent = stream.userAgent
        }
        Log.i("Sonora", "Resolved stream video=$videoId source=$sourceId codec=${stream.codecLabel}")
        return Result.success(stream)
    }

    private fun ensureNewPipeInitialized() {
        NewPipeBootstrapper.ensureInitialized(httpClient)
    }

    private val fallbackInvidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
        "https://inv.tux.pizza",
        "https://invidious.protokolla.fi",
        "https://invidious.perennialte.ch",
        "https://yt.artemislena.eu",
        "https://invidious.f5.si"
    )

    private val fallbackPipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.nosebs.ru",
        "https://piped-api.garudalinux.org",
        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi.moomoo.me"
    )

    suspend fun resolveAudioStream(
        track: Track,
        allowHls: Boolean = true
    ): Result<ResolvedStream> {
        registerTrackMetadata(track)
        return resolveAudioStream(track.videoId, allowHls)
    }

    suspend fun resolveAudioStreamFast(
        track: Track,
        allowHls: Boolean = true
    ): Result<ResolvedStream> {
        registerTrackMetadata(track)
        val videoId = track.videoId
        val now = System.currentTimeMillis()
        cache[videoId]?.let { cached ->
            if (cached.expiresAtMs > now + 60_000L && (allowHls || !isHlsStream(cached))) {
                return Result.success(cached)
            }
        }
        val failedSources = failedSourcesByVideo[videoId].orEmpty()
        return withContext(Dispatchers.IO) {
            // Try InnerTubeX first with tight budget
            val itxWin = withTimeoutOrNull(4_500L) {
                tryInnerTubeXWithProbe(videoId, maxAttempts = 2)
            }
            if (itxWin != null) {
                return@withContext recordResolved(videoId, itxWin.first, itxWin.second)
            }

            // Fast NewPipe plan B if allowed
            if ("newpipe" !in failedSources) {
                val npCandidates = runBlockingWithHardTimeout(3_000L) {
                    tryNewPipeExtractorCandidates(videoId, allowHls = allowHls)
                }.orEmpty()
                val npPlayable = npCandidates.take(3).firstOrNull { candidate ->
                    val allowProgMp4 = candidate.mimeType.startsWith("video/mp4", ignoreCase = true)
                    probeStreamWithOutcome(
                        stream = candidate,
                        extraHeaders = getHeadersForUrl(candidate.url),
                        allowHls = allowHls,
                        allowProgressiveVideoMp4 = allowProgMp4
                    ) == ProbeOutcome.PLAYABLE
                }
                if (npPlayable != null) {
                    return@withContext recordResolved(videoId, "newpipe", npPlayable)
                }
            }
            Result.failure(IllegalStateException("Sin fuente rápida para pista alterna."))
        }
    }

    suspend fun resolveAudioStream(
        videoId: String,
        allowHls: Boolean = true
    ): Result<ResolvedStream> {
        val now = System.currentTimeMillis()
        cache[videoId]?.let { cached ->
            if (cached.expiresAtMs > now + 60_000L && (allowHls || !isHlsStream(cached))) {
                return Result.success(cached)
            }
        }

        val flightKey = if (allowHls) videoId else "$videoId:no_hls"
        val createdDeferred = CompletableDeferred<Result<ResolvedStream>>()
        val existing = inFlight.putIfAbsent(flightKey, createdDeferred)
        if (existing != null) {
            return existing.await()
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                resolveAudioStreamInternal(videoId, allowHls)
            }
            createdDeferred.complete(result)
            result
        } catch (t: Throwable) {
            val err = Result.failure<ResolvedStream>(
                if (t is Exception) t else IllegalStateException(t.message ?: "Error al resolver audio", t)
            )
            createdDeferred.complete(err)
            err
        } finally {
            inFlight.remove(flightKey, createdDeferred)
        }
    }

    private suspend fun resolveAudioStreamInternal(
        videoId: String,
        allowHls: Boolean
    ): Result<ResolvedStream> {
        val failedSources = failedSourcesByVideo[videoId].orEmpty()

        // a) Primary: InnerTubeXResolver.extract + exact probe validation
        tryInnerTubeXWithProbe(videoId, maxAttempts = 3)?.let { (sourceId, verified) ->
            return recordResolved(videoId, sourceId, verified)
        }

        // b) Plan B: NewPipe (hard timeout 4.5s) + exact probe validation
        if ("newpipe" !in failedSources) {
            val npCandidates = runBlockingWithHardTimeout(4_500L) {
                tryNewPipeExtractorCandidates(videoId, allowHls = allowHls)
            }.orEmpty()

            for (candidate in npCandidates) {
                val isProgVideo = candidate.mimeType.startsWith("video/mp4", ignoreCase = true)
                val headers = getHeadersForUrl(candidate.url)
                if (probeStreamWithOutcome(
                        stream = candidate,
                        extraHeaders = headers,
                        allowHls = allowHls,
                        allowProgressiveVideoMp4 = isProgVideo
                    ) == ProbeOutcome.PLAYABLE
                ) {
                    val withHeaders = candidate.copy(headers = headers)
                    return recordResolved(videoId, "newpipe", withHeaders)
                }
            }
        }

        // c) Last resort: Piped & Invidious mirrors + Studio CatalogAudioFallback
        raceMirrorsAndCatalog(videoId, failedSources, allowHls)?.let { (sourceId, verified) ->
            return recordResolved(videoId, sourceId, verified)
        }

        Log.w("Sonora", "Primary stream resolution failed for video=$videoId (allowHls=$allowHls)")
        return Result.failure(
            IllegalStateException("No se pudo obtener una fuente de audio compatible para este tema.")
        )
    }

    private suspend fun tryInnerTubeXWithProbe(
        videoId: String,
        maxAttempts: Int = 3
    ): Pair<String, ResolvedStream>? {
        if (context == null) return null
        val skipClients = mutableSetOf<String>()

        repeat(maxAttempts) {
            val extracted = InnerTubeXResolver.extract(
                videoId = videoId,
                quality = preferredQuality,
                skipClients = skipClients
            ) ?: return null

            if (extracted.mimeType.contains("mpegURL", ignoreCase = true) ||
                extracted.url.contains(".m3u8", ignoreCase = true)
            ) {
                skipClients.add(extracted.profileId)
                return@repeat
            }

            val ua = extracted.headers.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?: inferYouTubeHeadersForUrl(extracted.url)["User-Agent"]
                ?: BROWSER_USER_AGENT

            val playbackHeaders = linkedMapOf<String, String>().apply {
                putAll(inferYouTubeHeadersForUrl(extracted.url))
                putAll(extracted.headers)
                put("User-Agent", ua)
            }

            val candidate = ResolvedStream(
                url = extracted.url,
                mimeType = extracted.mimeType,
                bitrate = extracted.bitrate,
                userAgent = ua,
                expiresAtMs = extracted.expiresAtMs,
                codecLabel = formatCodecLabel(extracted.mimeType, extracted.bitrate),
                headers = playbackHeaders,
                profileId = extracted.profileId
            )

            when (
                probeStreamWithOutcome(
                    stream = candidate,
                    extraHeaders = playbackHeaders,
                    allowHls = false,
                    allowProgressiveVideoMp4 = false,
                    clientHint = extracted.profileId
                )
            ) {
                ProbeOutcome.PLAYABLE -> {
                    Log.i("Sonora", "ITX probe playable video=$videoId client=${extracted.clientName} profile=${extracted.profileId}")
                    return "itx:${extracted.clientName}" to candidate
                }
                ProbeOutcome.FORBIDDEN_OR_GONE -> {
                    Log.w("Sonora", "ITX probe refused (403/410) video=$videoId client=${extracted.clientName} profile=${extracted.profileId}")
                    InnerTubeXResolver.onRefused(extracted.url)
                    skipClients.add(extracted.profileId)
                }
                ProbeOutcome.FAILED -> {
                    Log.w("Sonora", "ITX probe failed video=$videoId client=${extracted.clientName} profile=${extracted.profileId}")
                    skipClients.add(extracted.profileId)
                }
            }
        }
        return null
    }

    private suspend fun resolveHintWithOEmbedFallback(videoId: String): TrackMetadataHint? {
        val local = trackMetadata[videoId]
        val genericArtists = setOf("", "youtube music", "artista desconocido", "various artists", "topic")
        if (local != null && local.title.isNotBlank() && local.artist.trim().lowercase() !in genericArtists) {
            return local
        }
        val oembed = catalogFallback.fetchYouTubeOEmbedHint(videoId)
        return when {
            local != null && oembed != null -> local.copy(
                title = local.title.ifBlank { oembed.title },
                artist = if (local.artist.trim().lowercase() in genericArtists) oembed.artist else local.artist
            )
            local != null -> local
            else -> oembed
        }
    }

    private suspend fun raceMirrorsAndCatalog(
        videoId: String,
        failedSources: Set<String>,
        allowHls: Boolean
    ): Pair<String, ResolvedStream>? = withTimeoutOrNull(8_500L) {
        coroutineScope {
            val mirrorDeferred = async(Dispatchers.IO) {
                raceMirrors(videoId, failedSources, allowHls)
            }
            val catalogDeferred = async(Dispatchers.IO) {
                val hint = resolveHintWithOEmbedFallback(videoId)
                if (hint != null && hint.title.isNotBlank()) {
                    catalogFallback.resolveStudioFallback(
                        hint = hint,
                        quality = preferredQuality,
                        failedSources = failedSources,
                        probePlayable = { stream ->
                            if (!allowHls && isHlsStream(stream)) {
                                false
                            } else {
                                probeStreamWithOutcome(
                                    stream = stream,
                                    extraHeaders = stream.headers,
                                    allowHls = allowHls,
                                    allowProgressiveVideoMp4 = false
                                ) == ProbeOutcome.PLAYABLE
                            }
                        }
                    )
                } else {
                    null
                }
            }

            // Give exact videoId mirrors priority before accepting catalog audio
            val mirrorWin = withTimeoutOrNull(2_400L) { mirrorDeferred.await() }
            if (mirrorWin != null) {
                catalogDeferred.cancel()
                return@coroutineScope mirrorWin
            }

            val catalogWin = withTimeoutOrNull(5_800L) { catalogDeferred.await() }
            if (catalogWin != null) {
                mirrorDeferred.cancel()
                return@coroutineScope catalogWin
            }

            withTimeoutOrNull(1_000L) { mirrorDeferred.await() }
        }
    }

    private suspend fun getHealthyInvidiousInstances(): List<String> {
        val now = System.currentTimeMillis()
        cachedInvidiousInstances?.let { (ts, list) ->
            if (now - ts < 20 * 60_000L && list.isNotEmpty()) return list
        }
        val discovered = withTimeoutOrNull(3_000L) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.invidious.io/instances.json?sort_by=type,health")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build()
                probeClient.executeCancellable(req).use { resp ->
                    if (!resp.isSuccessful) return@runCatching emptyList()
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    val urls = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val entry = arr.optJSONArray(i) ?: continue
                        val meta = entry.optJSONObject(1) ?: continue
                        val type = meta.optString("type")
                        val apiEnabled = meta.optBoolean("api", false)
                        val uri = meta.optValidUrl("uri")
                        if (type == "https" && apiEnabled && uri != null) {
                            urls.add(uri.trimEnd('/'))
                            if (urls.size >= 8) break
                        }
                    }
                    urls
                }
            }.getOrDefault(emptyList())
        }.orEmpty()

        val merged = (discovered + fallbackInvidiousInstances).distinct().take(10)
        cachedInvidiousInstances = now to merged
        return merged
    }

    private suspend fun getHealthyPipedInstances(): List<String> {
        val now = System.currentTimeMillis()
        cachedPipedInstances?.let { (ts, list) ->
            if (now - ts < 20 * 60_000L && list.isNotEmpty()) return list
        }
        val discovered = withTimeoutOrNull(3_000L) {
            runCatching {
                val req = Request.Builder()
                    .url("https://piped-instances.kavin.rocks/")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build()
                probeClient.executeCancellable(req).use { resp ->
                    if (!resp.isSuccessful) return@runCatching emptyList()
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    val urls = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val apiUrl = item.optValidUrl("api_url")?.trimEnd('/') ?: continue
                        val upToDate = item.optBoolean("up_to_date", true)
                        if (upToDate && apiUrl.startsWith("https://")) {
                            urls.add(apiUrl)
                            if (urls.size >= 8) break
                        }
                    }
                    urls
                }
            }.getOrDefault(emptyList())
        }.orEmpty()

        val merged = (discovered + fallbackPipedInstances).distinct().take(10)
        cachedPipedInstances = now to merged
        return merged
    }

    private suspend fun raceMirrors(
        videoId: String,
        failedSources: Set<String>,
        allowHls: Boolean
    ): Pair<String, ResolvedStream>? = withTimeoutOrNull(2_400L) {
        coroutineScope {
            val invPool = (cachedInvidiousInstances?.second?.ifEmpty { fallbackInvidiousInstances }
                ?: fallbackInvidiousInstances)
                .filter { "inv:$it" !in failedSources }
                .sortedBy { mirrorFailStreak["inv:$it"] ?: 0 }
                .take(3)
            val pipedPool = (cachedPipedInstances?.second?.ifEmpty { fallbackPipedInstances }
                ?: fallbackPipedInstances)
                .filter { "piped:$it" !in failedSources }
                .sortedBy { mirrorFailStreak["piped:$it"] ?: 0 }
                .take(3)

            val jobs = buildList {
                invPool.forEach { instance ->
                    val id = "inv:$instance"
                    add(async(Dispatchers.IO) {
                        val stream = tryInvidiousInstancePlayable(instance, videoId, allowHls)
                        if (stream != null) {
                            mirrorFailStreak[id] = 0
                            id to stream
                        } else {
                            mirrorFailStreak.merge(id, 1, Int::plus)
                            null
                        }
                    })
                }
                pipedPool.forEach { instance ->
                    val id = "piped:$instance"
                    add(async(Dispatchers.IO) {
                        val stream = tryPipedInstance(instance, videoId, allowHls)
                        if (stream != null &&
                            probeStreamWithOutcome(
                                stream = stream,
                                extraHeaders = stream.headers,
                                allowHls = allowHls,
                                allowProgressiveVideoMp4 = false
                            ) == ProbeOutcome.PLAYABLE
                        ) {
                            mirrorFailStreak[id] = 0
                            id to stream
                        } else {
                            mirrorFailStreak.merge(id, 1, Int::plus)
                            null
                        }
                    })
                }
            }
            if (jobs.isEmpty()) return@coroutineScope null

            val remaining = jobs.toMutableList()
            var winner: Pair<String, ResolvedStream>? = null
            while (remaining.isNotEmpty() && winner == null) {
                val selected = select {
                    remaining.forEach { deferred ->
                        deferred.onAwait { res -> deferred to res }
                    }
                }
                remaining.remove(selected.first)
                if (selected.second != null) {
                    winner = selected.second
                }
            }
            remaining.forEach { it.cancel() }
            winner
        }
    }

    private enum class ProbeOutcome {
        PLAYABLE,
        FORBIDDEN_OR_GONE,
        FAILED
    }

    private suspend fun probeStreamWithOutcome(
        stream: ResolvedStream,
        extraHeaders: Map<String, String> = emptyMap(),
        allowHls: Boolean = true,
        allowProgressiveVideoMp4: Boolean = false,
        clientHint: String? = null
    ): ProbeOutcome {
        if (!isValidHttpUrl(stream.url)) return ProbeOutcome.FAILED
        val isHls = isHlsStream(stream)
        if (isHls && !allowHls) return ProbeOutcome.FAILED

        return withTimeoutOrNull(6_000L) {
            runCatching {
                val playbackHeaders = linkedMapOf<String, String>().apply {
                    putAll(extraHeaders)
                    putAll(stream.headers)
                    if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        put("User-Agent", stream.userAgent)
                    }
                }

                val reqBuilder = Request.Builder().url(stream.url)
                playbackHeaders.forEach { (k, v) ->
                    if (v.isNotBlank()) {
                        reqBuilder.header(k, v)
                    }
                }

                val plan = if (!isHls) {
                    computeProbePlan(stream.url, clientHint ?: stream.profileId).also {
                        reqBuilder.header("Range", it.rangeHeader)
                    }
                } else {
                    null
                }

                val outcome = probeClient.executeCancellable(reqBuilder.get().build()).use { resp ->
                    when {
                        resp.code == 403 || resp.code == 404 || resp.code == 410 -> {
                            Log.w("StreamResolver", "Probe rejected HTTP ${resp.code} for ${extractHost(stream.url)}")
                            ProbeOutcome.FORBIDDEN_OR_GONE
                        }
                        !isHls && resp.code == 416 && plan != null && plan.contentLengthBytes == null -> {
                            null // retry once without Range on non-clen third-party server
                        }
                        resp.code !in 200..299 && resp.code != 206 -> {
                            Log.w("StreamResolver", "Probe failed HTTP ${resp.code} for ${extractHost(stream.url)}")
                            ProbeOutcome.FAILED
                        }
                        isHls -> validateHlsResponse(resp, stream.url)
                        else -> validateAudioProbeResponse(resp, stream.url, allowProgressiveVideoMp4)
                    }
                }
                if (outcome != null) return@runCatching outcome

                // Single fallback for third-party non-clen hosts rejecting Range 416
                val plainReqBuilder = Request.Builder().url(stream.url)
                playbackHeaders.forEach { (k, v) ->
                    if (v.isNotBlank()) plainReqBuilder.header(k, v)
                }
                probeClient.executeCancellable(plainReqBuilder.get().build()).use { resp ->
                    when {
                        resp.code == 403 || resp.code == 404 || resp.code == 410 -> ProbeOutcome.FORBIDDEN_OR_GONE
                        resp.code !in 200..299 -> ProbeOutcome.FAILED
                        else -> validateAudioProbeResponse(resp, stream.url, allowProgressiveVideoMp4)
                    }
                }
            }.getOrDefault(ProbeOutcome.FAILED)
        } ?: ProbeOutcome.FAILED
    }

    private fun validateHlsResponse(resp: Response, url: String): ProbeOutcome {
        val preview = runCatching { resp.peekBody(8192).string() }.getOrDefault("")
        val hasM3u = preview.contains("#EXTM3U")
        val hasDrm = preview.contains("SAMPLE-AES", ignoreCase = true) ||
            preview.contains("skd://", ignoreCase = true) ||
            preview.contains("com.apple.streamingkeydelivery", ignoreCase = true) ||
            preview.contains("FAIRPLAY", ignoreCase = true) ||
            preview.contains("WIDEVINE", ignoreCase = true)
        if (hasDrm) {
            Log.w("StreamResolver", "Rejected DRM-encrypted HLS stream from ${extractHost(url)}")
        }
        return if (hasM3u && !hasDrm) ProbeOutcome.PLAYABLE else ProbeOutcome.FAILED
    }

    private fun validateAudioProbeResponse(
        resp: Response,
        url: String,
        allowProgressiveVideoMp4: Boolean
    ): ProbeOutcome {
        val contentType = (resp.header("Content-Type") ?: resp.body?.contentType()?.toString()).orEmpty().trim()
        val mimeMain = contentType.substringBefore(';').trim().lowercase()
        val isAudioMime = mimeMain.startsWith("audio/")
        val isAllowedProg = allowProgressiveVideoMp4 && mimeMain == "video/mp4"
        if (!isAudioMime && !isAllowedProg) {
            Log.w(
                "StreamResolver",
                "Rejected invalid Content-Type ($contentType) from ${extractHost(url)} (allowProg=$allowProgressiveVideoMp4)"
            )
            return ProbeOutcome.FAILED
        }

        val input = resp.body?.byteStream() ?: return ProbeOutcome.FAILED
        val buf = ByteArray(4096)
        var totalRead = 0
        while (totalRead < MIN_PROBE_BODY_BYTES) {
            val toRead = minOf(buf.size, MIN_PROBE_BODY_BYTES - totalRead)
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            totalRead += n
        }
        if (totalRead < MIN_PROBE_BODY_BYTES) {
            Log.w(
                "StreamResolver",
                "Rejected short probe body ($totalRead < $MIN_PROBE_BODY_BYTES bytes) from ${extractHost(url)}"
            )
            return ProbeOutcome.FAILED
        }
        return ProbeOutcome.PLAYABLE
    }

    private suspend fun probeFirstPlayable(
        candidates: List<ResolvedStream>,
        extraHeaders: Map<String, String> = emptyMap(),
        allowHls: Boolean = true,
        allowProgressiveVideoMp4: Boolean = false
    ): ResolvedStream? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) {
            val single = candidates.first()
            return if (probeStreamWithOutcome(
                    stream = single,
                    extraHeaders = extraHeaders,
                    allowHls = allowHls,
                    allowProgressiveVideoMp4 = allowProgressiveVideoMp4
                ) == ProbeOutcome.PLAYABLE
            ) single else null
        }
        return coroutineScope {
            val jobs = candidates.mapIndexed { idx, candidate ->
                idx to async(Dispatchers.IO) {
                    val isProg = allowProgressiveVideoMp4 &&
                        candidate.mimeType.startsWith("video/mp4", ignoreCase = true)
                    probeStreamWithOutcome(
                        stream = candidate,
                        extraHeaders = extraHeaders,
                        allowHls = allowHls,
                        allowProgressiveVideoMp4 = isProg
                    ) == ProbeOutcome.PLAYABLE
                }
            }
            for ((idx, job) in jobs) {
                if (job.await()) {
                    jobs.forEach { (_, other) -> if (!other.isCompleted) other.cancel() }
                    return@coroutineScope candidates[idx]
                }
            }
            null
        }
    }

    private suspend fun <T> runBlockingWithHardTimeout(
        timeoutMs: Long,
        block: () -> T
    ): T? {
        val deferred = timeoutScope.async { block() }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            if (!deferred.isCompleted) {
                deferred.cancel()
            }
        }
    }

    private suspend fun OkHttpClient.executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                    } else {
                        cont.resume(response)
                    }
                }
            })
        }

    private fun tryNewPipeExtractorCandidates(
        videoId: String,
        allowHls: Boolean
    ): List<ResolvedStream> {
        return runCatching {
            ensureNewPipeInitialized()
            val info = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId"
            )
            val expiresAt = System.currentTimeMillis() + 45 * 60_000L
            val results = mutableListOf<ResolvedStream>()

            val progressiveAudioCandidates = info.audioStreams
                .filter {
                    it.isUrl &&
                        isValidHttpUrl(it.content) &&
                        it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                }
                .map { stream ->
                    val kbps = stream.averageBitrate.coerceAtLeast(64)
                    val mime = stream.format?.mimeType ?: "audio/mp4"
                    val codecName = if (mime.contains("webm", true) || mime.contains("opus", true)) "OPUS" else "AAC"
                    val inferred = inferYouTubeHeadersForUrl(stream.content)
                    val matchedUa = inferred["User-Agent"] ?: BROWSER_USER_AGENT
                    ResolvedStream(
                        url = stream.content,
                        mimeType = mime,
                        bitrate = kbps * 1000,
                        userAgent = matchedUa,
                        expiresAtMs = expiresAt,
                        codecLabel = "$codecName $kbps kbps",
                        headers = inferred
                    )
                }

            val bestAudio = selectStreamByQuality(progressiveAudioCandidates)
            if (bestAudio != null) {
                results.add(bestAudio)
            }
            progressiveAudioCandidates
                .filter { it.url != bestAudio?.url }
                .take(2)
                .forEach { results.add(it) }

            if (allowHls) {
                val hlsUrl = info.hlsUrl?.takeIf { isValidHttpUrl(it) }
                if (!hlsUrl.isNullOrBlank()) {
                    val inferred = inferYouTubeHeadersForUrl(hlsUrl)
                    results.add(
                        ResolvedStream(
                            url = hlsUrl,
                            mimeType = "application/x-mpegURL",
                            bitrate = 128_000,
                            userAgent = inferred["User-Agent"] ?: BROWSER_USER_AGENT,
                            expiresAtMs = expiresAt,
                            codecLabel = "HLS Audio",
                            headers = inferred
                        )
                    )
                }
            }

            // Keep progressive video/mp4 fallback strictly behind audio-only and HLS candidates
            if (results.isEmpty()) {
                val fallbackVideo = info.videoStreams
                    .filter {
                        it.isUrl &&
                            isValidHttpUrl(it.content) &&
                            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                    }
                    .minByOrNull { it.resolution?.filter(Char::isDigit)?.toIntOrNull() ?: 360 }

                if (fallbackVideo != null) {
                    val inferred = inferYouTubeHeadersForUrl(fallbackVideo.content)
                    results.add(
                        ResolvedStream(
                            url = fallbackVideo.content,
                            mimeType = fallbackVideo.format?.mimeType ?: "video/mp4",
                            bitrate = 128_000,
                            userAgent = inferred["User-Agent"] ?: BROWSER_USER_AGENT,
                            expiresAtMs = expiresAt,
                            codecLabel = "AAC Stream",
                            headers = inferred
                        )
                    )
                }
            }
            results.distinctBy { it.url }
        }.onFailure { err ->
            Log.w("StreamResolver", "NewPipeExtractor failed for $videoId: ${err.message}")
        }.getOrDefault(emptyList())
    }

    suspend fun ensureVisitorData(): String? {
        cachedVisitorData?.let { return it }
        val fromSw = runCatching {
            val req = Request.Builder()
                .url("https://music.youtube.com/sw.js_data")
                .header("User-Agent", BROWSER_USER_AGENT)
                .get()
                .build()
            fastClient.executeCancellable(req).use { resp ->
                val raw = resp.body?.string().orEmpty()
                Regex("\"(Cg[a-zA-Z0-9_%-]{15,})\"").find(raw)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()

        val fetched = fromSw ?: runCatching {
            val body = JSONObject().put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", "2.20260115.01.00")
                )
            )
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            fastClient.executeCancellable(req).use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) null
                else JSONObject(raw).optJSONObject("responseContext")?.optString("visitorData")
            }
        }.getOrNull()

        if (!fetched.isNullOrBlank() && fetched != "null") {
            cachedVisitorData = fetched
        }
        return cachedVisitorData
    }

    private suspend fun tryInvidiousInstancePlayable(
        baseUrl: String,
        videoId: String,
        allowHls: Boolean
    ): ResolvedStream? {
        val json = runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/v1/videos/$videoId?local=true")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            fastClient.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank() || !raw.trimStart().startsWith("{")) return@runCatching null
                JSONObject(raw)
            }
        }.getOrNull() ?: return null

        val candidates = parseInvidiousCandidates(baseUrl, json, includeProgressiveVideo = false)
            .let { list -> if (allowHls) list else list.filterNot { isHlsStream(it) } }
        return probeFirstPlayable(candidates.take(3), allowHls = allowHls)
    }

    internal fun parseInvidiousJson(baseUrl: String, json: JSONObject): ResolvedStream? {
        return parseInvidiousCandidates(baseUrl, json, includeProgressiveVideo = true).firstOrNull()
    }

    internal fun parseInvidiousCandidates(
        baseUrl: String,
        json: JSONObject,
        includeProgressiveVideo: Boolean = true
    ): List<ResolvedStream> {
        val adaptive = json.optJSONArray("adaptiveFormats") ?: JSONArray()
        val candidates = mutableListOf<ResolvedStream>()
        for (i in 0 until adaptive.length()) {
            val fmt = adaptive.optJSONObject(i) ?: continue
            val type = fmt.optString("type")
            if (!type.startsWith("audio/")) continue
            val rawUrl = fmt.optValidUrl("url", allowRelative = true) ?: continue
            val fullUrl = if (rawUrl.startsWith("/")) "${baseUrl.trimEnd('/')}$rawUrl" else rawUrl
            if (!isValidHttpUrl(fullUrl)) continue
            val bitrate = fmt.optString("bitrate").toIntOrNull() ?: fmt.optInt("bitrate", 128_000)
            val isOpus = type.contains("opus", ignoreCase = true)
            candidates.add(
                ResolvedStream(
                    url = fullUrl,
                    mimeType = type,
                    bitrate = bitrate,
                    userAgent = BROWSER_USER_AGENT,
                    expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                    codecLabel = if (isOpus) "OPUS ${bitrate / 1000} kbps" else "AAC ${bitrate / 1000} kbps"
                )
            )
        }
        val ordered = mutableListOf<ResolvedStream>()
        val best = selectStreamByQuality(candidates)
        if (best != null) ordered.add(best)
        ordered.addAll(candidates.filter { it.url != best?.url }.sortedByDescending { it.bitrate })

        val hlsUrl = json.optValidUrl("hlsUrl", allowRelative = true)
        if (hlsUrl != null) {
            val fullHls = if (hlsUrl.startsWith("/")) "${baseUrl.trimEnd('/')}$hlsUrl" else hlsUrl
            if (isValidHttpUrl(fullHls)) {
                ordered.add(
                    ResolvedStream(
                        url = fullHls,
                        mimeType = "application/x-mpegURL",
                        bitrate = 128_000,
                        userAgent = BROWSER_USER_AGENT,
                        expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                        codecLabel = "HLS Audio"
                    )
                )
            }
        }

        if (includeProgressiveVideo) {
            val formatStreams = json.optJSONArray("formatStreams") ?: JSONArray()
            for (i in 0 until formatStreams.length()) {
                val fmt = formatStreams.optJSONObject(i) ?: continue
                val rawUrl = fmt.optValidUrl("url", allowRelative = true) ?: continue
                val fullUrl = if (rawUrl.startsWith("/")) "${baseUrl.trimEnd('/')}$rawUrl" else rawUrl
                if (!isValidHttpUrl(fullUrl)) continue
                ordered.add(
                    ResolvedStream(
                        url = fullUrl,
                        mimeType = fmt.optString("type", "video/mp4"),
                        bitrate = 128_000,
                        userAgent = BROWSER_USER_AGENT,
                        expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                        codecLabel = "AAC Stream"
                    )
                )
            }
        }
        return ordered.distinctBy { it.url }
    }

    private suspend fun tryPipedInstance(
        baseUrl: String,
        videoId: String,
        allowHls: Boolean
    ): ResolvedStream? {
        return runCatching {
            val req = Request.Builder()
                .url("$baseUrl/streams/$videoId")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            fastClient.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank() || !raw.trimStart().startsWith("{")) return@runCatching null
                parsePipedJson(JSONObject(raw), allowHls = allowHls)
            }
        }.getOrNull()
    }

    internal fun parsePipedJson(
        json: JSONObject,
        allowHls: Boolean = true
    ): ResolvedStream? {
        val audioStreams = json.optJSONArray("audioStreams") ?: JSONArray()
        val candidates = mutableListOf<ResolvedStream>()
        for (i in 0 until audioStreams.length()) {
            val item = audioStreams.optJSONObject(i) ?: continue
            val streamUrl = item.optValidUrl("url") ?: continue
            val bitrate = item.optInt("bitrate", 0)
            val mime = item.optString("mimeType", "audio/mp4")
            val rawCodec = if (item.isNull("codec")) "Audio" else item.optString("codec", "Audio")
            candidates.add(
                ResolvedStream(
                    url = streamUrl,
                    mimeType = mime,
                    bitrate = bitrate,
                    userAgent = BROWSER_USER_AGENT,
                    expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                    codecLabel = "${rawCodec.ifBlank { "Audio" }.uppercase()} ${bitrate / 1000} kbps"
                )
            )
        }
        selectStreamByQuality(candidates)?.let { return it }

        if (allowHls) {
            val hls = json.optValidUrl("hls")
            if (hls != null) {
                return ResolvedStream(
                    url = hls,
                    mimeType = "application/x-mpegURL",
                    bitrate = 128_000,
                    userAgent = BROWSER_USER_AGENT,
                    expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                    codecLabel = "HLS Audio"
                )
            }
        }
        return null
    }

    internal fun selectStreamByQuality(candidates: List<ResolvedStream>): ResolvedStream? {
        if (candidates.isEmpty()) return null
        return when (preferredQuality) {
            AudioQuality.MAX -> candidates.maxByOrNull { stream ->
                val isOpus = stream.mimeType.contains("opus", ignoreCase = true)
                stream.bitrate + if (isOpus) 15_000 else 0
            }
            AudioQuality.HIGH -> {
                val underTarget = candidates.filter { it.bitrate in 1..AudioQuality.HIGH.targetBitrateBps }
                if (underTarget.isNotEmpty()) {
                    underTarget.maxByOrNull { stream ->
                        val isOpus = stream.mimeType.contains("opus", ignoreCase = true)
                        stream.bitrate + if (isOpus) 10_000 else 0
                    }
                } else {
                    candidates.minByOrNull { it.bitrate }
                }
            }
            AudioQuality.DATA_SAVER -> {
                val viable = candidates.filter { it.bitrate >= 40_000 }
                (viable.ifEmpty { candidates }).minByOrNull { it.bitrate }
            }
        }
    }

    private fun formatCodecLabel(mimeType: String, bitrateBps: Int): String {
        val kbps = (bitrateBps / 1000).takeIf { it > 0 } ?: 128
        val codec = when {
            mimeType.contains("opus", ignoreCase = true) -> "OPUS"
            mimeType.contains("webm", ignoreCase = true) -> "WEBM"
            else -> "AAC"
        }
        return "$codec $kbps kbps"
    }

    private fun isHlsStream(stream: ResolvedStream): Boolean =
        stream.mimeType.contains("mpegURL", ignoreCase = true) ||
            stream.url.contains(".m3u8", ignoreCase = true)

    private fun JSONObject.optValidUrl(key: String, allowRelative: Boolean = false): String? {
        if (isNull(key)) return null
        val value = optString(key, "").trim()
        if (value.isBlank() || value.equals("null", ignoreCase = true) || value.equals("undefined", ignoreCase = true)) {
            return null
        }
        val valid = isValidHttpUrl(value) || (allowRelative && value.startsWith("/"))
        return if (valid) value else null
    }

    private fun isValidHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
    }

    companion object {
        const val ONE_MIB = 1_048_576L
        const val HALF_MIB = 524_288L
        const val MIN_PROBE_BODY_BYTES = 16_384

        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        const val MOBILE_BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

        private val CLIENT_PARAM_REGEX = Regex("[?&]c=([^&#]+)", RegexOption.IGNORE_CASE)
        private val CLIENT_VERSION_REGEX = Regex("[?&]cver=([^&#]+)", RegexOption.IGNORE_CASE)
        private val CLEN_PARAM_REGEX = Regex("[?&]clen=(\\d+)", RegexOption.IGNORE_CASE)

        internal fun extractQueryParam(regex: Regex, url: String): String? {
            val raw = regex.find(url)?.groupValues?.getOrNull(1)?.trim() ?: return null
            if (raw.isEmpty()) return null
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim()
        }

        internal fun computeProbePlan(url: String, clientHint: String? = null): ProbePlan {
            val clen = extractQueryParam(CLEN_PARAM_REGEX, url)?.toLongOrNull()?.takeIf { it > 0L }
            val urlClient = extractQueryParam(CLIENT_PARAM_REGEX, url)?.uppercase()
            val effectiveClient = urlClient ?: clientHint?.uppercase().orEmpty()
            val windowBytes = if (effectiveClient == "ANDROID_VR" || effectiveClient == "TVHTML5_SIMPLY") {
                HALF_MIB
            } else {
                ONE_MIB
            }
            val startBytes = if (clen != null && clen > ONE_MIB + MIN_PROBE_BODY_BYTES) {
                ONE_MIB
            } else {
                0L
            }
            val rawEnd = startBytes + windowBytes - 1L
            val endBytes = if (clen != null) {
                minOf(rawEnd, clen - 1L).coerceAtLeast(startBytes)
            } else {
                rawEnd
            }
            return ProbePlan(
                rangeStartBytes = startBytes,
                rangeEndBytes = endBytes,
                windowBytes = windowBytes,
                contentLengthBytes = clen
            )
        }

        internal fun isHalfMegabyteRangeClient(url: String): Boolean {
            val c = extractQueryParam(CLIENT_PARAM_REGEX, url)?.uppercase()
            return c == "ANDROID_VR" || c == "TVHTML5_SIMPLY"
        }

        internal fun inferYouTubeHeadersForUrl(url: String): Map<String, String> {
            val c = extractQueryParam(CLIENT_PARAM_REGEX, url)?.uppercase() ?: return emptyMap()
            val cver = extractQueryParam(CLIENT_VERSION_REGEX, url)?.takeIf { it.isNotBlank() }

            val baseUa = when (c) {
                "ANDROID_VR" -> when (cver) {
                    "1.43.32" -> YouTubeClient.ANDROID_VR_1_43_32.userAgent
                    "1.61.48" -> YouTubeClient.ANDROID_VR_1_61_48.userAgent
                    "1.65.10" -> YouTubeClient.ANDROID_VR_1_65_10.userAgent
                    else -> YouTubeClient.ANDROID_VR_NO_AUTH.userAgent.let { defaultUa ->
                        if (cver != null) {
                            "com.google.android.apps.youtube.vr.oculus/$cver (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
                        } else {
                            defaultUa
                        }
                    }
                }
                "IOS" -> if (cver != null) {
                    "com.google.ios.youtube/$cver (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)"
                } else {
                    YouTubeClient.IOS.userAgent
                }
                "IOS_MUSIC" -> if (cver != null) {
                    "com.google.ios.youtubemusic/$cver (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)"
                } else {
                    YouTubeClient.IOS_MUSIC.userAgent
                }
                "IPADOS" -> YouTubeClient.IPADOS.userAgent
                "VISIONOS" -> YouTubeClient.VISIONOS.userAgent
                "ANDROID" -> if (cver != null) {
                    "com.google.android.youtube/$cver (Linux; U; Android 14) gzip"
                } else {
                    YouTubeClient.ANDROID.userAgent
                }
                "ANDROID_MUSIC" -> if (cver != null) {
                    "com.google.android.apps.youtube.music/$cver (Linux; U; Android 14) gzip"
                } else {
                    YouTubeClient.ANDROID_MUSIC.userAgent
                }
                "ANDROID_CREATOR" -> if (cver != null) {
                    "com.google.android.apps.youtube.creator/$cver (Linux; U; Android 14) gzip"
                } else {
                    YouTubeClient.ANDROID_CREATOR.userAgent
                }
                "ANDROID_KIDS" -> YouTubeClient.ANDROID_KIDS.userAgent
                "ANDROID_PRODUCER" -> YouTubeClient.ANDROID_PRODUCER.userAgent
                "TVHTML5", "TVHTML5_SIMPLY", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> YouTubeClient.TVHTML5.userAgent
                "MWEB" -> YouTubeClient.MWEB.userAgent
                "WEB_REMIX" -> YouTubeClient.WEB_REMIX.userAgent
                "WEB_SAFARI" -> YouTubeClient.WEB_SAFARI.userAgent
                "WEB", "WEB_EMBEDDED_PLAYER", "WEB_CREATOR", "WEB_KIDS" -> YouTubeClient.WEB.userAgent
                else -> BROWSER_USER_AGENT
            }

            val origin = when (c) {
                "ANDROID_MUSIC", "WEB_REMIX", "IOS_MUSIC" -> "https://music.youtube.com"
                "MWEB" -> "https://m.youtube.com"
                else -> "https://www.youtube.com"
            }

            return mapOf(
                "User-Agent" to baseUa,
                "Origin" to origin,
                "Referer" to "$origin/"
            )
        }
    }
}
