package com.example.data.network

import android.util.Log
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects per-video stream resolution diagnostics (clients, playabilityStatus, probe outcomes,
 * and alternate candidates with scores) while strictly redacting full stream URLs.
 */
object PlaybackDiagnostics {
    private const val TAG = "Sonora"
    private const val MAX_SESSIONS = 32
    private const val MAX_CLIENT_LINES = 24
    private const val MAX_ALTERNATE_LINES = 16

    private val URL_REGEX = Regex("https?://[^\\s,)\"'>\\]]+", RegexOption.IGNORE_CASE)

    private class Session(
        @Volatile var title: String = "",
        @Volatile var artist: String = "",
        val clientEvents: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        val alternateEvents: MutableList<String> = Collections.synchronizedList(mutableListOf())
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun beginResolution(videoId: String, title: String = "", artist: String = "") {
        if (videoId.isBlank()) return
        trimIfNeeded()
        val session = sessions.getOrPut(videoId) { Session() }
        if (title.isNotBlank()) session.title = title
        if (artist.isNotBlank()) session.artist = artist
        session.clientEvents.clear()
        session.alternateEvents.clear()
    }

    fun updateTrackInfo(videoId: String, title: String, artist: String) {
        if (videoId.isBlank()) return
        val session = sessions.getOrPut(videoId) { Session() }
        if (title.isNotBlank()) session.title = title
        if (artist.isNotBlank()) session.artist = artist
    }

    fun recordClientResult(videoId: String, entry: String) {
        if (videoId.isBlank() || entry.isBlank()) return
        trimIfNeeded()
        val clean = sanitize(entry)
        val session = sessions.getOrPut(videoId) { Session() }
        synchronized(session.clientEvents) {
            if (session.clientEvents.lastOrNull() != clean) {
                if (session.clientEvents.size >= MAX_CLIENT_LINES) {
                    session.clientEvents.removeAt(0)
                }
                session.clientEvents.add(clean)
            }
        }
    }

    fun recordAlternateEvent(videoId: String, entry: String) {
        if (videoId.isBlank() || entry.isBlank()) return
        trimIfNeeded()
        val clean = sanitize(entry)
        val session = sessions.getOrPut(videoId) { Session() }
        synchronized(session.alternateEvents) {
            if (session.alternateEvents.lastOrNull() != clean) {
                if (session.alternateEvents.size >= MAX_ALTERNATE_LINES) {
                    session.alternateEvents.removeAt(0)
                }
                session.alternateEvents.add(clean)
            }
        }
    }

    fun buildReport(
        videoId: String,
        titleHint: String? = null,
        artistHint: String? = null
    ): String {
        val session = sessions[videoId]
        val title = titleHint?.takeIf { it.isNotBlank() } ?: session?.title.orEmpty().ifBlank { "Desconocido" }
        val artist = artistHint?.takeIf { it.isNotBlank() } ?: session?.artist.orEmpty().ifBlank { "Desconocido" }
        val clients = session?.let { s -> synchronized(s.clientEvents) { s.clientEvents.toList() } }.orEmpty()
        val alternates = session?.let { s -> synchronized(s.alternateEvents) { s.alternateEvents.toList() } }.orEmpty()

        return buildString {
            appendLine("videoId: $videoId")
            appendLine("Título: $title")
            appendLine("Artista: $artist")
            appendLine("Clientes y probe:")
            if (clients.isEmpty()) {
                appendLine("• Sin intentos registrados")
            } else {
                clients.forEach { appendLine("• $it") }
            }
            if (alternates.isNotEmpty()) {
                appendLine("Candidatos alternos:")
                alternates.forEach { appendLine("• $it") }
            }
        }.trimEnd()
    }

    fun logFailureReport(
        videoId: String,
        titleHint: String? = null,
        artistHint: String? = null
    ): String {
        val report = buildReport(videoId, titleHint, artistHint)
        runCatching {
            Log.w(TAG, "PlaybackDiagnostic [$videoId]:\n$report")
        }
        return report
    }

    fun sanitize(raw: String): String {
        return URL_REGEX.replace(raw) { match ->
            val url = match.value
            val host = runCatching { URI(url).host?.takeIf { it.isNotBlank() } }.getOrNull()
            if (host != null) "<$host/...>" else "<url-oculta>"
        }.replace(Regex("\\s+"), " ").trim()
    }

    internal fun clearForTest() {
        sessions.clear()
    }

    private fun trimIfNeeded() {
        if (sessions.size <= MAX_SESSIONS) return
        val firstKey = sessions.keys.firstOrNull() ?: return
        sessions.remove(firstKey)
    }
}
