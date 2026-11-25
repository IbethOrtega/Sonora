package com.example.data.repository

import com.example.model.QueueMoodFilter
import com.example.model.Track
import java.text.Normalizer
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure-Kotlin queue coherence builder for Up Next / AutoPlay radio extensions.
 * Enforces taste-weighted radio curation (60% seed radio filtered by taste,
 * 25% related frequent taste artists, 15% same artist), homonym/title-only trap
 * rejection, strict variant exclusion, 1-hour recency blocklist, and max 2 consecutive
 * songs by the same artist.
 */
object QueueBuilder {

    data class CohesiveQueueInput(
        val seedTrack: Track,
        val recentContextTracks: List<Track> = emptyList(),
        val upNextTracks: List<Track> = emptyList(),
        val artistSongs: List<Track> = emptyList(),
        val relatedArtistTracks: List<Track> = emptyList(),
        val extraSeedUpNextTracks: List<Track> = emptyList(),
        val existingQueue: List<Track> = emptyList(),
        val tasteProfile: TasteProfile? = null,
        val seedRelatedArtists: Set<String> = emptySet(),
        val likedVideoIds: Set<String> = emptySet(),
        val likedArtists: Set<String> = emptySet(),
        val historyVideoIds: Set<String> = emptySet(),
        val historyArtists: Set<String> = emptySet(),
        val lastHourVideoIds: Set<String> = emptySet(),
        val lastHourDedupeKeys: Set<String> = emptySet(),
        val previouslyShownIds: Set<String> = emptySet(),
        val lastGenerationIds: Set<String> = emptySet(),
        val moodFilter: QueueMoodFilter = QueueMoodFilter.BALANCED,
        val audioOnlyPreference: Boolean = false,
        val rotationSeed: Long = 0L,
        val limit: Int = 20,
        val minAffinityScore: Int = 10
    )

    private enum class QueueBucket {
        SEED_RADIO,
        RELATED_TASTE,
        SAME_ARTIST
    }

    private val FOREIGN_SCRIPT_REGEX =
        Regex("[\\u0600-\\u06FF\\u0900-\\u0D7F\\u0E00-\\u0E7F\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF\\u0400-\\u04FF]")

    private val UNWANTED_VARIANT_TOKENS = listOf(
        "cover", "karaoke", "tributo", "tribute", "homenaje", "nightcore", "8d",
        "slowed", "sped up", "speed up", "bass boosted", "parodia", "parody",
        "tutorial", "ringtone", "piano version", "guitar cover", "ai cover",
        "ia cover", "voice cover", "acoustic", "acustico", "acústico",
        "live", "en vivo", "unplugged", "remix", "rework", "vip mix"
    )

    private val VIDEO_CLIP_TOKENS = listOf(
        "official video", "official music video", "music video", "video oficial",
        "videoclip", "live at", "en vivo", "concierto", "mtv unplugged",
        "performance video", "lyric video", "lyrics video"
    )

    private val AUDIO_EDITION_TOKENS = listOf(
        "official audio", "audio oficial", "official lyric", "lyrics", "lyric", "letra",
        "audio", "remastered", "remaster", "radio edit", "single version",
        "album version", "clean", "explicit", "taylor s version", "taylors version",
        "acoustic", "acustico", "acustica", "live", "en vivo", "unplugged",
        "remix", "slowed", "sped up", "speed up", "cover", "demo", "rework", "vip", "edit"
    )

    private val STOP_WORDS = setOf(
        "the", "and", "for", "you", "are", "with", "from", "that", "this", "your",
        "con", "por", "para", "una", "uno", "unos", "las", "los", "del", "que", "sin",
        "song", "feat", "version", "remix", "live", "audio", "official", "video"
    )

    fun buildCohesiveQueue(input: CohesiveQueueInput): List<Track> {
        val taste = input.tasteProfile
        val seedTracks = (listOf(input.seedTrack) + input.recentContextTracks.takeLast(5).reversed())
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }
        val primarySeed = input.seedTrack
        val seedArtistsNorm = seedTracks.flatMap { splitArtistTokens(it.artist) }.toSet()
        val primarySeedArtistsNorm = splitArtistTokens(primarySeed.artist).toSet()

