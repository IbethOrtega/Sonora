package com.example.data.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.model.AudioQuality
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.metrolist.innertubex.extraction.AudioQuality as ItxAudioQuality

data class InnerTubeXStreamResult(
    val url: String,
    val bitrate: Int,
    val mimeType: String,
    val clientName: String,
    val profileId: String,
    val headers: Map<String, String>,
    val expiresAtMs: Long,
    val contentLengthBytes: Long? = null
)

/**
 * Per-(videoId, profileId) temporary client exclusion tracker with configurable TTL (default 10 minutes).
 */
internal class ClientExclusionTracker(
    private val ttlMs: Long = EXCLUSION_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class Key(val videoId: String, val profileId: String)

    private val entries = ConcurrentHashMap<Key, Long>()

    fun exclude(videoId: String, profileId: String) {
        if (videoId.isBlank() || profileId.isBlank()) return
        entries[Key(videoId, profileId)] = clockMs() + ttlMs
    }

    fun getExcludedProfiles(videoId: String): Set<String> {
        if (videoId.isBlank()) return emptySet()
        val now = clockMs()
        val active = mutableSetOf<String>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= now) {
                iterator.remove()
            } else if (entry.key.videoId == videoId) {
                active.add(entry.key.profileId)
            }
        }
        return active
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        const val EXCLUSION_TTL_MS = 10 * 60_000L
    }
}

private data class GeneratedUrlMeta(
    val videoId: String,
    val profileId: String,
    val headers: Map<String, String>
)

object InnerTubeXResolver {
    private const val TAG = "Sonora"
    private const val PREFS_NAME = "innertubex_player_config"
    private const val PLAYERS_CACHE_DIR = "innertubex_players"
    private const val MAX_CACHED_PLAYERS = 3
    private const val MAX_TRACKED_URLS = 64
    private const val DEFAULT_PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    private val playerCacheMutex = Mutex()
    private val exclusions = ClientExclusionTracker()
    private val cacheConfigured = AtomicBoolean(false)

