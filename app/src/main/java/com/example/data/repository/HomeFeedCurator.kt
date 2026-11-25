package com.example.data.repository

import com.example.data.local.ListeningHistoryEntity
import com.example.data.local.RecentTrackEntity
import com.example.model.HomeShelf
import com.example.model.Track
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

object HomeFeedCurator {

    const val ONE_DAY_MS: Long = 86_400_000L
    const val THIRTY_DAYS_MS: Long = 30L * ONE_DAY_MS

    data class TimeOfDaySlot(
        val title: String,
        val subtitle: String,
        val startHour: Int,
        val endHour: Int
    )

    fun dailyRotationSeed(nowMs: Long = System.currentTimeMillis(), pullToRefreshNonce: Int = 0): Long {
        val epochDay = Instant.ofEpochMilli(nowMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
        return epochDay * 1_000_003L + pullToRefreshNonce.toLong() * 7_919L
    }

    fun <T> rotateDeterministically(items: List<T>, seed: Long): List<T> {
        if (items.size <= 1) return items
        return items.shuffled(Random(seed))
    }

    fun timeOfDaySlot(nowMs: Long = System.currentTimeMillis()): TimeOfDaySlot {
        val hour = Instant.ofEpochMilli(nowMs)
            .atZone(ZoneId.systemDefault())
            .hour
        return when (hour) {
            in 5..11 -> TimeOfDaySlot(
                title = "Para esta mañana",
                subtitle = "Tu ritmo matutino",
                startHour = 5,
                endHour = 11
            )
            in 12..18 -> TimeOfDaySlot(
                title = "Para esta tarde",
                subtitle = "Para seguir el día",
                startHour = 12,
                endHour = 18
            )
            else -> TimeOfDaySlot(
                title = "Para esta noche",
                subtitle = "Sesión nocturna personalizada",
                startHour = 19,
                endHour = 4
            )
        }
    }

    fun isHourInSlot(hour: Int, slot: TimeOfDaySlot): Boolean {
        return if (slot.startHour <= slot.endHour) {
            hour in slot.startHour..slot.endHour
        } else {
            hour >= slot.startHour || hour <= slot.endHour
        }
    }

    fun buildPlayedLast24hExclusion(
        historyRows: List<ListeningHistoryEntity>,
        recentRows: List<RecentTrackEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Set<String>, Set<String>> {
        val cutoff = nowMs - ONE_DAY_MS
        val ids = mutableSetOf<String>()
        val dedupeKeys = mutableSetOf<String>()

        for (row in historyRows) {
            if (row.playedAtMs >= cutoff && row.playedAtMs <= nowMs + 60_000L && row.videoId.isNotBlank()) {
                ids.add(row.videoId)
                QueueBuilder.normalizeDedupeKey(row.title, row.artist)
                    .takeIf { it.isNotBlank() }
                    ?.let { dedupeKeys.add(it) }
            }
        }
        for (recent in recentRows) {
            if (recent.playedAt >= cutoff && recent.playedAt <= nowMs + 60_000L && recent.videoId.isNotBlank()) {
                ids.add(recent.videoId)
                QueueBuilder.normalizeDedupeKey(recent.title, recent.artist)
                    .takeIf { it.isNotBlank() }
                    ?.let { dedupeKeys.add(it) }
            }
        }
        return ids to dedupeKeys
    }

    fun findTimeOfDayTracks(
        historyRows: List<ListeningHistoryEntity>,
        slot: TimeOfDaySlot,
        excluded24hIds: Set<String>,
        excluded24hDedupeKeys: Set<String>,
        seed: Long,
        limit: Int = 12
    ): List<Track> {
        val zone = ZoneId.systemDefault()
        val matching = historyRows.filter { row ->
            if (row.videoId.isBlank()) return@filter false
            if (row.videoId in excluded24hIds) return@filter false
            val key = QueueBuilder.normalizeDedupeKey(row.title, row.artist)
            if (key.isNotBlank() && key in excluded24hDedupeKeys) return@filter false
            val h = Instant.ofEpochMilli(row.playedAtMs).atZone(zone).hour
            isHourInSlot(h, slot)
        }
        if (matching.isEmpty()) return emptyList()

        val countsById = matching.groupingBy { it.videoId }.eachCount()
        val distinct = matching
            .distinctBy { it.videoId }
            .sortedByDescending { countsById[it.videoId] ?: 0 }
            .map {
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
            }
        return rotateDeterministically(distinct.take(limit * 2), seed + 17L).take(limit)
    }

    fun findRediscoverTracks(
        likedTracks: List<Track>,
        historyRows: List<ListeningHistoryEntity>,
        excluded24hIds: Set<String>,
        excluded24hDedupeKeys: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
        seed: Long = 0L,
        limit: Int = 12
    ): List<Track> {
        if (likedTracks.isEmpty()) return emptyList()
        val cutoff = nowMs - THIRTY_DAYS_MS
        val latestPlayedById = mutableMapOf<String, Long>()
        for (row in historyRows) {
            val prev = latestPlayedById[row.videoId] ?: 0L
            if (row.playedAtMs > prev) latestPlayedById[row.videoId] = row.playedAtMs
        }

        val candidates = likedTracks.filter { track ->
            if (track.videoId.isBlank() || track.videoId in excluded24hIds) return@filter false
            val key = QueueBuilder.normalizeDedupeKey(track.title, track.artist)
            if (key.isNotBlank() && key in excluded24hDedupeKeys) return@filter false
            val lastPlayed = latestPlayedById[track.videoId]
            lastPlayed == null || lastPlayed < cutoff
        }.distinctBy { it.videoId }

        return rotateDeterministically(candidates, seed + 31L).take(limit)
    }

    /**
     * Ensures each track appears in at most ONE section across Home, and excludes tracks
     * played in the last 24 hours from all discovery sections.
     */
    fun deduplicateHomeShelves(
        shelves: List<HomeShelf>,
        excluded24hIds: Set<String>,
        excluded24hDedupeKeys: Set<String>,
        preClaimedIds: Set<String> = emptySet(),
        preClaimedDedupeKeys: Set<String> = emptySet(),
        exempt24hTitles: Set<String> = setOf("Volver a escuchar", "Tus más escuchadas")
    ): List<HomeShelf> {
        val seenIds = preClaimedIds.toMutableSet()
        val seenDedupeKeys = preClaimedDedupeKeys.toMutableSet()

        return shelves.mapNotNull { shelf ->
            val apply24hExclusion = shelf.title !in exempt24hTitles
            val filteredTracks = shelf.tracks.filter { track ->
                if (track.videoId.isBlank()) return@filter false
                val dedupeKey = QueueBuilder.normalizeDedupeKey(track.title, track.artist)
                if (apply24hExclusion) {
                    if (track.videoId in excluded24hIds) return@filter false
                    if (dedupeKey.isNotBlank() && dedupeKey in excluded24hDedupeKeys) return@filter false
                }
                if (!seenIds.add(track.videoId)) return@filter false
                if (dedupeKey.isNotBlank() && !seenDedupeKeys.add(dedupeKey)) return@filter false
                true
            }
            if (filteredTracks.isEmpty() && shelf.items.isEmpty()) {
                null
            } else {
                shelf.copy(tracks = filteredTracks)
            }
        }
    }
}