        val allLikedIds = input.likedVideoIds + taste?.likedVideoIds.orEmpty()
        val allHistoryIds = input.historyVideoIds + taste?.historyVideoIds.orEmpty()
        val likedArtistsNorm = (input.likedArtists + taste?.likedArtists.orEmpty())
            .flatMap { splitArtistTokens(it) }
            .toSet()
        val historyArtistsNorm = (input.historyArtists + taste?.historyArtists.orEmpty())
            .flatMap { splitArtistTokens(it) }
            .toSet()
        val seedRelatedArtistsNorm = (
            input.seedRelatedArtists +
                (taste?.topRelatedArtistsForSeed(primarySeed, limit = 8).orEmpty())
            )
            .flatMap { splitArtistTokens(it) }
            .toSet()

        val blocked1HourIds = input.lastHourVideoIds + taste?.recent1HourVideoIds.orEmpty()
        val blocked1HourDedupeKeys = input.lastHourDedupeKeys + taste?.recent1HourDedupeKeys.orEmpty()

        val existingIds = input.existingQueue.map { it.videoId }.toMutableSet()
        existingIds.add(primarySeed.videoId)

        val seenDedupeKeys = mutableSetOf<String>()
        val existingBaseTitles = mutableSetOf<String>()
        normalizeDedupeKey(primarySeed.title, primarySeed.artist).takeIf { it.isNotBlank() }?.let { seenDedupeKeys.add(it) }
        cleanBaseTitleForDedupe(primarySeed.title).takeIf { it.isNotBlank() }?.let { existingBaseTitles.add(it) }
        input.existingQueue.forEach { t ->
            normalizeDedupeKey(t.title, t.artist).takeIf { it.isNotBlank() }?.let { seenDedupeKeys.add(it) }
            cleanBaseTitleForDedupe(t.title).takeIf { it.isNotBlank() }?.let { existingBaseTitles.add(it) }
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
            val bucket: QueueBucket,
            val primaryArtistKey: String,
            val isFresh: Boolean,
            val isImmediateLast: Boolean
        )

        val artistOriginSet = input.artistSongs.map { it.videoId }.toSet()
        val relatedArtistOriginSet = input.relatedArtistTracks.map { it.videoId }.toSet()
        val directUpNextSet = input.upNextTracks.map { it.videoId }.toSet()
        val multiSeedUpNextSet = input.extraSeedUpNextTracks.map { it.videoId }.toSet()

        val allRaw = (
            input.upNextTracks +
                input.relatedArtistTracks +
                input.artistSongs +
                input.extraSeedUpNextTracks
            ).filter { it.videoId.isNotBlank() && it.videoId !in existingIds && it.videoId !in blocked1HourIds }

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
            // Block any alternate version / homonym of the seed OR of an already queued song
            if (candBaseTitle.isNotBlank() && (candBaseTitle == seedBaseTitle || candBaseTitle in existingBaseTitles)) {
                continue
            }

            val dedupeKey = normalizeDedupeKey(cand.title, cand.artist)
            if (dedupeKey.isNotBlank() && (dedupeKey in seenDedupeKeys || dedupeKey in blocked1HourDedupeKeys)) {
                continue
            }

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
            val matchesRelatedSeedArtist = cand.videoId in relatedArtistOriginSet ||
                candArtists.any { ca -> seedRelatedArtistsNorm.any { ra -> artistsMatch(ra, ca) } }

            val tasteArtistWeight = taste?.weightForArtist(cand.artist) ?: 0.0
            val hasTasteAffinity = tasteArtistWeight > 0.0 ||
                matchesLikedArtist ||
                matchesHistoryArtist ||
                cand.videoId in allLikedIds ||
                cand.videoId in allHistoryIds
            val hasRadioProvenance = cand.videoId in directUpNextSet ||
                cand.videoId in multiSeedUpNextSet ||
                cand.videoId in artistOriginSet ||
                cand.videoId in relatedArtistOriginSet

