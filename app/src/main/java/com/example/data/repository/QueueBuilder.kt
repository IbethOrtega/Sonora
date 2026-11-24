package com.example.data.repository

import com.example.model.QueueMoodFilter
import com.example.model.Track
import java.text.Normalizer
import kotlin.random.Random

/**
 * Pure-Kotlin queue coherence builder for Up Next / AutoPlay radio extensions.
 * Uses multi-seed context (current track + last 3-5 tracks), strict audio/video deduplication,
 * per-artist caps, user affinity scoring (likes & listening history), and low-affinity pruning.
 */
object QueueBuilder {

    data class CohesiveQueueInput(
        val seedTrack: Track,
        val recentContextTracks: List<Track> = emptyList(),
        val upNextTracks: List<Track> = emptyList(),
        val artistSongs: List<Track> = emptyList(),
        val extraSeedUpNextTracks: List<Track> = emptyList(),
        val existingQueue: List<Track> = emptyList(),
        val likedVideoIds: Set<String> = emptySet(),
        val likedArtists: Set<String> = emptySet(),
        val historyVideoIds: Set<String> = emptySet(),
        val historyArtists: Set<String> = emptySet(),
        val previouslyShownIds: Set<String> = emptySet(),
        val lastGenerationIds: Set<String> = emptySet(),
        val moodFilter: QueueMoodFilter = QueueMoodFilter.BALANCED,
        val audioOnlyPreference: Boolean = false,
        val rotationSeed: Long = 0L,
        val limit: Int = 20,
        val minAffinityScore: Int = 10
    )

    private val FOREIGN_SCRIPT_REGEX =
        Regex("[\\u0600-\\u06FF\\u0900-\\u0D7F\\u0E00-\\u0E7F\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF\\u0400-\\u04FF]")

    private val UNWANTED_VARIANT_TOKENS = listOf(
        "cover", "karaoke", "tributo", "tribute", "homenaje", "nightcore", "8d",
        "slowed", "sped up", "speed up", "bass boosted", "parodia", "parody",
        "tutorial", "ringtone", "piano version", "guitar cover", "ai cover",
        "ia cover", "voice cover"
    )

    private val VIDEO_CLIP_TOKENS = listOf(
        "official video", "official music video", "music video", "video oficial",
        "videoclip", "live at", "en vivo", "concierto", "mtv unplugged",
        "performance video", "lyric video", "lyrics video"
    )

    private val AUDIO_EDITION_TOKENS = listOf(
        "official audio", "audio oficial", "official lyric", "lyrics", "lyric", "letra",
        "audio", "remastered", "remaster", "radio edit", "single version",
        "album version", "clean", "explicit", "taylor s version", "taylors version"
    )

    fun buildCohesiveQueue(input: CohesiveQueueInput): List<Track> {
        val seedTracks = (listOf(input.seedTrack) + input.recentContextTracks.takeLast(5).reversed())
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }
        val primarySeed = input.seedTrack
        val seedArtistsNorm = seedTracks.flatMap { splitArtistTokens(it.artist) }.toSet()
        val primarySeedArtistsNorm = splitArtistTokens(primarySeed.artist).toSet()
        val likedArtistsNorm = input.likedArtists.flatMap { splitArtistTokens(it) }.toSet()
        val historyArtistsNorm = input.historyArtists.flatMap { splitArtistTokens(it) }.toSet()

        val existingIds = input.existingQueue.map { it.videoId }.toMutableSet()
        existingIds.add(primarySeed.videoId)

        val seenDedupeKeys = mutableSetOf<String>()
        normalizeDedupeKey(primarySeed.title, primarySeed.artist).takeIf { it.isNotBlank() }?.let { seenDedupeKeys.add(it) }
        input.existingQueue.forEach { t ->
            normalizeDedupeKey(t.title, t.artist).takeIf { it.isNotBlank() }?.let { seenDedupeKeys.add(it) }
        }
        seedTracks.forEach { t ->
            normalizeDedupeKey(t.title, t.artist).takeIf { it.isNotBlank() }?.let { seenDedupeKeys.add(it) }
        }

        val seedText = seedTracks.joinToString(" ") { "${it.title} ${it.artist}" }.lowercase()
        val seedHasForeignScript = FOREIGN_SCRIPT_REGEX.containsMatchIn(seedText)
        val allowedVariants = UNWANTED_VARIANT_TOKENS.filter { tok ->
            Regex("\\b${Regex.escape(tok)}\\b").containsMatchIn(seedText)
        }.toSet()
        val seedBaseTitle = cleanBaseTitleForDedupe(primarySeed.title)

        data class ScoredCandidate(
            val track: Track,
            val affinity: Int,
            val isSameArtistAsPrimary: Boolean,
            val primaryArtistKey: String,
            val isFresh: Boolean,
            val isImmediateLast: Boolean
        )

        val artistOriginSet = input.artistSongs.map { it.videoId }.toSet()
        val directUpNextSet = input.upNextTracks.map { it.videoId }.toSet()
        val multiSeedUpNextSet = input.extraSeedUpNextTracks.map { it.videoId }.toSet()