    private val generatedUrls: MutableMap<String, GeneratedUrlMeta> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, GeneratedUrlMeta>(MAX_TRACKED_URLS + 1, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, GeneratedUrlMeta>?
                ): Boolean = size > MAX_TRACKED_URLS
            }
        )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var baseOkHttpClient: OkHttpClient? = null

    @Volatile
    private var tokenProviderRef: TokenProvider? = null

    @Volatile
    private var cookieProvider: (() -> String?)? = null

    @Volatile
    private var visitorDataProvider: (suspend () -> String?)? = null

    @Volatile
    private var prewarmJob: Job? = null

    @Volatile
    private var httpClient: HttpClient? = null

    @Volatile
    private var innerTubeInstance: InnerTube? = null

    @Volatile
    private var remoteStoreInstance: RemotePlayerConfigStore? = null

    @Volatile
    private var cipherServiceInstance: YouTubeCipherService? = null

    @Volatile
    private var extractorInstance: InnerTubeExtractor? = null

    private val logger = InnerTubeLogger { event ->
        val mediaId = event.mediaId?.takeIf { it.isNotBlank() }
        val mediaPart = mediaId?.let { " [$it]" }.orEmpty()
        val sanitizedDetails = if (event.details.isNotEmpty()) {
            val formatted = event.details.entries.joinToString(", ") { (k, v) ->
                "$k=${PlaybackDiagnostics.sanitize(v)}"
            }
            " {$formatted}"
        } else {
            ""
        }
        val cleanMessage = PlaybackDiagnostics.sanitize(event.message)
        val msg = "ITX [${event.tag}]$mediaPart: $cleanMessage$sanitizedDetails"
        if (mediaId != null && (
                event.level == InnerTubeLogLevel.WARN ||
                    event.level == InnerTubeLogLevel.ERROR ||
                    cleanMessage.contains("playability", ignoreCase = true) ||
                    cleanMessage.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                    cleanMessage.contains("UNPLAYABLE", ignoreCase = true) ||
                    cleanMessage.contains("status", ignoreCase = true) ||
                    cleanMessage.contains("rejected", ignoreCase = true)
                )
        ) {
            PlaybackDiagnostics.recordClientResult(mediaId, "ITX [${event.tag}]: $cleanMessage$sanitizedDetails")
        }
        runCatching {
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Log.d(TAG, msg)
                InnerTubeLogLevel.INFO -> Log.i(TAG, msg)
                InnerTubeLogLevel.WARN -> Log.w(TAG, msg)
                InnerTubeLogLevel.ERROR -> Log.e(TAG, msg)
            }
        }
    }

    /**
     * Initializes InnerTubeX dependencies and schedules a non-blocking `prewarm()` 2s after startup.
     */
    fun init(
        context: Context,
        okHttpClient: OkHttpClient,
        tokenProvider: TokenProvider? = null,
        cookieProvider: (() -> String?)? = null,
        visitorDataProvider: (suspend () -> String?)? = null
    ) {
        appContext = context.applicationContext
        baseOkHttpClient = okHttpClient
        if (tokenProvider != null) {
            tokenProviderRef = tokenProvider
        }
        if (cookieProvider != null) {
            this.cookieProvider = cookieProvider
        }
        if (visitorDataProvider != null) {
            this.visitorDataProvider = visitorDataProvider
        }

        if (prewarmJob != null) return
        prewarmJob = scope.launch {
            delay(2_000L)
            runCatching {
                val extractor = ensureComponentsInitialized()
                syncSessionStateToInnerTube()
                extractor.prewarm()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w(TAG, "ITX startup prewarm skipped: ${err.message}")
            }
        }
    }

    fun updateProviders(
        cookieProvider: (() -> String?)? = null,
        visitorDataProvider: (suspend () -> String?)? = null,
        tokenProvider: TokenProvider? = null
    ) {
        if (cookieProvider != null) this.cookieProvider = cookieProvider
        if (visitorDataProvider != null) this.visitorDataProvider = visitorDataProvider
        if (tokenProvider != null && tokenProviderRef !== tokenProvider) {
            tokenProviderRef = tokenProvider
            // Reset extractor if tokenProvider arrived late
            extractorInstance = null
        }
    }

    suspend fun extract(
        videoId: String,
        quality: AudioQuality,
        skipClients: Set<String> = emptySet()
    ): InnerTubeXStreamResult? {
        if (videoId.isBlank()) return null
        val extractor = runCatching { ensureComponentsInitialized() }.getOrElse { err ->
            Log.w(TAG, "ITX init unavailable for $videoId: ${err.message}")
            return null
        }

        syncSessionStateToInnerTube()

        val excludedClients = exclusions.getExcludedProfiles(videoId) + skipClients
        val hints = ContentHints().withStreamCapabilities(
            allowHls = false,
            allowSabr = false,
            allowBoundedRange = true
        )

        val extracted = try {
            extractor.extract(
                videoId = videoId,
                hints = hints,
                excludedClients = excludedClients,
                audioQuality = mapAudioQuality(quality),
                clientPlaybackNonce = generateClientPlaybackNonce()
            )
        } catch (e: StreamResolveException) {
            val attempts = e.diagnostics?.attempts.orEmpty()
            if (attempts.isNotEmpty()) {
                attempts.forEach { attempt ->
                    val line = "ITX ${attempt.clientName} (${attempt.profileId}): ${attempt.outcome}"
                    PlaybackDiagnostics.recordClientResult(videoId, line)
                    runCatching { Log.w(TAG, "[$videoId] ${PlaybackDiagnostics.sanitize(line)}") }
                }
            } else {
                PlaybackDiagnostics.recordClientResult(
                    videoId,
                    "ITX fallo: ${e.reason} (${e.message ?: "sin detalle"})"
                )
            }
            runCatching {
                Log.w(
                    TAG,
                    "ITX StreamResolveException [$videoId] reason=${e.reason}: ${PlaybackDiagnostics.sanitize(e.message.orEmpty())}"
                )
            }
            return null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            PlaybackDiagnostics.recordClientResult(
                videoId,
                "ITX error: ${t.javaClass.simpleName} (${t.message ?: "sin detalle"})"
            )
            runCatching {
                Log.w(TAG, "ITX unexpected failure [$videoId]: ${PlaybackDiagnostics.sanitize(t.message.orEmpty())}")
            }
            return null
        }

        if (extracted == null || extracted.sabrBootstrap != null || extracted.audioUrl.isBlank()) {
            val reason = when {
                extracted == null -> "respuesta nula"
                extracted.sabrBootstrap != null -> "solo SABR"
                else -> "URL vacía"
            }
            PlaybackDiagnostics.recordClientResult(videoId, "ITX: sin stream directo ($reason)")
            runCatching { Log.w(TAG, "ITX returned null, SABR bootstrap, or empty URL for $videoId ($reason)") }
            return null
        }

        PlaybackDiagnostics.recordClientResult(
            videoId,
            "ITX ${extracted.clientName} (${extracted.profileId}): playability=OK (${extracted.mimeType.orEmpty().ifBlank { "audio" }})"
        )

        val mimeWithCodecs = buildMimeWithCodecs(extracted.mimeType.orEmpty(), extracted.codecs)
        val expiresAtMs = extracted.expiresAt?.let { instant ->
            runCatching { instant.toEpochMilliseconds() }.getOrNull()
        } ?: (System.currentTimeMillis() + 5 * 3600_000L)

        val cleanHeaders = extracted.headers.filterValues { it.isNotBlank() }
        generatedUrls[extracted.audioUrl] = GeneratedUrlMeta(
            videoId = videoId,
            profileId = extracted.profileId,
            headers = cleanHeaders
        )

        return InnerTubeXStreamResult(
            url = extracted.audioUrl,
            bitrate = extracted.bitrate ?: 128_000,
            mimeType = mimeWithCodecs,
            clientName = extracted.clientName,
            profileId = extracted.profileId,
            headers = cleanHeaders,
            expiresAtMs = expiresAtMs,
            contentLengthBytes = extracted.contentLengthBytes
        )
    }

    fun headersFor(url: String): Map<String, String>? {
        if (url.isBlank()) return null
        return generatedUrls[url]?.headers?.takeIf { it.isNotEmpty() }
    }

    fun onRefused(url: String) {
        val meta = generatedUrls.remove(url)
        if (meta != null) {
            exclusions.exclude(meta.videoId, meta.profileId)
            Log.w(TAG, "ITX client refused url; excluding profile=${meta.profileId} for video=${meta.videoId} (10m)")
        }
        val cipher = cipherServiceInstance ?: return
        scope.launch {
            runCatching {
                cipher.refreshAfterStreamRejection()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w(TAG, "ITX refreshAfterStreamRejection failed: ${err.message}")
            }
        }
    }

    fun onSessionChanged() {
        exclusions.clear()
        generatedUrls.clear()
        prewarmJob?.cancel()
        prewarmJob = scope.launch {
            runCatching {
                (tokenProviderRef as? PoTokenWebViewManager)?.clearCache()
                val extractor = ensureComponentsInitialized()
                syncSessionStateToInnerTube()
                extractor.prewarm()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w(TAG, "ITX onSessionChanged prewarm failed: ${err.message}")
            }
        }
    }

    private suspend fun ensureComponentsInitialized(): InnerTubeExtractor {
        extractorInstance?.let { existing ->
            if (cacheConfigured.get()) return existing
        }
        return initMutex.withLock {
            val ctx = appContext ?: error("InnerTubeXResolver requires init(context, okHttpClient) first")
            val okHttp = baseOkHttpClient ?: OkHttpClient()

            val ktor = httpClient ?: HttpClient(OkHttp) {
                engine {
                    preconfigured = okHttp
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                            encodeDefaults = true
                        }
                    )
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 20_000
                }
                expectSuccess = false
            }.also { httpClient = it }

            val it = innerTubeInstance ?: InnerTube(
                httpClient = ktor,
                logger = logger
            ).also { innerTubeInstance = it }

            val remoteStore = remoteStoreInstance ?: RemotePlayerConfigStore(
                httpClient = ktor,
                repository = SharedPrefsPlayerConfigRepository(
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ),
                logger = logger
            ).also { remoteStoreInstance = it }

            val cipher = cipherServiceInstance ?: YouTubeCipherService(
                ktor,
                remoteStore,
                logger
            ).also { cipherServiceInstance = it }

            if (cacheConfigured.compareAndSet(false, true)) {
                val cacheDir = File(ctx.filesDir, PLAYERS_CACHE_DIR)
                cipher.setPreprocessedPlayerCache(
                    { key -> readPreprocessedPlayer(cacheDir, key) },
                    { key, code -> writePreprocessedPlayer(cacheDir, key, code) }
                )
            }

            extractorInstance ?: InnerTubeExtractor(
                configParser = YtConfigParserImpl(ktor, it, remoteStore, logger),
                cipherService = cipher,
                innerTube = it,
                tokenProvider = tokenProviderRef ?: PoTokenWebViewManager(ctx).also { tokenProviderRef = it },
                logger = logger
            ).also { extractorInstance = it }
        }
    }

    private suspend fun syncSessionStateToInnerTube() {
        val it = innerTubeInstance ?: return
        val currentCookie = runCatching { cookieProvider?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() }
        val currentVisitorData = runCatching { visitorDataProvider?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() }
        it.cookie = currentCookie
        it.visitorData = currentVisitorData
        it.locale = resolveDeviceLocale()
    }

    private fun resolveDeviceLocale(): YouTubeLocale {
        val sys = Locale.getDefault()
        val gl = sys.country.trim().uppercase(Locale.US).takeIf { it.length == 2 } ?: "US"
        val hl = sys.language.trim().lowercase(Locale.US).takeIf { it.length in 2..3 } ?: "es"
        return YouTubeLocale(gl = gl, hl = hl)
    }

    private fun mapAudioQuality(quality: AudioQuality): ItxAudioQuality {
        return when (quality) {
            AudioQuality.AUTO -> ItxAudioQuality.AUTO
            AudioQuality.MAX -> ItxAudioQuality.HIGH
            AudioQuality.HIGH -> ItxAudioQuality.AUTO
            AudioQuality.DATA_SAVER -> ItxAudioQuality.LOW
        }
    }

    private fun buildMimeWithCodecs(mimeType: String, codecs: String?): String {
        val cleanMime = mimeType.ifBlank { "audio/mp4" }
        val cleanCodecs = codecs?.trim()?.takeIf { it.isNotBlank() }
        return if (cleanCodecs != null && !cleanMime.contains("codecs", ignoreCase = true)) {
            "$cleanMime; codecs=\"$cleanCodecs\""
        } else {
            cleanMime
        }
    }

    private suspend fun readPreprocessedPlayer(cacheDir: File, key: String): String? =
        playerCacheMutex.withLock {
            if (!cacheDir.exists()) return@withLock null
            val target = File(cacheDir, safePlayerFileName(key))
            if (!target.exists() || !target.isFile) return@withLock null
            runCatching {
                target.setLastModified(System.currentTimeMillis())
                target.readText(Charsets.UTF_8)
            }.getOrNull()
        }

    private suspend fun writePreprocessedPlayer(cacheDir: File, key: String, code: String?) {
        if (code.isNullOrBlank()) return
        playerCacheMutex.withLock {
            runCatching {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val fileName = safePlayerFileName(key)
                val target = File(cacheDir, fileName)
                val tmp = File(cacheDir, "$fileName.tmp")
                tmp.writeText(code, Charsets.UTF_8)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                val cachedFiles = cacheDir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                    ?.sortedByDescending { it.lastModified() }
                    .orEmpty()
                cachedFiles.drop(MAX_CACHED_PLAYERS).forEach { stale ->
                    runCatching { stale.delete() }
                }
            }
        }
    }

    private fun safePlayerFileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return "player_$digest.js"
    }

    private class SharedPrefsPlayerConfigRepository(
        private val prefs: SharedPreferences
    ) : PlayerConfigRepository {
        override val enabled: Boolean
            get() = prefs.getBoolean(KEY_ENABLED, true)

        override val sourceUrl: String
            get() = prefs.getString(KEY_SOURCE_URL, DEFAULT_PLAYER_CONFIG_URL)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_PLAYER_CONFIG_URL

        override val defaultSourceUrl: String = DEFAULT_PLAYER_CONFIG_URL

        override var cachedJson: String
            get() = prefs.getString(KEY_CACHED_JSON, "") ?: ""
            set(value) {
                prefs.edit().putString(KEY_CACHED_JSON, value).apply()
            }

        override var cachedAtMs: Long
            get() = prefs.getLong(KEY_CACHED_AT_MS, 0L)
            set(value) {
                prefs.edit().putLong(KEY_CACHED_AT_MS, value).apply()
            }

        override var cachedSourceUrl: String
            get() = prefs.getString(KEY_CACHED_SOURCE_URL, "") ?: ""
            set(value) {
                prefs.edit().putString(KEY_CACHED_SOURCE_URL, value).apply()
            }

        override var cachedEtag: String
            get() = prefs.getString(KEY_CACHED_ETAG, "") ?: ""
            set(value) {
                prefs.edit().putString(KEY_CACHED_ETAG, value).apply()
            }

        private companion object {
            const val KEY_ENABLED = "enabled"
            const val KEY_SOURCE_URL = "source_url"
            const val KEY_CACHED_JSON = "cached_json"
            const val KEY_CACHED_AT_MS = "cached_at_ms"
            const val KEY_CACHED_SOURCE_URL = "cached_source_url"
            const val KEY_CACHED_ETAG = "cached_etag"
        }
    }
}
