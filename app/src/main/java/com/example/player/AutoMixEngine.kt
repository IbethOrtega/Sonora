package com.example.player

import com.example.model.PlaylistTransitionSpec
import com.example.model.Track
import com.example.model.TransitionCurveType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

data class AutoMixPlan(
    val outgoingVideoId: String,
    val incomingVideoId: String,
    val overlapMs: Long,
    val triggerBeforeEndMs: Long,
    val compatibilityScore: Float,
    val transitionStyleLabel: String,
    val triggerAtPositionMs: Long? = null,
    val incomingStartPositionMs: Long = 0L,
    val curveType: TransitionCurveType = TransitionCurveType.SMOOTH_FADE
)

enum class TrackEnergyProfile(val estimatedBpm: Int, val energyLevel: Float) {
    AMBIENT_CHILL(84, 0.28f),
    MID_GROOVE(104, 0.56f),
    UPBEAT_POP(122, 0.78f),
    HIGH_ENERGY_CLUB(134, 0.94f)
}

/**
 * Pure transition calculator & harmonic queue curator for Sonora AutoMix,
 * Standard Crossfade (1..12s / Auto), and Playlist Fragment Mixer transitions.
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
     * Computes transition timing supporting:
     * 1) Custom Playlist Fragment Transition (`customSpec`)
     * 2) Standard Crossfade (`crossfadeSeconds`: >0 manual seconds, -1 auto)
     * 3) Smart AutoMix (`autoMixOverlapSeconds`: 0 auto, 2..12s manual)
     */
    fun computeTransition(
        currentTrack: Track,
        nextTrack: Track,
        measuredDurationMs: Long,
        configuredOverlapSec: Int = 0,
        crossfadeSeconds: Int = 0,
        customSpec: PlaylistTransitionSpec? = null
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

        val baseCompat = (1f - (bpmDelta / 65f) * 0.55f - energyDelta * 0.45f).coerceIn(0.35f, 1f)
        val compatScore = (baseCompat + if (sameArtist) 0.12f else 0f).coerceIn(0.4f, 1f)

        if (customSpec != null) {
            val specOverlap = customSpec.crossfadeMs.toLong().coerceIn(1_500L, 16_000L)
            val exitMs = customSpec.fromExitMs.takeIf { it > 2_000L }
            val triggerPos = exitMs?.let { (it - specOverlap).coerceAtLeast(1_000L) }
            val triggerBeforeEnd = if (exitMs != null && safeDurationMs > exitMs) {
                (safeDurationMs - triggerPos!!).coerceAtLeast(specOverlap)
            } else {
                specOverlap.coerceAtMost((safeDurationMs * 0.35).roundToLong().coerceAtLeast(1_500L))
            }
            val secStr = String.format(java.util.Locale.US, "%.1fs", specOverlap / 1000f)
            return AutoMixPlan(
                outgoingVideoId = currentTrack.videoId,
                incomingVideoId = nextTrack.videoId,
                overlapMs = specOverlap,
                triggerBeforeEndMs = triggerBeforeEnd,
                compatibilityScore = compatScore,
                transitionStyleLabel = "Mix fragmento • ${customSpec.curveType.label} ($secStr)",
                triggerAtPositionMs = triggerPos,
                incomingStartPositionMs = customSpec.toEntryMs.coerceAtLeast(0L),
                curveType = customSpec.curveType
            )
        }

        // Determine desired overlap: AutoMix manual/auto takes priority when called for AutoMix;
        // standalone Crossfade uses exact crossfadeSeconds (or -1 for Smart Crossfade).
        val effectiveManualSec = when {
            configuredOverlapSec > 0 -> configuredOverlapSec
            crossfadeSeconds > 0 -> crossfadeSeconds
            else -> 0
        }
        val isManual = effectiveManualSec > 0

        val desiredOverlapMs = when {
            isManual -> effectiveManualSec.coerceIn(1, 12) * 1_000L
            crossfadeSeconds < 0 -> { // Auto Crossfade (-1)
                when {
                    compatScore >= 0.84f -> 5_800L
                    compatScore >= 0.65f -> 4_500L
                    else -> 3_400L
                }
            }
            else -> {
                when {
                    outProfile == TrackEnergyProfile.HIGH_ENERGY_CLUB && inProfile == TrackEnergyProfile.HIGH_ENERGY_CLUB -> 5_200L
                    outProfile == TrackEnergyProfile.AMBIENT_CHILL || inProfile == TrackEnergyProfile.AMBIENT_CHILL -> 7_800L
                    compatScore >= 0.84f -> 6_800L
                    compatScore >= 0.65f -> 5_600L
                    else -> 4_200L
                }
            }
        }

        val maxRatio = when {
            isManual -> 0.28
            safeDurationMs <= 45_000L -> 0.125
            else -> 0.18
        }
        val maxAllowedOverlap = if (safeDurationMs > 18_000L) {
            (safeDurationMs * maxRatio).roundToLong().coerceIn(1_200L, 12_500L)
        } else {
            1_500L
        }
        val overlapMs = desiredOverlapMs.coerceAtMost(maxAllowedOverlap)
        val triggerBeforeEndMs = overlapMs.coerceAtMost((safeDurationMs * 0.30).roundToLong().coerceAtLeast(1_000L))

        val secStr = String.format(java.util.Locale.US, "%.1fs", overlapMs / 1000f)
        val label = when {
            crossfadeSeconds != 0 && configuredOverlapSec == 0 -> {
                if (crossfadeSeconds < 0) "Fundido auto • $secStr" else "Fundido • $secStr"
            }
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
     * Curve-aware crossfade gain calculator preserving perceived loudness.
     */
    fun curveGains(
        progress: Float,
        curveType: TransitionCurveType = TransitionCurveType.SMOOTH_FADE
    ): Pair<Float, Float> {
        val t = progress.coerceIn(0f, 1f).toDouble()
        return when (curveType) {
            TransitionCurveType.SMOOTH_FADE -> equalPowerGains(t.toFloat())
            TransitionCurveType.BEAT_CUT -> {
                // Quick S-punch around midpoint so incoming drop hits strongly without overlap mud
                val shaped = if (t < 0.5) {
                    (2.0 * t).pow(2.4) / 2.0
                } else {
                    1.0 - (2.0 * (1.0 - t)).pow(2.4) / 2.0
                }
                val angle = shaped * (PI / 2.0)
                cos(angle).toFloat().coerceIn(0f, 1f) to sin(angle).toFloat().coerceIn(0f, 1f)
            }
            TransitionCurveType.EQ_BASS_SWAP -> {
                // Slightly holds outgoing clarity slightly longer before rapid mid-point handoff
                val outG = cos((t.pow(1.25)) * (PI / 2.0)).toFloat().coerceIn(0f, 1f)
                val inG = sin((t.pow(0.82)) * (PI / 2.0)).toFloat().coerceIn(0f, 1f)
                outG to inG
            }
        }
    }

    fun equalPowerGains(progress: Float): Pair<Float, Float> {
        val t = progress.coerceIn(0f, 1f).toDouble()
        val angle = t * (PI / 2.0)
        val outgoingGain = cos(angle).toFloat().coerceIn(0f, 1f)
        val incomingGain = sin(angle).toFloat().coerceIn(0f, 1f)
        return outgoingGain to incomingGain
    }
}