            // Rule: discard candidates with neither radio provenance nor taste affinity
            if (!hasRadioProvenance && !hasTasteAffinity) {
                return@mapNotNull null
            }

            // Rule: strictly discard candidates whose only connection is title-word overlap with the seed
            if (!sharesPrimarySeedArtist &&
                !matchesRelatedSeedArtist &&
                !hasTasteAffinity &&
                hasTitleWordOverlapWithSeed(cand.title, primarySeed.title)
            ) {
                return@mapNotNull null
            }

            var affinity = 0
            if (cand.videoId in artistOriginSet) affinity += 28
            if (cand.videoId in directUpNextSet) affinity += 24
            if (cand.videoId in relatedArtistOriginSet) affinity += 22
            if (cand.videoId in multiSeedUpNextSet) affinity += 16
            if (sharesPrimarySeedArtist) affinity += 24
            else if (sharesAnyRecentSeedArtist) affinity += 18

            if (tasteArtistWeight > 0.0) {
                affinity += (tasteArtistWeight * 12.0).toInt().coerceIn(4, 28)
            }
            if (matchesLikedArtist) affinity += 16
            if (matchesHistoryArtist) affinity += 12
            if (matchesRelatedSeedArtist) affinity += 14
            if (cand.videoId in allLikedIds) affinity += 10
            if (cand.videoId in allHistoryIds) affinity += 6

            // Genre boost from TasteProfile if available
            if (taste != null && taste.genreWeights.isNotEmpty()) {
                val candMetaLower = "${cand.title} ${cand.album.orEmpty()}".lowercase()
                val genreBonus = taste.genreWeights.entries
                    .filter { (genre, _) -> Regex("\\b${Regex.escape(genre)}\\b").containsMatchIn(candMetaLower) }
                    .sumOf { it.value }
                if (genreBonus > 0.0) {
                    affinity += (genreBonus * 4.0).toInt().coerceIn(2, 12)
                }
            }

            if (isVideoHeavyUpload(cand)) {
                affinity -= if (input.audioOnlyPreference) 14 else 4
            }
            if (cand.durationMs in 1L..44_999L || cand.durationMs > 900_000L) {
                affinity -= 18
            }