        // Prefer non-video studio tracks when two versions map to the exact same dedupe key
        val allRaw = (input.artistSongs + input.upNextTracks + input.extraSeedUpNextTracks)
            .filter { it.videoId.isNotBlank() && it.videoId !in existingIds }

        val bestVersionByDedupeKey = linkedMapOf<String, Track>()
        for (cand in allRaw) {
            val combinedLower = "${cand.title} ${cand.artist}".lowercase()
            if (!seedHasForeignScript && FOREIGN_SCRIPT_REGEX.containsMatchIn("${cand.title} ${cand.artist}")) {
                continue
            }
            val unwanted = UNWANTED_VARIANT_TOKENS.any { tok ->
                tok !in allowedVariants && Regex("\\b${Regex.escape(tok)}\\b").containsMatchIn(combinedLower)
            }
            if (unwanted) continue

            val candBaseTitle = cleanBaseTitleForDedupe(cand.title)
            if (seedBaseTitle.isNotBlank() && candBaseTitle == seedBaseTitle) continue

            val dedupeKey = normalizeDedupeKey(cand.title, cand.artist)
            if (dedupeKey.isNotBlank() && dedupeKey in seenDedupeKeys) continue

            val mapKey = dedupeKey.ifBlank { cand.videoId }
            val existing = bestVersionByDedupeKey[mapKey]
            if (existing == null || shouldPreferCandidateVersion(cand, existing, input.audioOnlyPreference)) {
                bestVersionByDedupeKey[mapKey] = cand
            }
        }

        val scored = bestVersionByDedupeKey.values.mapNotNull { cand ->
            val candArtists = splitArtistTokens(cand.artist)
            val primaryCandArtist = candArtists.firstOrNull().orEmpty()
            val sharesPrimarySeedArtist = candArtists.any { ca ->
                primarySeedArtistsNorm.any { sa -> artistsMatch(sa, ca) }
            }
            val sharesAnyRecentSeedArtist = candArtists.any { ca ->
                seedArtistsNorm.any { sa -> artistsMatch(sa, ca) }
            }
            val matchesLikedArtist = candArtists.any { ca ->
                likedArtistsNorm.any { la -> artistsMatch(la, ca) }
            }
            val matchesHistoryArtist = candArtists.any { ca ->
                historyArtistsNorm.any { ha -> artistsMatch(ha, ca) }
            }

            var affinity = 0
            if (cand.videoId in artistOriginSet) affinity += 30
            if (cand.videoId in directUpNextSet) affinity += 24
            if (cand.videoId in multiSeedUpNextSet) affinity += 18
            if (sharesPrimarySeedArtist) affinity += 28
            else if (sharesAnyRecentSeedArtist) affinity += 22
            if (matchesLikedArtist) affinity += 18
            if (matchesHistoryArtist) affinity += 14
            if (cand.videoId in input.likedVideoIds) affinity += 10
            if (cand.videoId in input.historyVideoIds) affinity += 6
            if (isVideoHeavyUpload(cand)) {
                affinity -= if (input.audioOnlyPreference) 14 else 4
            }

            // Duration coherence penalty for extreme outlier lengths (<45s snippets or >15m mixes)
            if (cand.durationMs in 1L..44_999L || cand.durationMs > 900_000L) {
                affinity -= 18
            }

            // Mood adjustments
            when (input.moodFilter) {
                QueueMoodFilter.SAME_ARTIST -> if (sharesPrimarySeedArtist) affinity += 24 else affinity -= 8
                QueueMoodFilter.DISCOVER -> {
                    if (cand.videoId !in input.historyVideoIds && cand.videoId !in input.previouslyShownIds) affinity += 14
                    if (sharesPrimarySeedArtist) affinity -= 6
                }
                QueueMoodFilter.CHILL -> {
                    val tLow = cand.title.lowercase()
                    if (tLow.contains("acoustic") || tLow.contains("acústic") || tLow.contains("slow") || tLow.contains("ballad")) {
                        affinity += 14
                    }
                }
                QueueMoodFilter.MORE_ENERGY -> {
                    val tLow = cand.title.lowercase()
                    if (tLow.contains("remix") || tLow.contains("dance") || tLow.contains("club") || tLow.contains("party")) {
                        affinity += 14
                    }
                }
                QueueMoodFilter.BALANCED -> Unit
            }

            // Discard low-affinity candidates strictly even if fewer tracks remain
            if (affinity < input.minAffinityScore) return@mapNotNull null

            ScoredCandidate(
                track = cand,
                affinity = affinity,
                isSameArtistAsPrimary = sharesPrimarySeedArtist,
                primaryArtistKey = primaryCandArtist.ifBlank { cand.artist.trim().lowercase() },
                isFresh = cand.videoId !in input.previouslyShownIds && cand.videoId !in input.lastGenerationIds,
                isImmediateLast = cand.videoId in input.lastGenerationIds
            )
        }

