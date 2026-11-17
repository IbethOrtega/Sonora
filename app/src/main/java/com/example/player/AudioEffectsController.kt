package com.example.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.example.data.local.SonoraPreferences
import com.example.model.EqPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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

    private var eqEnabled: Boolean = false
    private var eqPreset: EqPreset = EqPreset.FLAT
    private var bassStrength: Int = 0
    private var normalizeVolume: Boolean = false

    init {
        scope.launch {
            preferences.settingsFlow.collectLatest { settings ->
                eqEnabled = settings.eqEnabled
                eqPreset = settings.eqPreset
                bassStrength = settings.bassBoostStrength
                normalizeVolume = settings.normalizeVolume
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
        applyCurrentSettings()
    }

    private fun applyCurrentSettings() {
        equalizer?.let { eq ->
            runCatching {
                eq.enabled = eqEnabled
                if (eqEnabled) {
                    val bands = eq.numberOfBands.toInt().coerceAtLeast(1)
                    val range = eq.bandLevelRange
                    val minMb = range.getOrNull(0)?.toInt() ?: -1500
                    val maxMb = range.getOrNull(1)?.toInt() ?: 1500
                    val offsets = eqPreset.bandOffsetsDb
                    for (band in 0 until bands) {
                        val mappedIdx = ((band.toFloat() / bands.coerceAtLeast(1)) * offsets.size)
                            .toInt()
                            .coerceIn(0, offsets.lastIndex)
                        val dbStep = offsets[mappedIdx] // -6..+6
                        val targetMb = (dbStep * 180).coerceIn(minMb, maxMb)
                        eq.setBandLevel(band.toShort(), targetMb.toShort())
                    }
                }
            }
        }

        bassBoost?.let { bb ->
            runCatching {
                val active = eqEnabled && bassStrength > 0
                bb.enabled = active
                if (active && bb.strengthSupported) {
                    bb.setStrength(bassStrength.coerceIn(0, 1000).toShort())
                }
            }
        }

        loudnessEnhancer?.let { le ->
            runCatching {
                le.enabled = normalizeVolume
                if (normalizeVolume) {
                    le.setTargetGain(420) // +4.2 dB smart loudness lift
                }
            }
        }
    }

    fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudnessEnhancer?.release() }
        equalizer = null
        bassBoost = null
        loudnessEnhancer = null
        currentSessionId = 0
    }
}

object SonoraPlaybackBridge {
    private val _volumeMultiplier = MutableStateFlow(1.0f)
    val volumeMultiplier = _volumeMultiplier

    fun setDucking(ducked: Boolean) {
        _volumeMultiplier.value = if (ducked) 0.22f else 1.0f
    }
}
