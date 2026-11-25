package com.example.data.repository

import com.example.data.local.ListeningHistoryEntity
import com.example.data.local.RecentTrackEntity
import com.example.model.Track
import kotlin.math.pow

data class TasteProfile(
    val artistWeights: Map<String, Double> = emptyMap(),
    val coOccurrenceByArtist: Map<String, Map<String, Double>> = emptyMap(),
    val relatedArtistWeights: Map<String, Double> = emptyMap(),
    val genreWeights: Map<String, Double> = emptyMap(),
    val likedVideoIds: Set<String> = emptySet(),
    val likedArtists: Set<String> = emptySet(),
    val historyVideoIds: Set<String> = emptySet(),
    val historyArtists: Set<String> = emptySet(),
    val recent1HourVideoIds: Set<String> = emptySet(),
    val recent1HourDedupeKeys: Set<String> = emptySet(),
    val recentTracks: List<Track> = emptyList()
) {
    fun weightForArtist(artistRaw: String): Double {
        val tokens = QueueBuilder.splitArtistTokens(artistRaw)
        if (tokens.isEmpty()) return 0.0
        return tokens.maxOfOrNull { tok -> artistWeights[tok] ?: 0.0 } ?: 0.0
    }

    fun topRelatedArtistsForSeed(seedTrack: Track, limit: Int = 4): List<String> {
        val seedTokens = QueueBuilder.splitArtistTokens(seedTrack.artist).toSet()
        val combinedScores = linkedMapOf<String, Double>()

        for (seedTok in seedTokens) {
            coOccurrenceByArtist[seedTok]?.forEach { (other, coWeight) ->
                if (other !in seedTokens) {
                    val base = artistWeights[other] ?: 0.0
                    combinedScores[other] = (combinedScores[other] ?: 0.0) + coWeight * (1.0 + base * 0.25)
                }
            }
        }

        // Fall back or supplement with overall frequent user artists if co-occurrence is sparse
        artistWeights.entries
            .sortedByDescending { it.value }
            .forEach { (artist, weight) ->
                if (artist !in seedTokens && weight > 0.15) {
                    val existing = combinedScores[artist] ?: 0.0
                    combinedScores[artist] = existing + weight * 0.45
                }
            }

        return combinedScores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
}

object TasteProfileBuilder {

    const val HALF_LIFE_DAYS: Double = 30.0
    private const val DAY_MS: Double = 86_400_000.0
    const val ONE_HOUR_MS: Long = 3_600_000L
    private const val SESSION_WINDOW_MS: Long = 2L * 3_600_000L

    private val GENRE_KEYWORDS = listOf(
        "indie", "synthpop", "synth pop", "electropop", "pop", "rock", "alt", "alternative",
        "r&b", "rnb", "soul", "hip hop", "rap", "trap", "reggaeton", "latin", "salsa",
        "cumbia", "bachata", "jazz", "blues", "folk", "acoustic", "electronic", "house",
        "techno", "edm", "lo-fi", "lofi", "ambient", "metal", "punk", "disco", "funk"
    )

    fun decayWeight(playedAtMs: Long, nowMs: Long): Double {
        if (playedAtMs <= 0L) return 0.25
        val ageDays = ((nowMs - playedAtMs).coerceAtLeast(0L)) / DAY_MS
        return 0.5.pow(ageDays / HALF_LIFE_DAYS)
    }

    fun build(
        historyRows: List<ListeningHistoryEntity>,
        likedTracks: List<Track>,
        ytHistoryTracks: List<Track> = emptyList(),
        recentTrackEntities: List<RecentTrackEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): TasteProfile {
        val artistWeights = mutableMapOf<String, Double>()
        val coOccurrence = mutableMapOf<String, MutableMap<String, Double>>()
        val relatedArtistWeights = mutableMapOf<String, Double>()
        val genreWeights = mutableMapOf<String, Double>()

        val likedVideoIds = likedTracks.mapNotNull { it.videoId.takeIf(String::isNotBlank) }.toSet()
        val likedArtists = likedTracks.mapNotNull { it.artist.takeIf(String::isNotBlank) }.toSet()
        val historyVideoIds = mutableSetOf<String>()
        val historyArtists = mutableSetOf<String>()
        val recent1HourVideoIds = mutableSetOf<String>()
        val recent1HourDedupeKeys = mutableSetOf<String>()

        fun addGenreHints(title: String, album: String?, weight: Double) {
            val haystack = "${title} ${album.orEmpty()}".lowercase()
            for (genre in GENRE_KEYWORDS) {
                if (Regex("\\b${Regex.escape(genre)}\\b").containsMatchIn(haystack)) {
                    genreWeights[genre] = (genreWeights[genre] ?: 0.0) + weight
                }
            }
        }

        // 1. ListeningHistoryEntity rows with 30-day exponential decay + session co-occurrence
        val orderedHistory = historyRows.sortedByDescending { it.playedAtMs }
        for (row in orderedHistory) {
            if (row.videoId.isBlank()) continue
            historyVideoIds.add(row.videoId)
            if (row.artist.isNotBlank()) historyArtists.add(row.artist)

            val decay = decayWeight(row.playedAtMs, nowMs)
            val completionFactor = when {
                row.durationMs > 0L && row.listenedMs > 0L ->
                    (row.listenedMs.toDouble() / row.durationMs.toDouble()).coerceIn(0.35, 1.35)
                else -> 1.0
            }
            val effectiveWeight = decay * completionFactor

            val artists = QueueBuilder.splitArtistTokens(row.artist)
            for (a in artists) {
                artistWeights[a] = (artistWeights[a] ?: 0.0) + effectiveWeight
            }
            // Collaborative track artists co-occur strongly
            if (artists.size > 1) {
                for (i in artists.indices) {
                    for (j in artists.indices) {
                        if (i == j) continue
                        val map = coOccurrence.getOrPut(artists[i]) { mutableMapOf() }
                        map[artists[j]] = (map[artists[j]] ?: 0.0) + effectiveWeight
                        relatedArtistWeights[artists[j]] = (relatedArtistWeights[artists[j]] ?: 0.0) + effectiveWeight * 0.5
                    }
                }
            }
            addGenreHints(row.title, row.album, effectiveWeight)

            if (nowMs - row.playedAtMs <= ONE_HOUR_MS && row.playedAtMs <= nowMs + 60_000L) {
                recent1HourVideoIds.add(row.videoId)
                QueueBuilder.normalizeDedupeKey(row.title, row.artist)
                    .takeIf { it.isNotBlank() }
                    ?.let { recent1HourDedupeKeys.add(it) }
            }
        }

        // Session co-occurrence between consecutive history rows within 2 hours
        for (i in orderedHistory.indices) {
            val current = orderedHistory[i]
            val currentArtists = QueueBuilder.splitArtistTokens(current.artist)
            if (currentArtists.isEmpty()) continue
            val pairDecay = decayWeight(current.playedAtMs, nowMs)

            var j = i + 1
            while (j < orderedHistory.size && j <= i + 5) {
                val neighbor = orderedHistory[j]
                if (kotlin.math.abs(current.playedAtMs - neighbor.playedAtMs) > SESSION_WINDOW_MS) break
                val neighborArtists = QueueBuilder.splitArtistTokens(neighbor.artist)
                for (ca in currentArtists) {
                    for (na in neighborArtists) {
                        if (ca != na) {
                            val m1 = coOccurrence.getOrPut(ca) { mutableMapOf() }
                            m1[na] = (m1[na] ?: 0.0) + pairDecay * 0.65
                            val m2 = coOccurrence.getOrPut(na) { mutableMapOf() }
                            m2[ca] = (m2[ca] ?: 0.0) + pairDecay * 0.65
                            relatedArtistWeights[na] = (relatedArtistWeights[na] ?: 0.0) + pairDecay * 0.3
                        }
                    }
                }
                j++
            }
        }

        // 2. RecentTrackEntity rows (e.g. quick local/YT recents + 1h protection)
        for (recent in recentTrackEntities) {
            if (recent.videoId.isBlank()) continue
            historyVideoIds.add(recent.videoId)
            if (recent.artist.isNotBlank()) historyArtists.add(recent.artist)
            val w = decayWeight(recent.playedAt, nowMs) * 0.75
            for (a in QueueBuilder.splitArtistTokens(recent.artist)) {
                artistWeights[a] = (artistWeights[a] ?: 0.0) + w
            }
            if (nowMs - recent.playedAt <= ONE_HOUR_MS && recent.playedAt <= nowMs + 60_000L) {
                recent1HourVideoIds.add(recent.videoId)
                QueueBuilder.normalizeDedupeKey(recent.title, recent.artist)
                    .takeIf { it.isNotBlank() }
                    ?.let { recent1HourDedupeKeys.add(it) }
            }
        }

        // 3. Liked songs (explicit high-affinity signal)
        for (liked in likedTracks) {
            for (a in QueueBuilder.splitArtistTokens(liked.artist)) {
                artistWeights[a] = (artistWeights[a] ?: 0.0) + 1.25
            }
            addGenreHints(liked.title, liked.album, 1.0)
        }

        // 4. YouTube Music account history
        for ((idx, ytTrack) in ytHistoryTracks.withIndex()) {
            if (ytTrack.videoId.isBlank()) continue
            historyVideoIds.add(ytTrack.videoId)
            if (ytTrack.artist.isNotBlank()) historyArtists.add(ytTrack.artist)
            val positionalDecay = (1.0 - (idx * 0.01)).coerceAtLeast(0.2) * 0.55
            for (a in QueueBuilder.splitArtistTokens(ytTrack.artist)) {
                artistWeights[a] = (artistWeights[a] ?: 0.0) + positionalDecay
            }
            addGenreHints(ytTrack.title, ytTrack.album, positionalDecay)
        }

        val combinedRecentTracks = (orderedHistory.take(40).map {
            Track(
                videoId = it.videoId,
                title = it.title,
                artist = it.artist,
                artistId = it.artistId,
                album = it.album,
                albumId = it.albumId,
                artworkUrl = it.artworkUrl,
                durationText = it.durationText,
                durationMs = it.durationMs
            )
        } + ytHistoryTracks.take(20)).distinctBy { it.videoId }

        return TasteProfile(
            artistWeights = artistWeights,
            coOccurrenceByArtist = coOccurrence.mapValues { it.value.toMap() },
            relatedArtistWeights = relatedArtistWeights,
            genreWeights = genreWeights,
            likedVideoIds = likedVideoIds,
            likedArtists = likedArtists,
            historyVideoIds = historyVideoIds,
            historyArtists = historyArtists,
            recent1HourVideoIds = recent1HourVideoIds,
            recent1HourDedupeKeys = recent1HourDedupeKeys,
            recentTracks = combinedRecentTracks
        )
    }
}