        // Order tiers: fresh first -> olderSeen -> immediateLast, sorted by affinity + gentle deterministic jitter
        val rng = if (input.rotationSeed != 0L) Random(input.rotationSeed) else null
        fun orderTier(list: List<ScoredCandidate>): List<ScoredCandidate> {
            return list.sortedWith(
                compareByDescending<ScoredCandidate> { it.affinity }
                    .thenBy { if (rng != null) rng.nextInt(8) else 0 }
            )
        }

        val ordered = orderTier(scored.filter { it.isFresh }) +
            orderTier(scored.filter { !it.isFresh && !it.isImmediateLast }) +
            orderTier(scored.filter { it.isImmediateLast })

        val seedArtistCap = if (input.moodFilter == QueueMoodFilter.SAME_ARTIST) 10 else 4
        val otherArtistCap = 2

        // Pre-populate per-artist counts from any upcoming songs already sitting in tail
        val artistCounts = mutableMapOf<String, Int>()
        var seedArtistCount = 0
        for (existing in input.existingQueue.takeLast(8)) {
            val isSeedArt = splitArtistTokens(existing.artist).any { ea ->
                primarySeedArtistsNorm.any { sa -> artistsMatch(sa, ea) }
            }
            if (isSeedArt) {
                seedArtistCount++
            } else {
                val k = splitArtistTokens(existing.artist).firstOrNull().orEmpty()
                if (k.isNotBlank()) artistCounts[k] = (artistCounts[k] ?: 0) + 1
            }
        }

        val accepted = mutableListOf<Track>()
        val usedIds = HashSet<String>(existingIds)
        val usedDedupeKeys = HashSet<String>(seenDedupeKeys)

        for (item in ordered) {
            if (accepted.size >= input.limit) break
            val t = item.track
            if (t.videoId in usedIds) continue
            val dKey = normalizeDedupeKey(t.title, t.artist)
            if (dKey.isNotBlank() && dKey in usedDedupeKeys) continue

            if (item.isSameArtistAsPrimary) {
                if (seedArtistCount >= seedArtistCap) continue
                seedArtistCount++
            } else if (item.primaryArtistKey.isNotBlank()) {
                val count = artistCounts[item.primaryArtistKey] ?: 0
                if (count >= otherArtistCap) continue
                artistCounts[item.primaryArtistKey] = count + 1
            }

            usedIds.add(t.videoId)
            if (dKey.isNotBlank()) usedDedupeKeys.add(dKey)
            accepted.add(t)
        }

        return accepted
    }

    private fun shouldPreferCandidateVersion(
        candidate: Track,
        currentBest: Track,
        audioOnlyPreference: Boolean
    ): Boolean {
        val candVideo = isVideoHeavyUpload(candidate)
        val bestVideo = isVideoHeavyUpload(currentBest)
        if (candVideo != bestVideo) {
            return !candVideo // Studio audio almost always beats music-video upload for music listening
        }
        val candHasArt = candidate.artworkUrl.isNotBlank()
        val bestHasArt = currentBest.artworkUrl.isNotBlank()
        if (candHasArt != bestHasArt) return candHasArt
        return if (audioOnlyPreference) !candVideo else false
    }

    fun isVideoHeavyUpload(track: Track): Boolean {
        val lower = track.title.lowercase()
        return VIDEO_CLIP_TOKENS.any { lower.contains(it) }
    }

    private fun artistsMatch(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && a.replace(" ", "") == b.replace(" ", "")) return true
        return false
    }

    fun stripDiacritics(raw: String): String {
        val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(nfd, "")
    }

    fun splitArtistTokens(artistRaw: String): List<String> {
        return stripDiacritics(artistRaw)
            .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("vevo$", RegexOption.IGNORE_CASE), "")
            .split(",", "&", " feat.", " feat ", " ft.", " ft ", " x ", " X ", ";", "•")
            .map { it.trim().lowercase() }
            .filter {
                it.length >= 2 &&
                    it !in setOf("youtube music", "artista desconocido", "various artists", "topic")
            }
    }

    fun cleanBaseTitleForDedupe(title: String): String {
        var cleaned = stripDiacritics(title)
            .lowercase()
            .replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
        // Handle "Artist - Song Title" YouTube upload patterns consistently
        val dashIdx = cleaned.indexOf(" - ").takeIf { it > 1 } ?: cleaned.indexOf(" – ").takeIf { it > 1 }
        if (dashIdx != null && dashIdx < cleaned.length - 3) {
            cleaned = cleaned.substring(dashIdx + 3)
        }
        cleaned = cleaned
            .replace(Regex("\\s+-\\s+(remaster(ed)?|live|en vivo|radio edit|version|official.*|audio.*|lyric.*)$"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Strip dangling edition words when outside brackets
        for (tok in AUDIO_EDITION_TOKENS + VIDEO_CLIP_TOKENS) {
            if (cleaned.endsWith(" $tok")) {
                cleaned = cleaned.removeSuffix(" $tok").trim()
            }
        }
        return cleaned
    }

    fun normalizeDedupeKey(title: String, artist: String): String {
        val cleanTitle = cleanBaseTitleForDedupe(title)
        val primaryArtist = splitArtistTokens(artist).firstOrNull().orEmpty()
        return if (cleanTitle.isBlank()) "" else "$cleanTitle|$primaryArtist"
    }
}