            when (input.moodFilter) {
                QueueMoodFilter.SAME_ARTIST -> if (sharesPrimarySeedArtist) affinity += 24 else affinity -= 8
                QueueMoodFilter.DISCOVER -> {
                    if (cand.videoId !in allHistoryIds && cand.videoId !in input.previouslyShownIds) affinity += 14
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

            if (affinity < input.minAffinityScore) return@mapNotNull null

            val bucket = when {
                sharesPrimarySeedArtist -> QueueBucket.SAME_ARTIST
                matchesRelatedSeedArtist && (hasTasteAffinity || cand.videoId in relatedArtistOriginSet) ->
                    QueueBucket.RELATED_TASTE
                else -> QueueBucket.SEED_RADIO
            }

            ScoredCandidate(
                track = cand,
                affinity = affinity,
                bucket = bucket,
                primaryArtistKey = primaryCandArtist.ifBlank { cand.artist.trim().lowercase() },
                isFresh = cand.videoId !in input.previouslyShownIds && cand.videoId !in input.lastGenerationIds,
                isImmediateLast = cand.videoId in input.lastGenerationIds
            )
        }

        val rng = if (input.rotationSeed != 0L) Random(input.rotationSeed) else null
        fun orderTier(list: List<ScoredCandidate>): List<ScoredCandidate> {
            return list.sortedWith(
                compareByDescending<ScoredCandidate> { it.affinity }
                    .thenBy { if (rng != null) rng.nextInt(6) else 0 }
            )
        }

        val ordered = orderTier(scored.filter { it.isFresh }) +
            orderTier(scored.filter { !it.isFresh && !it.isImmediateLast }) +
            orderTier(scored.filter { it.isImmediateLast })

        val bucketPools = mapOf(
            QueueBucket.SEED_RADIO to ordered.filter { it.bucket == QueueBucket.SEED_RADIO }.toMutableList(),
            QueueBucket.RELATED_TASTE to ordered.filter { it.bucket == QueueBucket.RELATED_TASTE }.toMutableList(),
            QueueBucket.SAME_ARTIST to ordered.filter { it.bucket == QueueBucket.SAME_ARTIST }.toMutableList()
        )

        val targetShares = if (input.moodFilter == QueueMoodFilter.SAME_ARTIST) {
            mapOf(
                QueueBucket.SAME_ARTIST to 0.65,
                QueueBucket.SEED_RADIO to 0.25,
                QueueBucket.RELATED_TASTE to 0.10
            )
        } else {
            mapOf(
                QueueBucket.SEED_RADIO to 0.60,
                QueueBucket.RELATED_TASTE to 0.25,
                QueueBucket.SAME_ARTIST to 0.15
            )
        }

        val seedArtistCap = if (input.moodFilter == QueueMoodFilter.SAME_ARTIST) {
            min(input.limit, 12)
        } else {
            kotlin.math.max(2, kotlin.math.ceil(input.limit * 0.25).toInt())
        }
        val otherArtistCap = 3

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

        // Track trailing artist sequence from existingQueue to enforce max 2 consecutive songs per artist
        val trailingArtists = input.existingQueue
            .ifEmpty { listOf(primarySeed) }
            .takeLast(2)
            .map { splitArtistTokens(it.artist).firstOrNull().orEmpty().ifBlank { it.artist.trim().lowercase() } }
            .toMutableList()

        val accepted = mutableListOf<Track>()
        val usedIds = HashSet<String>(existingIds)
        val usedDedupeKeys = HashSet<String>(seenDedupeKeys)
        val usedBaseTitles = HashSet<String>(existingBaseTitles)
        val bucketCounts = mutableMapOf(
            QueueBucket.SEED_RADIO to 0,
            QueueBucket.RELATED_TASTE to 0,
            QueueBucket.SAME_ARTIST to 0
        )

        fun canAccept(item: ScoredCandidate, ignoreSoftCaps: Boolean = false): Boolean {
            val t = item.track
            if (t.videoId in usedIds) return false
            val dKey = normalizeDedupeKey(t.title, t.artist)
            if (dKey.isNotBlank() && dKey in usedDedupeKeys) return false
            val baseTitle = cleanBaseTitleForDedupe(t.title)
            if (baseTitle.isNotBlank() && baseTitle in usedBaseTitles) return false

            // Hard rule: never more than 2 consecutive songs by the same artist
            val artKey = item.primaryArtistKey
            if (artKey.isNotBlank() &&
                trailingArtists.size >= 2 &&
                artistsMatch(trailingArtists[trailingArtists.lastIndex], artKey) &&
                artistsMatch(trailingArtists[trailingArtists.lastIndex - 1], artKey)
            ) {
                return false
            }

            if (!ignoreSoftCaps) {
                if (item.bucket == QueueBucket.SAME_ARTIST && seedArtistCount >= seedArtistCap) {
                    return false
                }
                if (item.bucket != QueueBucket.SAME_ARTIST && artKey.isNotBlank()) {
                    if ((artistCounts[artKey] ?: 0) >= otherArtistCap) return false
                }
            }
            return true
        }

        fun commitCandidate(item: ScoredCandidate) {
            val t = item.track
            usedIds.add(t.videoId)
            normalizeDedupeKey(t.title, t.artist).takeIf { it.isNotBlank() }?.let { usedDedupeKeys.add(it) }
            cleanBaseTitleForDedupe(t.title).takeIf { it.isNotBlank() }?.let { usedBaseTitles.add(it) }
            if (item.bucket == QueueBucket.SAME_ARTIST) {
                seedArtistCount++
            } else if (item.primaryArtistKey.isNotBlank()) {
                artistCounts[item.primaryArtistKey] = (artistCounts[item.primaryArtistKey] ?: 0) + 1
            }
            trailingArtists.add(item.primaryArtistKey)
            if (trailingArtists.size > 2) trailingArtists.removeAt(0)
            bucketCounts[item.bucket] = (bucketCounts[item.bucket] ?: 0) + 1
            accepted.add(t)
        }

        while (accepted.size < input.limit) {
            val totalAfterPick = (accepted.size + 1).toDouble()
            // Order buckets by largest deficit relative to target 60 / 25 / 15 ratio
            val rankedBuckets = QueueBucket.entries.sortedByDescending { b ->
                val target = targetShares[b] ?: 0.0
                val current = (bucketCounts[b] ?: 0) / totalAfterPick
                target - current
            }

            var picked: Pair<QueueBucket, Int>? = null
            for (b in rankedBuckets) {
                val pool = bucketPools[b].orEmpty()
                val idx = pool.indexOfFirst { canAccept(it, ignoreSoftCaps = false) }
                if (idx >= 0) {
                    picked = b to idx
                    break
                }
            }
            if (picked == null) {
                // Relax total per-artist cap only, still strictly respecting max-2-consecutive & dedupe/variant/homonym rules
                for (b in rankedBuckets) {
                    val pool = bucketPools[b].orEmpty()
                    val idx = pool.indexOfFirst { canAccept(it, ignoreSoftCaps = true) }
                    if (idx >= 0) {
                        picked = b to idx
                        break
                    }
                }
            }
            if (picked == null) break
            val item = bucketPools[picked.first]!!.removeAt(picked.second)
            commitCandidate(item)
        }

        return accepted
    }

