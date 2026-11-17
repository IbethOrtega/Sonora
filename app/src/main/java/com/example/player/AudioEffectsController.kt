package com.example.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import com.example.data.local.SonoraPreferences
import com.example.model.EqPreset
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AudioEffectsController(
    private val preferences: SonoraPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentSessionId: Int = 0
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var virtualizer: Virtualizer? = null

    private var eqEnabled: Boolean = false
    private var eqPreset: EqPreset = EqPreset.FLAT
    private var bassStrength: Int = 0
    private var normalizeVolume: Boolean = false
    private var dolbyAtmosEnabled: Boolean = false
    private var spatialAudioEnabled: Boolean = false

    init {
        scope.launch {
            preferences.settingsFlow.collectLatest { settings ->
                eqEnabled = settings.eqEnabled
                eqPreset = settings.eqPreset
                bassStrength = settings.bassBoostStrength
                normalizeVolume = settings.normalizeVolume
                dolbyAtmosEnabled = settings.dolbyAtmosEnabled
                spatialAudioEnabled = settings.spatialAudioEnabled
                applyCurrentSettings()
            }
        }
    }

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        releaseEffects()
        currentSessionId = audioSessionId

        runCatching {
            equalizer = Equalizer(0, audioSessionId)
        }
        runCatching {
            bassBoost = BassBoost(0, audioSessionId)
        }
        runCatching {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
        }
        runCatching {
            virtualizer = Virtualizer(0, audioSessionId)
        }
        applyCurrentSettings()
    }

    private fun applyCurrentSettings() {
        val atmosActive = dolbyAtmosEnabled
        val spatialActive = spatialAudioEnabled

        // Virtualizer powers both Spatial Audio 360° soundstage widening & Dolby Atmos surround depth
        virtualizer?.let { virt ->
            runCatching {
                val active = atmosActive || spatialActive
                virt.enabled = active
                if (active && virt.strengthSupported) {
                    val targetStrength = when {
                        atmosActive && spatialActive -> 940
                        spatialActive -> 800
                        else -> 680 // Dolby Atmos cinematic soundstage
                    }
                    virt.setStrength(targetStrength.toShort())
                }
            }
        }

        // Equalizer: user EQ or subtle psychoacoustic Atmos atmospheric presence shaping
        equalizer?.let { eq ->
            runCatching {
                val shouldEnableEq = eqEnabled || atmosActive
                eq.enabled = shouldEnableEq
                if (shouldEnableEq) {
                    val bands = eq.numberOfBands.toInt().coerceAtLeast(1)
                    val range = eq.bandLevelRange
                    val minMb = range.getOrNull(0)?.toInt() ?: -1500
                    val maxMb = range.getOrNull(1)?.toInt() ?: 1500
                    val userOffsets = if (eqEnabled) eqPreset.bandOffsetsDb else listOf(0, 0, 0, 0, 0)
                    // Dolby Atmos subtle V/air harmonic warmth (+2 sub-bass, +1 mid-presence, +2 airy highs)
                    val atmosOffsets = if (atmosActive) listOf(2, 0, 1, 2, 3) else listOf(0, 0, 0, 0, 0)
                    for (band in 0 until bands) {
                        val mappedIdx = ((band.toFloat() / bands.coerceAtLeast(1)) * userOffsets.size)
                            .toInt()
                            .coerceIn(0, userOffsets.lastIndex)
                        val dbStep = (userOffsets[mappedIdx] + atmosOffsets[mappedIdx]).coerceIn(-6, 7)
                        val targetMb = (dbStep * 175).coerceIn(minMb, maxMb)
                        eq.setBandLevel(band.toShort(), targetMb.toShort())
                    }
                }
            }
        }

        bassBoost?.let { bb ->
            runCatching {
                val effectiveBass = when {
                    eqEnabled && bassStrength > 0 -> bassStrength
                    atmosActive -> 260 // Subtle Atmos sub-depth warmth
                    else -> 0
                }
                val active = effectiveBass > 0
                bb.enabled = active
                if (active && bb.strengthSupported) {
                    bb.setStrength(effectiveBass.coerceIn(0, 1000).toShort())
                }
            }
        }

        loudnessEnhancer?.let { le ->
            runCatching {
                val active = normalizeVolume || atmosActive
                le.enabled = active
                if (active) {
                    val targetGainMb = when {
                        normalizeVolume && atmosActive -> 480
                        normalizeVolume -> 420
                        else -> 220 // Gentle Atmos headroom lift
                    }
                    le.setTargetGain(targetGainMb)
                }
            }
        }
    }

    fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudnessEnhancer?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        loudnessEnhancer = null
        virtualizer = null
        currentSessionId = 0
    }
}

data class AutoMixTransitionRequest(
    val id: Long,
    val outgoingTrack: Track,
    val incomingTrack: Track,
    val nextQueueIndex: Int,
    val outgoingPositionMs: Long,
    val plan: AutoMixPlan
)

data class AutoMixPreCueRequest(
    val id: Long,
    val incomingTrack: Track
)

object SonoraPlaybackBridge {
    private val _volumeMultiplier = MutableStateFlow(1.0f)
    val volumeMultiplier = _volumeMultiplier.asStateFlow()

    private val _autoMixActive = MutableStateFlow(false)
    val autoMixActive = _autoMixActive.asStateFlow()

    private val _autoMixStatus = MutableStateFlow<String?>(null)
    val autoMixStatus = _autoMixStatus.asStateFlow()

    private val _pendingPreCue = MutableStateFlow<AutoMixPreCueRequest?>(null)
    val pendingPreCue = _pendingPreCue.asStateFlow()

    private val _pendingTransition = MutableStateFlow<AutoMixTransitionRequest?>(null)
    val pendingTransition = _pendingTransition.asStateFlow()

    fun setDucking(ducked: Boolean) {
        _volumeMultiplier.value = if (ducked) 0.22f else 1.0f
    }

    fun requestPreCueIncoming(track: Track) {
        _pendingPreCue.value = AutoMixPreCueRequest(
            id = System.nanoTime(),
            incomingTrack = track
        )
    }

    fun consumePreCue(requestId: Long) {
        if (_pendingPreCue.value?.id == requestId) {
            _pendingPreCue.value = null
        }
    }

    fun requestAutoMixTransition(request: AutoMixTransitionRequest) {
        _pendingTransition.value = request
    }

    fun consumePendingTransition(requestId: Long) {
        if (_pendingTransition.value?.id == requestId) {
            _pendingTransition.value = null
        }
    }

    fun updateAutoMixState(active: Boolean, status: String?) {
        _autoMixActive.value = active
        _autoMixStatus.value = status
    }
}
