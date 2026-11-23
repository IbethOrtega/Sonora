package com.example.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import com.example.data.local.SonoraPreferences
import com.example.model.AudioOutputRoute
import com.example.model.DeviceEqProfile
import com.example.model.EqPreset
import com.example.model.Track
import com.example.model.TransitionCurveType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private var savedProfilesJson: String = ""
    private var activeRoute: AudioOutputRoute = AudioOutputRoute.SPEAKER

    init {
        scope.launch {
            preferences.settingsFlow.collectLatest { settings ->
                eqEnabled = settings.eqEnabled
                eqPreset = settings.eqPreset
                bassStrength = settings.bassBoostStrength
                normalizeVolume = settings.normalizeVolume
                dolbyAtmosEnabled = settings.dolbyAtmosEnabled
                spatialAudioEnabled = settings.spatialAudioEnabled
                savedProfilesJson = settings.deviceEqProfilesJson
                applyCurrentSettings()
            }
        }
    }

    fun onAudioRouteChanged(newRoute: AudioOutputRoute) {
        if (newRoute == activeRoute) return
        val previousRoute = activeRoute
        activeRoute = newRoute
        SonoraPlaybackBridge.updateAudioRoute(newRoute)
        scope.launch {
            // Persist previous route's profile before switching
            preferences.saveDeviceEqProfile(
                previousRoute,
                DeviceEqProfile(
                    eqEnabled = eqEnabled,
                    presetName = eqPreset.name,
                    bassBoostStrength = bassStrength
                )
            )
            // Restore target route profile if saved previously
            val restored = SonoraPreferences.parseDeviceEqProfiles(savedProfilesJson)[newRoute.name]
            if (restored != null) {
                val restoredPreset = runCatching { EqPreset.valueOf(restored.presetName) }
                    .getOrDefault(EqPreset.FLAT)
                preferences.setEqEnabled(restored.eqEnabled)
                preferences.setEqPreset(restoredPreset)
                preferences.setBassBoostStrength(restored.bassBoostStrength)
            }
        }
    }

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        releaseEffects()
        currentSessionId = audioSessionId

        runCatching { equalizer = Equalizer(0, audioSessionId) }
        runCatching { bassBoost = BassBoost(0, audioSessionId) }
        runCatching { loudnessEnhancer = LoudnessEnhancer(audioSessionId) }
        runCatching { virtualizer = Virtualizer(0, audioSessionId) }
        applyCurrentSettings()
    }

    private fun applyCurrentSettings() {
        val atmosActive = dolbyAtmosEnabled
        val spatialActive = spatialAudioEnabled

        virtualizer?.let { virt ->
            runCatching {
                val active = atmosActive || spatialActive
                virt.enabled = active
                if (active && virt.strengthSupported) {
                    val targetStrength = when {
                        atmosActive && spatialActive -> 940
                        spatialActive -> 800
                        else -> 680
                    }
                    virt.setStrength(targetStrength.toShort())
                }
            }
        }

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
                    atmosActive -> 260
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
                        else -> 220
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
    val plan: AutoMixPlan,
    val incomingStartPositionMs: Long = plan.incomingStartPositionMs,
    val curveType: TransitionCurveType = plan.curveType
)

data class AutoMixPreCueRequest(
    val id: Long,
    val incomingTrack: Track,
    val startPositionMs: Long = 0L
)

data class LiveAudioTelemetry(
    val codec: String = "--",
    val sampleMimeType: String = "--",
    val bitrateBps: Int = 0,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val pcmEncoding: Int = 0,
    val audioSessionId: Int = 0,
    val bufferedMs: Long = 0L,
    val bufferPercent: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f
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

    private val _liveTelemetry = MutableStateFlow(LiveAudioTelemetry())
    val liveTelemetry = _liveTelemetry.asStateFlow()

    private val _activeAudioRoute = MutableStateFlow(AudioOutputRoute.SPEAKER)
    val activeAudioRoute = _activeAudioRoute.asStateFlow()

    private val _visualizerBars = MutableStateFlow(List(24) { 0f })
    val visualizerBars = _visualizerBars.asStateFlow()

    private val duckScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var duckRampJob: Job? = null

    fun setDucking(ducked: Boolean) {
        val target = if (ducked) 0.27f else 1.0f
        val start = _volumeMultiplier.value
        if (kotlin.math.abs(start - target) < 0.01f) {
            duckRampJob?.cancel()
            _volumeMultiplier.value = target
            return
        }
        duckRampJob?.cancel()
        duckRampJob = duckScope.launch {
            val steps = 15
            val stepDelayMs = 20L // 15 * 20ms = 300ms smooth ramp
            for (i in 1..steps) {
                val t = i.toFloat() / steps.toFloat()
                _volumeMultiplier.value = start + (target - start) * t
                delay(stepDelayMs)
            }
            _volumeMultiplier.value = target
        }
    }

    fun requestPreCueIncoming(track: Track, startPositionMs: Long = 0L) {
        _pendingPreCue.value = AutoMixPreCueRequest(
            id = System.nanoTime(),
            incomingTrack = track,
            startPositionMs = startPositionMs.coerceAtLeast(0L)
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

    fun updateLiveTelemetry(telemetry: LiveAudioTelemetry) {
        _liveTelemetry.value = telemetry
    }

    fun updateAudioRoute(route: AudioOutputRoute) {
        _activeAudioRoute.value = route
    }

    fun updateVisualizerBars(bars: List<Float>) {
        _visualizerBars.value = bars
    }
}