    fun hasTitleWordOverlapWithSeed(candidateTitle: String, seedTitle: String): Boolean {
        val seedClean = cleanBaseTitleForDedupe(seedTitle)
        val candClean = cleanBaseTitleForDedupe(candidateTitle)
        if (seedClean.isBlank() || candClean.isBlank()) return false
        if (seedClean == candClean) return true

        val seedWords = seedClean.split(" ")
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
        val candWords = candClean.split(" ")
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
        if (seedWords.isEmpty() || candWords.isEmpty()) return false
        return seedWords.any { it in candWords }
    }

    private fun shouldPreferCandidateVersion(
        candidate: Track,
        currentBest: Track,
        audioOnlyPreference: Boolean
    ): Boolean {
        val candVideo = isVideoHeavyUpload(candidate)
        val bestVideo = isVideoHeavyUpload(currentBest)
        if (candVideo != bestVideo) {
            return !candVideo
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
            .split(",", "&", " feat.", " feat ", " ft.", " ft ", " x ", " X ", " with ", ";", "•")
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
        val dashIdx = cleaned.indexOf(" - ").takeIf { it > 1 } ?: cleaned.indexOf(" – ").takeIf { it > 1 }
        if (dashIdx != null && dashIdx < cleaned.length - 3) {
            // Only strip "Artist - Title" prefix when suffix is NOT itself an edition tag like "- Remix" / "- Live"
            val suffix = cleaned.substring(dashIdx + 3).trim()
            val isEditionSuffix = Regex("^(remaster(ed)?|live|en vivo|acoustic|acustico|acustica|remix|radio edit|version|unplugged|slowed|sped up|official.*|audio.*|lyric.*)\\b")
                .containsMatchIn(suffix)
            if (isEditionSuffix) {
                cleaned = cleaned.substring(0, dashIdx)
            } else {
                cleaned = suffix
            }
        }
        cleaned = cleaned
            .replace(Regex("\\s+-\\s+(remaster(ed)?|live|en vivo|acoustic|acustico|acustica|remix|radio edit|version|unplugged|slowed|sped up|official.*|audio.*|lyric.*)$"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        var changed = true
        while (changed) {
            changed = false
            for (tok in AUDIO_EDITION_TOKENS + VIDEO_CLIP_TOKENS) {
                if (cleaned.endsWith(" $tok")) {
                    cleaned = cleaned.removeSuffix(" $tok").trim()
                    changed = true
                }
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
