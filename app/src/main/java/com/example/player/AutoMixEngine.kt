package com.example.player

import com.example.model.Track
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

data class AutoMixPlan(
    val outgoingVideoId: String,
    val incomingVideoId: String,
    val overlapMs: Long,
    val triggerBeforeEndMs: Long,
    val compatibilityScore: Float,
    val transitionStyleLabel: String
)

enum class TrackEnergyProfile(val estimatedBpm: Int, val energyLevel: Float) {
    AMBIENT_CHILL(84, 0.28f),
    MID_GROOVE(104, 0.56f),
    UPBEAT_POP(122, 0.78f),
    HIGH_ENERGY_CLUB(134, 0.94f)
}

/**
 * Pure transition calculator & harmonic queue curator for Sonora AutoMix.
 * Analyzes track metadata, tempo/energy cues, and durations to calculate seamless
 * equal-power crossfade windows between songs.
 */
object AutoMixEngine {

    fun classifyEnergyProfile(track: Track): TrackEnergyProfile {
        val text = "${track.title} ${track.artist} ${track.album.orEmpty()}".lowercase()
        val durationSec = (track.durationMs / 1000L).toInt()

        val chillKeywords = listOf(
            "acoustic", "acústic", "lofi", "lo-fi", "chill", "ambient", "piano",
            "sleep", "relax", "ballad", "balada", "unplugged", "lento", "nocturn"
        )
        val clubKeywords = listOf(
            "remix", "club", "dj", "edm", "techno", "house", "dance", "trap",
            "reggaeton", "perreo", "workout", "gym", "phonk", "turbo", "elektron", "electronic"
        )
        val upbeatKeywords = listOf(
            "pop", "rock", "funk", "disco", "salsa", "cumbia", "live", "vivo",
            "party", "fiesta", "hits", "summer", "verano", "upbeat"
        )

        return when {
            clubKeywords.any { text.contains(it) } -> TrackEnergyProfile.HIGH_ENERGY_CLUB
            chillKeywords.any { text.contains(it) } -> TrackEnergyProfile.AMBIENT_CHILL
            upbeatKeywords.any { text.contains(it) } -> TrackEnergyProfile.UPBEAT_POP
            durationSec in 1..175 -> TrackEnergyProfile.UPBEAT_POP
            durationSec >= 290 -> TrackEnergyProfile.AMBIENT_CHILL
            else -> TrackEnergyProfile.MID_GROOVE
        }
    }

    /**
     * Computes the exact overlap window and transition point based on both songs
     * and the user's configured overlap duration (0 = Automatic analysis, 2..12s = Manual).
     */
    fun computeTransition(
        currentTrack: Track,
        nextTrack: Track,
        measuredDurationMs: Long,
        configuredOverlapSec: Int = 0
    ): AutoMixPlan {
        val safeDurationMs = measuredDurationMs.takeIf { it > 0L } ?: currentTrack.durationMs
        val outProfile = classifyEnergyProfile(currentTrack)
        val inProfile = classifyEnergyProfile(nextTrack)

        val sameArtist = currentTrack.artist.isNotBlank() &&
            nextTrack.artist.isNotBlank() &&
            (currentTrack.artist.contains(nextTrack.artist, ignoreCase = true) ||
                nextTrack.artist.contains(currentTrack.artist, ignoreCase = true))

        val bpmDelta = abs(outProfile.estimatedBpm - inProfile.estimatedBpm)
        val energyDelta = abs(outProfile.energyLevel - inProfile.energyLevel)

        // Higher score = closer energy/BPM match -> smoother, slightly longer blend
        val baseCompat = (1f - (bpmDelta / 65f) * 0.55f - energyDelta * 0.45f).coerceIn(0.35f, 1f)
        val compatScore = (baseCompat + if (sameArtist) 0.12f else 0f).coerceIn(0.4f, 1f)

        val isManual = configuredOverlapSec > 0
        val desiredOverlapMs = if (isManual) {
            configuredOverlapSec.coerceIn(2, 12) * 1_000L
        } else {
            when {
                outProfile == TrackEnergyProfile.HIGH_ENERGY_CLUB && inProfile == TrackEnergyProfile.HIGH_ENERGY_CLUB -> 5_200L
                outProfile == TrackEnergyProfile.AMBIENT_CHILL || inProfile == TrackEnergyProfile.AMBIENT_CHILL -> 7_800L
                compatScore >= 0.84f -> 6_800L
                compatScore >= 0.65f -> 5_600L
                else -> 4_200L
            }
        }

        // Cap overlap safely so short tracks still keep their main body intact
        val maxRatio = if (isManual) 0.28 else 0.18
        val maxAllowedOverlap = if (safeDurationMs > 22_000L) {
            (safeDurationMs * maxRatio).roundToLong().coerceIn(2_000L, 12_500L)
        } else {
            2_000L
        }
        val overlapMs = desiredOverlapMs.coerceAtMost(maxAllowedOverlap)

        val triggerBeforeEndMs = overlapMs.coerceAtMost((safeDurationMs * 0.30).roundToLong())

        val secStr = String.format(java.util.Locale.US, "%.1fs", overlapMs / 1000f)
        val label = when {
            isManual -> "Cruce manual • $secStr"
            sameArtist -> "Mezcla continua • $secStr"
            compatScore >= 0.82f -> "Mezcla armónica • $secStr"
            outProfile == TrackEnergyProfile.HIGH_ENERGY_CLUB -> "Cruce rítmico • $secStr"
            else -> "Transición suave • $secStr"
        }

        return AutoMixPlan(
            outgoingVideoId = currentTrack.videoId,
            incomingVideoId = nextTrack.videoId,
            overlapMs = overlapMs,
            triggerBeforeEndMs = triggerBeforeEndMs,
            compatibilityScore = compatScore,
            transitionStyleLabel = label
        )
    }

    /**
     * Equal-power crossfade gains (cos / sin curve) preserving perceived loudness
     * through the middle of the transition without a volume dip.
     */
    fun equalPowerGains(progress: Float): Pair<Float, Float> {
        val t = progress.coerceIn(0f, 1f).toDouble()
        val angle = t * (PI / 2.0)
        val outgoingGain = cos(angle).toFloat().coerceIn(0f, 1f)
        val incomingGain = sin(angle).toFloat().coerceIn(0f, 1f)
        return outgoingGain to incomingGain
    }
}
