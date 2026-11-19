package com.example.data.network

import com.example.model.SonoraSettings
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real integrations manager for BitChord-equivalent external services:
 * - ListenBrainz (token validation + playing_now & single scrobbles)
 * - Last.fm AudioScrobbler 2.0 (MD5-signed mobile session auth + nowPlaying + scrobble)
 * - Discord Rich Presence (Discord Gateway v10 WebSocket Listening activity)
 */
class IntegrationsManager(
    private val httpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var discordWebSocket: WebSocket? = null
    private var discordHeartbeatJob: Job? = null
    private var discordConnectedToken: String? = null
    private var discordReady: Boolean = false
    private var pendingPresenceTrack: Track? = null
    private var pendingPresencePlaying: Boolean = false

    // --- ListenBrainz ---
    suspend fun validateListenBrainzToken(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = token.trim()
            require(trimmed.isNotBlank()) { "Ingresa un token de ListenBrainz válido." }
            val req = Request.Builder()
                .url("https://api.listenbrainz.org/1/validate-token")
                .header("Authorization", "Token $trimmed")
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.executeCancellable(req).use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Token rechazado por ListenBrainz (HTTP ${resp.code}).")
                }
                val json = JSONObject(raw)
                if (!json.optBoolean("valid", false)) {
                    throw IllegalStateException("El token de ListenBrainz no es válido.")
                }
                json.optString("user_name").ifBlank { "Usuario ListenBrainz" }
            }
        }
    }

    suspend fun submitListenBrainz(
        token: String,
        track: Track,
        isNowPlaying: Boolean,
        listenedAtSec: Long = System.currentTimeMillis() / 1000L
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (token.isBlank() || track.title.isBlank()) return@runCatching
            val payloadJson = buildListenBrainzPayload(
                track = track,
                isNowPlaying = isNowPlaying,
                listenedAtSec = listenedAtSec
            )
            val req = Request.Builder()
                .url("https://api.listenbrainz.org/1/submit-listens")
                .header("Authorization", "Token ${token.trim()}")
                .header("Content-Type", "application/json")
                .post(payloadJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("ListenBrainz HTTP ${resp.code}")
                }
            }
        }
    }

    // --- Last.fm ---
    suspend fun authenticateLastFmMobileSession(
        username: String,
        password: String,
        apiKey: String,
        apiSecret: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val u = username.trim()
            val p = password
            val k = apiKey.trim()
            val s = apiSecret.trim()
            require(u.isNotBlank() && p.isNotBlank()) { "Ingresa usuario y contraseña de Last.fm." }
            require(k.isNotBlank() && s.isNotBlank()) { "Ingresa tu API Key y API Secret de Last.fm." }

            val params = linkedMapOf(
                "method" to "auth.getMobileSession",
                "username" to u,
                "password" to p,
                "api_key" to k
            )
            val sig = signLastFmParams(params, s)
            val bodyBuilder = FormBody.Builder()
            params.forEach { (key, value) -> bodyBuilder.add(key, value) }
            bodyBuilder.add("api_sig", sig)
            bodyBuilder.add("format", "json")

            val req = Request.Builder()
                .url("https://ws.audioscrobbler.com/2.0/")
                .post(bodyBuilder.build())
                .build()
            httpClient.executeCancellable(req).use { resp ->
                val raw = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (!resp.isSuccessful || json == null || json.has("error")) {
                    val errMsg = json?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "No se pudo autenticar en Last.fm (HTTP ${resp.code})."
                    throw IllegalStateException(errMsg)
                }
                val session = json.optJSONObject("session")
                    ?: throw IllegalStateException("Respuesta sin sesión de Last.fm.")
                val key = session.optString("key")
                val name = session.optString("name").ifBlank { u }
                require(key.isNotBlank()) { "Last.fm no devolvió clave de sesión." }
                name to key
            }
        }
    }

    suspend fun submitLastFm(
        apiKey: String,
        apiSecret: String,
        sessionKey: String,
        track: Track,
        isNowPlaying: Boolean,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (apiKey.isBlank() || apiSecret.isBlank() || sessionKey.isBlank()) return@runCatching
            val artist = track.artist.ifBlank { return@runCatching }
            val title = track.title.ifBlank { return@runCatching }

            val params = linkedMapOf(
                "method" to if (isNowPlaying) "track.updateNowPlaying" else "track.scrobble",
                "api_key" to apiKey.trim(),
                "sk" to sessionKey.trim(),
                "artist" to artist,
                "track" to title
            )
            if (!track.album.isNullOrBlank()) {
                params["album"] = track.album
            }
            if (track.durationMs > 10_000L) {
                params["duration"] = (track.durationMs / 1000L).toString()
            }
            if (!isNowPlaying) {
                params["timestamp"] = timestampSec.toString()
            }
            val sig = signLastFmParams(params, apiSecret.trim())
            val form = FormBody.Builder()
            params.forEach { (k, v) -> form.add(k, v) }
            form.add("api_sig", sig)
            form.add("format", "json")

            val req = Request.Builder()
                .url("https://ws.audioscrobbler.com/2.0/")
                .post(form.build())
                .build()
            httpClient.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Last.fm HTTP ${resp.code}")
                }
            }
        }
    }

    // --- Discord Rich Presence (REST validate + Gateway v10 WebSocket) ---
    suspend fun validateDiscordToken(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = token.trim()
            require(clean.isNotBlank()) { "Ingresa un token de Discord." }
            val req = Request.Builder()
                .url("https://discord.com/api/v10/users/@me")
                .header("Authorization", clean)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.executeCancellable(req).use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Discord rechazó el token (HTTP ${resp.code}).")
                }
                val json = JSONObject(raw)
                val global = json.optString("global_name").takeIf { it.isNotBlank() && it != "null" }
                val user = json.optString("username").takeIf { it.isNotBlank() }
                global ?: user ?: "Usuario Discord"
            }
        }
    }

    fun updateDiscordPresence(settings: SonoraSettings, track: Track?, isPlaying: Boolean) {
        if (!settings.discordRpcEnabled || settings.discordToken.isBlank() || track == null || !isPlaying) {
            pendingPresenceTrack = null
            pendingPresencePlaying = false
            if (discordReady && discordWebSocket != null) {
                sendDiscordPresenceClear()
            }
            if (!settings.discordRpcEnabled || settings.discordToken.isBlank()) {
                disconnectDiscord()
            }
            return
        }

        pendingPresenceTrack = track
        pendingPresencePlaying = isPlaying

        if (discordConnectedToken == settings.discordToken && discordWebSocket != null) {
            if (discordReady) {
                sendDiscordPresenceUpdate(track)
            }
            return
        }

        connectDiscordGateway(settings.discordToken)
    }

    private fun connectDiscordGateway(token: String) {
        disconnectDiscord()
        discordConnectedToken = token
        val req = Request.Builder()
            .url("wss://gateway.discord.gg/?v=10&encoding=json")
            .build()

        discordWebSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    val op = json.optInt("op", -1)
                    when (op) {
                        10 -> { // Hello -> start heartbeat & send Identify
                            val intervalMs = json.optJSONObject("d")?.optLong("heartbeat_interval", 41250L) ?: 41250L
                            startDiscordHeartbeat(webSocket, intervalMs)
                            val identify = JSONObject().apply {
                                put("op", 2)
                                put("d", JSONObject().apply {
                                    put("token", token)
                                    put("intents", 0)
                                    put("properties", JSONObject().apply {
                                        put("os", "Android")
                                        put("browser", "Sonora")
                                        put("device", "Sonora Music")
                                    })
                                })
                            }
                            webSocket.send(identify.toString())
                        }
                        0 -> {
                            if (json.optString("t") == "READY") {
                                discordReady = true
                                pendingPresenceTrack?.takeIf { pendingPresencePlaying }?.let { active ->
                                    sendDiscordPresenceUpdate(active)
                                }
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                discordReady = false
                discordHeartbeatJob?.cancel()
                if (discordWebSocket === webSocket) {
                    discordWebSocket = null
                    discordConnectedToken = null
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                discordReady = false
                discordHeartbeatJob?.cancel()
                if (discordWebSocket === webSocket) {
                    discordWebSocket = null
                    discordConnectedToken = null
                }
            }
        })
    }

    private fun startDiscordHeartbeat(ws: WebSocket, intervalMs: Long) {
        discordHeartbeatJob?.cancel()
        discordHeartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs.coerceAtLeast(10_000L))
                val hb = JSONObject().apply {
                    put("op", 1)
                    put("d", JSONObject.NULL)
                }
                runCatching { ws.send(hb.toString()) }
            }
        }
    }

    private fun sendDiscordPresenceUpdate(track: Track) {
        val ws = discordWebSocket ?: return
        val payload = buildDiscordPresencePayload(track)
        runCatching { ws.send(payload.toString()) }
    }

    private fun sendDiscordPresenceClear() {
        val ws = discordWebSocket ?: return
        val clearPayload = JSONObject().apply {
            put("op", 3)
            put("d", JSONObject().apply {
                put("since", JSONObject.NULL)
                put("activities", JSONArray())
                put("status", "online")
                put("afk", false)
            })
        }
        runCatching { ws.send(clearPayload.toString()) }
    }

    fun disconnectDiscord() {
        discordHeartbeatJob?.cancel()
        discordHeartbeatJob = null
        discordReady = false
        discordConnectedToken = null
        runCatching { discordWebSocket?.close(1000, "Disconnected") }
        discordWebSocket = null
    }

    // --- Coordinated Scrobble Event Dispatch ---
    suspend fun dispatchNowPlaying(settings: SonoraSettings, track: Track) {
        if (settings.listenBrainzEnabled && settings.listenBrainzToken.isNotBlank()) {
            submitListenBrainz(settings.listenBrainzToken, track, isNowPlaying = true)
        }
        if (settings.lastFmEnabled && settings.lastFmSessionKey.isNotBlank()) {
            submitLastFm(
                apiKey = settings.lastFmApiKey,
                apiSecret = settings.lastFmApiSecret,
                sessionKey = settings.lastFmSessionKey,
                track = track,
                isNowPlaying = true
            )
        }
    }

    suspend fun dispatchScrobble(settings: SonoraSettings, track: Track, listenedAtSec: Long) {
        if (settings.listenBrainzEnabled && settings.listenBrainzToken.isNotBlank()) {
            submitListenBrainz(settings.listenBrainzToken, track, isNowPlaying = false, listenedAtSec = listenedAtSec)
        }
        if (settings.lastFmEnabled && settings.lastFmSessionKey.isNotBlank()) {
            submitLastFm(
                apiKey = settings.lastFmApiKey,
                apiSecret = settings.lastFmApiSecret,
                sessionKey = settings.lastFmSessionKey,
                track = track,
                isNowPlaying = false,
                timestampSec = listenedAtSec
            )
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
                    if (cont.isCancelled) response.close() else cont.resume(response)
                }
            })
        }

    companion object {
        fun signLastFmParams(params: Map<String, String>, apiSecret: String): String {
            val concat = params.toSortedMap().entries.joinToString("") { (k, v) -> "$k$v" } + apiSecret
            val digest = MessageDigest.getInstance("MD5").digest(concat.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun buildListenBrainzPayload(
            track: Track,
            isNowPlaying: Boolean,
            listenedAtSec: Long
        ): JSONObject {
            val additionalInfo = JSONObject().apply {
                put("media_player", "Sonora")
                if (track.durationMs > 0L) {
                    put("duration_ms", track.durationMs)
                }
                if (!track.isLocal && track.videoId.isNotBlank()) {
                    put("origin_url", "https://music.youtube.com/watch?v=${track.videoId}")
                }
            }
            val metadata = JSONObject().apply {
                put("artist_name", track.artist.ifBlank { "Artista desconocido" })
                put("track_name", track.title)
                if (!track.album.isNullOrBlank()) {
                    put("release_name", track.album)
                }
                put("additional_info", additionalInfo)
            }
            val listenObj = JSONObject().apply {
                if (!isNowPlaying) {
                    put("listened_at", listenedAtSec)
                }
                put("track_metadata", metadata)
            }
            return JSONObject().apply {
                put("listen_type", if (isNowPlaying) "playing_now" else "single")
                put("payload", JSONArray().put(listenObj))
            }
        }

        fun buildDiscordPresencePayload(track: Track, nowMs: Long = System.currentTimeMillis()): JSONObject {
            val activity = JSONObject().apply {
                put("name", "Sonora")
                put("type", 2) // 2 = Listening
                put("details", track.title.take(120))
                put("state", "por ${track.artist.ifBlank { "Artista" }}".take(120))
                if (track.durationMs > 1_000L) {
                    put("timestamps", JSONObject().apply {
                        put("start", nowMs)
                        put("end", nowMs + track.durationMs)
                    })
                }
                if (!track.album.isNullOrBlank()) {
                    put("assets", JSONObject().apply {
                        put("large_text", track.album.take(120))
                    })
                }
            }
            return JSONObject().apply {
                put("op", 3)
                put("d", JSONObject().apply {
                    put("since", JSONObject.NULL)
                    put("activities", JSONArray().put(activity))
                    put("status", "online")
                    put("afk", false)
                })
            }
        }
    }
}
