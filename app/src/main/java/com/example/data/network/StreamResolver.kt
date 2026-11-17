package com.example.data.network

import android.util.Log
import com.example.model.AudioQuality
import com.example.model.ResolvedStream
import com.example.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class StreamResolver(
    private val httpClient: OkHttpClient
) {
    private val fastClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val probeClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val trackMetadata = ConcurrentHashMap<String, TrackMetadataHint>()
    private val urlToUserAgent = ConcurrentHashMap<String, String>()
    private val hostToUserAgent = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Result<ResolvedStream>>>()
    private val lastSourceByVideo = ConcurrentHashMap<String, String>()
    private val failedSourcesByVideo = ConcurrentHashMap<String, MutableSet<String>>()
    private val timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogFallback by lazy { CatalogAudioFallback(httpClient, BROWSER_USER_AGENT) }

    @Volatile
    private var cachedVisitorData: String? = null

    @Volatile
    private var cachedSts: Int? = null

    @Volatile
    private var sessionCookies: String? = null

    @Volatile
    private var preferredQuality: AudioQuality = AudioQuality.MAX

    @Volatile
    private var cachedInvidiousInstances: Pair<Long, List<String>>? = null

    @Volatile
    private var cachedPipedInstances: Pair<Long, List<String>>? = null

    @Volatile
    private var ytBypassCooldownUntilMs: Long = 0L

    private val consecutiveYtFailures = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun warmup() = withContext(Dispatchers.IO) {
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

    fun setSessionCookies(cookies: String?) {
        val cleaned = cookies?.takeIf { it.isNotBlank() }
        sessionCookies = cleaned
        NewPipeBootstrapper.sessionCookies = cleaned
        cache.clear()
        failedSourcesByVideo.clear()
        consecutiveYtFailures.set(0)
        ytBypassCooldownUntilMs = 0L
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
        failedSourcesByVideo.clear()
        cachedInvidiousInstances = null
        cachedPipedInstances = null
        cachedSts = null
        consecutiveYtFailures.set(0)
        ytBypassCooldownUntilMs = 0L
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

    fun invalidate(videoId: String) {
        cache.remove(videoId)?.let { stream ->
            urlToUserAgent.remove(stream.url)
        }
        val lastSource = lastSourceByVideo[videoId]
        if (lastSource != null) {
            failedSourcesByVideo.getOrPut(videoId) { ConcurrentHashMap.newKeySet() }.add(lastSource)
        }
    }

    fun invalidateYouTubeSources(videoId: String) {
        cache.remove(videoId)?.let { stream ->
            urlToUserAgent.remove(stream.url)
        }
        val failed = failedSourcesByVideo.getOrPut(videoId) { ConcurrentHashMap.newKeySet() }
        profiles.forEach { failed.add("yt:${it.clientName}") }
        failed.add("newpipe")
        if (consecutiveYtFailures.incrementAndGet() >= 2) {
            ytBypassCooldownUntilMs = System.currentTimeMillis() + 5 * 60_000L
        }
    }

    fun resetFailedSources(videoId: String) {
        cache.remove(videoId)?.let { stream ->
            urlToUserAgent.remove(stream.url)
        }
        failedSourcesByVideo.remove(videoId)
        lastSourceByVideo.remove(videoId)
    }

    fun getUserAgentForUrl(url: String): String? {
        urlToUserAgent[url]?.let { return it }
        val host = extractHost(url) ?: return null
        return hostToUserAgent[host]
    }

    fun getUserAgentForHost(host: String): String? = hostToUserAgent[host.lowercase()]

    private fun extractHost(url: String): String? {
        return runCatching { URI(url).host?.lowercase()?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun recordResolved(videoId: String, sourceId: String, stream: ResolvedStream): Result<ResolvedStream> {
        cache[videoId] = stream
        urlToUserAgent[stream.url] = stream.userAgent
        extractHost(stream.url)?.let { host ->
            hostToUserAgent[host] = stream.userAgent
        }
        lastSourceByVideo[videoId] = sourceId
        if (sourceId.startsWith("yt:") || sourceId == "newpipe") {
            consecutiveYtFailures.set(0)
        }
        return Result.success(stream)
    }

    private fun ensureNewPipeInitialized() {
        NewPipeBootstrapper.ensureInitialized(httpClient)
    }

    private data class ClientProfile(
        val url: String,
        val origin: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val clientIdHeader: String,
        val sendAuthCookies: Boolean = false,
        val requiresSts: Boolean = false,
        val extraClientFields: JSONObject.() -> Unit,
        val extraBodyFields: JSONObject.() -> Unit = {}
    )

    private val profiles = listOf(
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "ANDROID_VR",
            clientVersion = "1.71.26",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.71.26 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientIdHeader = "28",
            extraClientFields = {
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("osName", "Android")
                put("osVersion", "12L")
                put("androidSdkVersion", 32)
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "IOS",
            clientVersion = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)",
            clientIdHeader = "5",
            sendAuthCookies = true,
            extraClientFields = {
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "18.3.1.22D72")
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "ANDROID",
            clientVersion = "20.10.38",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
            clientIdHeader = "3",
            sendAuthCookies = true,
            extraClientFields = {
                put("osName", "Android")
                put("osVersion", "14")
                put("androidSdkVersion", 34)
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            url = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://music.youtube.com",
            clientName = "ANDROID_MUSIC",
            clientVersion = "7.29.52",
            userAgent = "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14) gzip",
            clientIdHeader = "21",
            sendAuthCookies = true,
            extraClientFields = {
                put("osName", "Android")
                put("osVersion", "14")
                put("androidSdkVersion", 34)
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "TVHTML5",
            clientVersion = "7.20250212.16.00",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            clientIdHeader = "7",
            sendAuthCookies = true,
            requiresSts = true,
            extraClientFields = {
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://www.youtube.com",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20260115.01.00",
            userAgent = BROWSER_USER_AGENT,
            clientIdHeader = "56",
            sendAuthCookies = true,
            requiresSts = true,
            extraClientFields = {
                put("hl", "en")
                put("gl", "US")
            },
            extraBodyFields = {
                put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
            }
        ),
        ClientProfile(
            url = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://music.youtube.com",
            clientName = "WEB_REMIX",
            clientVersion = "1.20250212.01.00",
            userAgent = BROWSER_USER_AGENT,
            clientIdHeader = "67",
            sendAuthCookies = true,
            requiresSts = true,
            extraClientFields = {
                put("hl", "en")
                put("gl", "US")
            }
        )
    )

    private val fallbackInvidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be",
        "https://invidious.f5.si",
        "https://inv.tux.pizza",
        "https://yt.artemislena.eu"
    )

    private val fallbackPipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi.moomoo.me"
    )

    suspend fun resolveAudioStream(track: Track): Result<ResolvedStream> {
        registerTrackMetadata(track)
        return resolveAudioStream(track.videoId)
    }

    suspend fun resolveAudioStream(videoId: String): Result<ResolvedStream> {
        val now = System.currentTimeMillis()
        cache[videoId]?.let { cached ->
            if (cached.expiresAtMs > now + 60_000L) {
                return Result.success(cached)
            }
        }

        val createdDeferred = CompletableDeferred<Result<ResolvedStream>>()
        val existing = inFlight.putIfAbsent(videoId, createdDeferred)
        if (existing != null) {
            return existing.await()
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                resolveAudioStreamInternal(videoId)
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
            inFlight.remove(videoId, createdDeferred)
        }
    }

    private suspend fun resolveAudioStreamInternal(videoId: String): Result<ResolvedStream> {
        val failedSources = failedSourcesByVideo[videoId].orEmpty()
        val isSignedIn = !sessionCookies.isNullOrBlank()
        val inYtBypassCooldown = System.currentTimeMillis() < ytBypassCooldownUntilMs

        // Ordered profiles: when signed in, prioritize auth-capable mobile/TV clients before VR
        val orderedProfiles = if (isSignedIn) {
            profiles.sortedByDescending { it.sendAuthCookies }
        } else {
            profiles
        }

        val firstAvailable = orderedProfiles.firstOrNull { "yt:${it.clientName}" !in failedSources }
        val remainingProfiles = orderedProfiles.filter {
            it != firstAvailable && "yt:${it.clientName}" !in failedSources
        }
        val hasDirectYtOrNewpipe = firstAvailable != null ||
            remainingProfiles.isNotEmpty() ||
            ("newpipe" !in failedSources)

        // When YouTube IP/PoToken cooldown is active or direct YT sources were invalidated,
        // race Mirrors + Studio Catalog + Direct fallbacks simultaneously from t=0ms for instant start.
        if (hasDirectYtOrNewpipe && !inYtBypassCooldown) {
            val visitorData = cachedVisitorData ?: if (firstAvailable != null || remainingProfiles.isNotEmpty()) {
                withTimeoutOrNull(700L) { ensureVisitorData() }
            } else {
                null
            }

            // 1. Fast path: single best non-failed InnerTube profile
            if (firstAvailable != null) {
                val firstSourceId = "yt:${firstAvailable.clientName}"
                val firstResolved = withTimeoutOrNull(2_200L) {
                    val sts = if (firstAvailable.requiresSts) {
                        withTimeoutOrNull(1_200L) { ensureSignatureTimestamp(videoId) }
                    } else null
                    tryInnerTubeProfile(videoId, firstAvailable, visitorData, sts)
                }
                if (firstResolved != null) {
                    return recordResolved(videoId, firstSourceId, firstResolved)
                }
            }

            // 2. Unified high-speed parallel race: remaining YT clients + NewPipe + Mirrors + Studio Catalog
            val raceWin = raceAllFallbacks(
                videoId = videoId,
                remainingProfiles = remainingProfiles,
                visitorData = visitorData,
                failedSources = failedSources
            )
            if (raceWin != null) {
                return recordResolved(videoId, raceWin.first, raceWin.second)
            }
        } else {
            // Fast bypass path: race Mirrors + Studio Catalog concurrently with zero YouTube delay
            val fastBypassWin = raceBypassMirrorsAndCatalog(videoId, failedSources)
            if (fastBypassWin != null) {
                return recordResolved(videoId, fastBypassWin.first, fastBypassWin.second)
            }

            // Last-chance retry against InnerTube if cooldown expired or bypass sources missed
            if (hasDirectYtOrNewpipe) {
                val fallbackProfiles = listOfNotNull(firstAvailable) + remainingProfiles
                val visitorData = cachedVisitorData ?: withTimeoutOrNull(700L) { ensureVisitorData() }
                val ytWin = raceYouTubeFallbacks(videoId, fallbackProfiles, visitorData, failedSources)
                if (ytWin != null) {
                    return recordResolved(videoId, ytWin.first, ytWin.second)
                }
            }
        }

        return Result.failure(IllegalStateException("No se pudo obtener una fuente de audio compatible para este tema."))
    }

    private suspend fun raceBypassMirrorsAndCatalog(
        videoId: String,
        failedSources: Set<String>
    ): Pair<String, ResolvedStream>? = withTimeoutOrNull(9_000L) {
        coroutineScope {
            val mirrorDeferred = async(Dispatchers.IO) {
                raceMirrors(videoId, failedSources)
            }
            val catalogDeferred = async(Dispatchers.IO) {
                val hint = trackMetadata[videoId] ?: catalogFallback.fetchYouTubeOEmbedHint(videoId)
                if (hint != null && hint.title.isNotBlank()) {
                    catalogFallback.resolveStudioFallback(
                        hint = hint,
                        quality = preferredQuality,
                        failedSources = failedSources,
                        probePlayable = { stream -> probeStreamPlayable(stream) }
                    )
                } else {
                    null
                }
            }

            // Always give exact videoId mirrors (Invidious/Piped) priority before fuzzy catalog search
            val mirrorWin = withTimeoutOrNull(2_800L) { mirrorDeferred.await() }
            if (mirrorWin != null) {
                catalogDeferred.cancel()
                return@coroutineScope mirrorWin
            }

            val catalogWin = withTimeoutOrNull(5_800L) { catalogDeferred.await() }
            if (catalogWin != null) {
                mirrorDeferred.cancel()
                return@coroutineScope catalogWin
            }

            withTimeoutOrNull(1_500L) { mirrorDeferred.await() }
        }
    }

    private suspend fun raceAllFallbacks(
        videoId: String,
        remainingProfiles: List<ClientProfile>,
        visitorData: String?,
        failedSources: Set<String>
    ): Pair<String, ResolvedStream>? = withTimeoutOrNull(9_500L) {
        coroutineScope {
            val metadataHintDeferred = async(Dispatchers.IO) {
                trackMetadata[videoId] ?: catalogFallback.fetchYouTubeOEmbedHint(videoId)
            }
            // Pre-resolve studio catalog quietly in background ONLY as safety net if all exact videoId sources fail
            val catalogDeferred = async(Dispatchers.IO) {
                val hint = metadataHintDeferred.await() ?: return@async null
                if (hint.title.isBlank()) return@async null
                catalogFallback.resolveStudioFallback(
                    hint = hint,
                    quality = preferredQuality,
                    failedSources = failedSources,
                    probePlayable = { stream -> probeStreamPlayable(stream) }
                )
            }

            val exactVideoIdJobs = buildList {
                // Remaining no-STS InnerTube profiles (IOS, ANDROID, ANDROID_MUSIC)
                remainingProfiles.filter { !it.requiresSts }.forEach { profile ->
                    add(async(Dispatchers.IO) {
                        val stream = tryInnerTubeProfile(videoId, profile, visitorData, null)
                        if (stream != null) "yt:${profile.clientName}" to stream else null
                    })
                }
                // NewPipeExtractor JS cipher fallback (probed before accepting)
                if ("newpipe" !in failedSources) {
                    add(async(Dispatchers.IO) {
                        val candidates = runBlockingWithHardTimeout(4_500L) {
                            tryNewPipeExtractorCandidates(videoId)
                        }.orEmpty()
                        val playable = candidates.firstOrNull { probeStreamPlayable(it) }
                        if (playable != null) "newpipe" to playable else null
                    })
                }
                // Web/STS InnerTube profiles (TVHTML5, WEB_EMBEDDED_PLAYER, WEB_REMIX)
                val stsProfiles = remainingProfiles.filter { it.requiresSts }
                if (stsProfiles.isNotEmpty()) {
                    add(async(Dispatchers.IO) {
                        val sts = withTimeoutOrNull(1_600L) { ensureSignatureTimestamp(videoId) }
                        for (profile in stsProfiles) {
                            val resolved = tryInnerTubeProfile(videoId, profile, visitorData, sts)
                            if (resolved != null) return@async "yt:${profile.clientName}" to resolved
                        }
                        null
                    })
                }
                // Live Invidious / Piped mirror race (exact videoId)
                add(async(Dispatchers.IO) {
                    raceMirrors(videoId, failedSources)
                })
            }

            // Always prefer exact YouTube videoId audio before using text-matched catalog audio
            val exactWin = withTimeoutOrNull(4_800L) {
                awaitFirstNonNull(exactVideoIdJobs)
            }
            if (exactWin != null) {
                catalogDeferred.cancel()
                return@coroutineScope exactWin
            }

            withTimeoutOrNull(4_500L) { catalogDeferred.await() }
        }
    }

    private suspend fun <T> awaitFirstNonNull(
        jobs: List<kotlinx.coroutines.Deferred<T?>>
    ): T? {
        if (jobs.isEmpty()) return null
        val remaining = jobs.toMutableList()
        var winner: T? = null
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
        return winner
    }

    private suspend fun raceYouTubeFallbacks(
        videoId: String,
        remainingProfiles: List<ClientProfile>,
        visitorData: String?,
        failedSources: Set<String>
    ): Pair<String, ResolvedStream>? = withTimeoutOrNull(6_500L) {
        coroutineScope {
            val jobs = buildList {
                // Remaining no-STS InnerTube profiles (IOS, ANDROID, ANDROID_MUSIC)
                remainingProfiles.filter { !it.requiresSts }.forEach { profile ->
                    add(async(Dispatchers.IO) {
                        val stream = tryInnerTubeProfile(videoId, profile, visitorData, null)
                        if (stream != null) "yt:${profile.clientName}" to stream else null
                    })
                }
                // NewPipeExtractor JS cipher fallback (probed before accepting)
                if ("newpipe" !in failedSources) {
                    add(async(Dispatchers.IO) {
                        val candidates = runBlockingWithHardTimeout(4_500L) {
                            tryNewPipeExtractorCandidates(videoId)
                        }.orEmpty()
                        val playable = candidates.firstOrNull { probeStreamPlayable(it) }
                        if (playable != null) "newpipe" to playable else null
                    })
                }
                // Web/STS InnerTube profiles (TVHTML5, WEB_EMBEDDED_PLAYER, WEB_REMIX)
                val stsProfiles = remainingProfiles.filter { it.requiresSts }
                if (stsProfiles.isNotEmpty()) {
                    add(async(Dispatchers.IO) {
                        val sts = withTimeoutOrNull(2_000L) { ensureSignatureTimestamp(videoId) }
                        for (profile in stsProfiles) {
                            val resolved = tryInnerTubeProfile(videoId, profile, visitorData, sts)
                            if (resolved != null) return@async "yt:${profile.clientName}" to resolved
                        }
                        null
                    })
                }
                // Live Invidious / Piped mirror race
                add(async(Dispatchers.IO) {
                    raceMirrors(videoId, failedSources)
                })
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
        failedSources: Set<String>
    ): Pair<String, ResolvedStream>? = withTimeoutOrNull(4_000L) {
        coroutineScope {
            val invListDeferred = async(Dispatchers.IO) { getHealthyInvidiousInstances() }
            val pipedListDeferred = async(Dispatchers.IO) { getHealthyPipedInstances() }
            val invInstances = invListDeferred.await()
            val pipedInstances = pipedListDeferred.await()

            val jobs = buildList {
                invInstances.forEach { instance ->
                    val id = "inv:$instance"
                    if (id !in failedSources) {
                        add(async(Dispatchers.IO) {
                            val stream = tryInvidiousInstance(instance, videoId) ?: return@async null
                            if (probeStreamPlayable(stream)) id to stream else null
                        })
                    }
                }
                pipedInstances.forEach { instance ->
                    val id = "piped:$instance"
                    if (id !in failedSources) {
                        add(async(Dispatchers.IO) {
                            val stream = tryPipedInstance(instance, videoId) ?: return@async null
                            if (probeStreamPlayable(stream)) id to stream else null
                        })
                    }
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

    private suspend fun probeStreamPlayable(stream: ResolvedStream): Boolean {
        if (!isValidHttpUrl(stream.url)) return false
        return withTimeoutOrNull(4_000L) {
            runCatching {
                val isHls = stream.mimeType.contains("mpegURL", ignoreCase = true) ||
                    stream.url.contains(".m3u8", ignoreCase = true)
                val reqBuilder = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", stream.userAgent)
                if (!isHls) {
                    reqBuilder.header("Range", "bytes=0-65535")
                }
                val firstAttempt = probeClient.executeCancellable(reqBuilder.get().build()).use { resp ->
                    if (!isHls && resp.code == 416) {
                        null
                    } else if (resp.code !in 200..299 && resp.code != 206) {
                        Log.w("StreamResolver", "Stream probe HTTP ${resp.code} for ${extractHost(stream.url)}")
                        false
                    } else if (isHls) {
                        val preview = resp.body?.string()?.take(4096).orEmpty()
                        val hasM3u = preview.contains("#EXTM3U")
                        val hasDrm = preview.contains("SAMPLE-AES", ignoreCase = true) ||
                            preview.contains("skd://", ignoreCase = true) ||
                            preview.contains("com.apple.streamingkeydelivery", ignoreCase = true) ||
                            preview.contains("FAIRPLAY", ignoreCase = true) ||
                            preview.contains("WIDEVINE", ignoreCase = true)
                        if (hasDrm) {
                            Log.w("StreamResolver", "Rejected DRM-encrypted HLS stream from ${extractHost(stream.url)}")
                        }
                        hasM3u && !hasDrm
                    } else {
                        val contentType = resp.header("Content-Type").orEmpty()
                        if (contentType.contains("text/html", ignoreCase = true)) {
                            Log.w("StreamResolver", "Rejected HTML error response from ${extractHost(stream.url)}")
                            false
                        } else {
                            val buf = ByteArray(64)
                            val bytesRead = resp.body?.byteStream()?.read(buf) ?: -1
                            if (bytesRead <= 0) {
                                Log.w("StreamResolver", "Rejected empty audio body from ${extractHost(stream.url)}")
                                false
                            } else {
                                true
                            }
                        }
                    }
                }
                if (firstAttempt != null) return@runCatching firstAttempt

                // Retry without Range header if server rejected byte-range 416
                val plainReq = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", stream.userAgent)
                    .get()
                    .build()
                probeClient.executeCancellable(plainReq).use { resp ->
                    val contentType = resp.header("Content-Type").orEmpty()
                    if (resp.code !in 200..299 || contentType.contains("text/html", ignoreCase = true)) {
                        false
                    } else {
                        val buf = ByteArray(64)
                        (resp.body?.byteStream()?.read(buf) ?: -1) > 0
                    }
                }
            }.getOrDefault(false)
        } ?: false
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

    private fun tryNewPipeExtractorCandidates(videoId: String): List<ResolvedStream> {
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
                    ResolvedStream(
                        url = stream.content,
                        mimeType = mime,
                        bitrate = kbps * 1000,
                        userAgent = BROWSER_USER_AGENT,
                        expiresAtMs = expiresAt,
                        codecLabel = "$codecName $kbps kbps"
                    )
                }

            selectStreamByQuality(progressiveAudioCandidates)?.let { results.add(it) }

            val hlsUrl = info.hlsUrl?.takeIf { isValidHttpUrl(it) }
            if (!hlsUrl.isNullOrBlank()) {
                results.add(
                    ResolvedStream(
                        url = hlsUrl,
                        mimeType = "application/x-mpegURL",
                        bitrate = 128_000,
                        userAgent = BROWSER_USER_AGENT,
                        expiresAtMs = expiresAt,
                        codecLabel = "HLS Audio"
                    )
                )
            }

            val fallbackVideo = info.videoStreams
                .filter {
                    it.isUrl &&
                        isValidHttpUrl(it.content) &&
                        it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                }
                .minByOrNull { it.resolution?.filter(Char::isDigit)?.toIntOrNull() ?: 360 }

            if (fallbackVideo != null) {
                results.add(
                    ResolvedStream(
                        url = fallbackVideo.content,
                        mimeType = fallbackVideo.format?.mimeType ?: "video/mp4",
                        bitrate = 128_000,
                        userAgent = BROWSER_USER_AGENT,
                        expiresAtMs = expiresAt,
                        codecLabel = "AAC Stream"
                    )
                )
            }
            results
        }.onFailure { err ->
            Log.w("StreamResolver", "NewPipeExtractor failed for $videoId: ${err.message}")
        }.getOrDefault(emptyList())
    }

    private suspend fun ensureVisitorData(): String? {
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

    private suspend fun ensureSignatureTimestamp(videoId: String): Int? {
        cachedSts?.let { return it }
        val fetched = runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/embed/$videoId")
                .header("User-Agent", BROWSER_USER_AGENT)
                .get()
                .build()
            fastClient.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                Regex("\"STS\"\\s*:\\s*(\\d{4,6})|\"sts\"\\s*:\\s*(\\d{4,6})")
                    .find(raw)
                    ?.groupValues
                    ?.drop(1)
                    ?.firstOrNull { it.isNotBlank() }
                    ?.toIntOrNull()
            }
        }.getOrNull()
        if (fetched != null) {
            cachedSts = fetched
        }
        return fetched
    }

    private suspend fun tryInnerTubeProfile(
        videoId: String,
        profile: ClientProfile,
        visitorData: String?,
        sts: Int?
    ): ResolvedStream? {
        return runCatching {
            val clientObj = JSONObject()
                .put("clientName", profile.clientName)
                .put("clientVersion", profile.clientVersion)
            if (!visitorData.isNullOrBlank()) {
                clientObj.put("visitorData", visitorData)
            }
            profile.extraClientFields(clientObj)

            val payload = JSONObject()
                .put("context", JSONObject().put("client", clientObj))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
            if (sts != null) {
                payload.put(
                    "playbackContext",
                    JSONObject().put(
                        "contentPlaybackContext",
                        JSONObject()
                            .put("signatureTimestamp", sts)
                            .put("html5Preference", "HTML5_PREF_WANTS")
                    )
                )
            }
            profile.extraBodyFields(payload)

            val builder = Request.Builder()
                .url(profile.url)
                .header("User-Agent", profile.userAgent)
                .header("Origin", profile.origin)
                .header("Referer", "${profile.origin}/")
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", profile.clientIdHeader)
                .header("X-YouTube-Client-Version", profile.clientVersion)

            if (!visitorData.isNullOrBlank()) {
                builder.header("X-Goog-Visitor-Id", visitorData)
            }

            if (profile.sendAuthCookies) {
                val cookies = sessionCookies
                if (!cookies.isNullOrBlank()) {
                    builder.header("Cookie", cookies)
                    buildMultiSapisidHeader(cookies, profile.origin)?.let { auth ->
                        builder.header("Authorization", auth)
                        builder.header("X-Origin", profile.origin)
                        builder.header("X-Goog-AuthUser", "0")
                    }
                }
            }

            val req = builder
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            fastClient.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("StreamResolver", "${profile.clientName} HTTP ${resp.code}")
                    return@runCatching null
                }
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@runCatching null
                val json = JSONObject(raw)
                val playability = json.optJSONObject("playabilityStatus")
                val status = playability?.optString("status").orEmpty()
                if (status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                    Log.w("StreamResolver", "${profile.clientName} status=$status reason=${playability?.optString("reason")}")
                    return@runCatching null
                }

                val streamingData = json.optJSONObject("streamingData") ?: return@runCatching null
                val expiresInSec = streamingData.optString("expiresInSeconds").toLongOrNull() ?: 3600L
                val expiresAt = System.currentTimeMillis() + expiresInSec * 1000L

                val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                val adaptiveAudio = pickBestAudioFormat(adaptive, profile.userAgent, expiresAt)
                if (adaptiveAudio != null && probeStreamPlayable(adaptiveAudio)) {
                    return@runCatching adaptiveAudio
                }

                val progressive = streamingData.optJSONArray("formats") ?: JSONArray()
                val progressiveAudio = pickBestProgressiveFormat(progressive, profile.userAgent, expiresAt)
                if (progressiveAudio != null && probeStreamPlayable(progressiveAudio)) {
                    return@runCatching progressiveAudio
                }

                val hlsUrl = streamingData.optValidUrl("hlsManifestUrl")
                if (hlsUrl != null) {
                    val hlsStream = ResolvedStream(
                        url = hlsUrl,
                        mimeType = "application/x-mpegURL",
                        bitrate = 128_000,
                        userAgent = profile.userAgent,
                        expiresAtMs = expiresAt,
                        codecLabel = "HLS Audio"
                    )
                    if (probeStreamPlayable(hlsStream)) {
                        return@runCatching hlsStream
                    }
                }

                Log.w("StreamResolver", "${profile.clientName} returned no playable (non-403) direct URLs")
                null
            }
        }.getOrNull()
    }

    private fun pickBestAudioFormat(
        formats: JSONArray,
        userAgent: String,
        expiresAt: Long
    ): ResolvedStream? {
        val candidates = mutableListOf<ResolvedStream>()
        for (i in 0 until formats.length()) {
            val fmt = formats.optJSONObject(i) ?: continue
            val mime = fmt.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            val directUrl = fmt.optValidUrl("url") ?: continue
            val bitrate = fmt.optInt("bitrate", 0)
            val isOpus = mime.contains("opus", ignoreCase = true)
            val codec = if (isOpus) "OPUS ${bitrate / 1000} kbps" else "AAC ${bitrate / 1000} kbps"
            candidates.add(
                ResolvedStream(
                    url = directUrl,
                    mimeType = mime,
                    bitrate = bitrate,
                    userAgent = userAgent,
                    expiresAtMs = expiresAt,
                    codecLabel = codec
                )
            )
        }
        return selectStreamByQuality(candidates)
    }

    private fun pickBestProgressiveFormat(
        formats: JSONArray,
        userAgent: String,
        expiresAt: Long
    ): ResolvedStream? {
        var best: ResolvedStream? = null
        for (i in 0 until formats.length()) {
            val fmt = formats.optJSONObject(i) ?: continue
            val directUrl = fmt.optValidUrl("url") ?: continue
            val mime = fmt.optString("mimeType", "video/mp4")
            val bitrate = fmt.optInt("bitrate", 128_000)
            if (best == null || bitrate > best.bitrate) {
                best = ResolvedStream(
                    url = directUrl,
                    mimeType = mime,
                    bitrate = bitrate,
                    userAgent = userAgent,
                    expiresAtMs = expiresAt,
                    codecLabel = "AAC Stream"
                )
            }
        }
        return best
    }

    private suspend fun tryInvidiousInstance(baseUrl: String, videoId: String): ResolvedStream? {
        return runCatching {
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
                parseInvidiousJson(baseUrl, JSONObject(raw))
            }
        }.getOrNull()
    }

    internal fun parseInvidiousJson(baseUrl: String, json: JSONObject): ResolvedStream? {
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
            candidates.add(
                ResolvedStream(
                    url = fullUrl,
                    mimeType = type,
                    bitrate = bitrate,
                    userAgent = BROWSER_USER_AGENT,
                    expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                    codecLabel = "Audio ${bitrate / 1000} kbps"
                )
            )
        }
        selectStreamByQuality(candidates)?.let { return it }

        val hlsUrl = json.optValidUrl("hlsUrl", allowRelative = true)
        if (hlsUrl != null) {
            val fullHls = if (hlsUrl.startsWith("/")) "${baseUrl.trimEnd('/')}$hlsUrl" else hlsUrl
            if (isValidHttpUrl(fullHls)) {
                return ResolvedStream(
                    url = fullHls,
                    mimeType = "application/x-mpegURL",
                    bitrate = 128_000,
                    userAgent = BROWSER_USER_AGENT,
                    expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                    codecLabel = "HLS Audio"
                )
            }
        }

        val formatStreams = json.optJSONArray("formatStreams") ?: return null
        for (i in 0 until formatStreams.length()) {
            val fmt = formatStreams.optJSONObject(i) ?: continue
            val rawUrl = fmt.optValidUrl("url", allowRelative = true) ?: continue
            val fullUrl = if (rawUrl.startsWith("/")) "${baseUrl.trimEnd('/')}$rawUrl" else rawUrl
            if (!isValidHttpUrl(fullUrl)) continue
            return ResolvedStream(
                url = fullUrl,
                mimeType = fmt.optString("type", "video/mp4"),
                bitrate = 128_000,
                userAgent = BROWSER_USER_AGENT,
                expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                codecLabel = "AAC Stream"
            )
        }
        return null
    }

    private suspend fun tryPipedInstance(baseUrl: String, videoId: String): ResolvedStream? {
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
                parsePipedJson(JSONObject(raw))
            }
        }.getOrNull()
    }

    internal fun parsePipedJson(json: JSONObject): ResolvedStream? {
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

    private fun extractCookie(cookies: String, key: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildMultiSapisidHeader(cookies: String, origin: String): String? {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val sapisid = extractCookie(cookies, "SAPISID")
        val p1 = extractCookie(cookies, "__Secure-1PAPISID")
        val p3 = extractCookie(cookies, "__Secure-3PAPISID")
        val parts = mutableListOf<String>()
        val mainKey = p3 ?: sapisid
        if (mainKey != null) {
            parts.add(computeSchemeHash("SAPISIDHASH", mainKey, origin, timestamp))
        }
        if (p1 != null) {
            parts.add(computeSchemeHash("SAPISID1PHASH", p1, origin, timestamp))
        }
        if (p3 != null) {
            parts.add(computeSchemeHash("SAPISID3PHASH", p3, origin, timestamp))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun computeSchemeHash(scheme: String, key: String, origin: String, timestamp: String): String {
        val input = "$timestamp $key $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$scheme ${timestamp}_$hex"
    }

    companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    }
}
