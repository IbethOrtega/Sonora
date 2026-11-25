package com.example.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.data.ai.AiDjIntentRepository
import com.example.data.ai.DjVoiceSynthesizer
import com.example.data.ai.FirebaseAiVoiceSynthesizer
import com.example.data.ai.NoOpDjVoiceSynthesizer
import com.example.data.ai.PlaceholderAiDjIntentRepository
import com.example.data.ai.SynthesizedSpeech
import com.example.data.local.SonoraPreferences
import com.example.data.repository.MusicRepository
import com.example.model.DjVibe
import com.example.model.DjVoiceCatalog
import com.example.model.SearchFilter
import com.example.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.UUID

enum class DjVoiceEngine {
    GEMINI,
    DEVICE
}

data class DeviceTtsVoiceInfo(
    val name: String,
    val localeTag: String,
    val quality: Int,
    val isNetworkRequired: Boolean,
    val displayLabel: String
) {
    val qualityLabel: String
        get() = if (quality >= Voice.QUALITY_HIGH) "Alta" else "Normal"

    val networkLabel: String
        get() = if (isNetworkRequired) "Red" else "Sin conexión"
}

data class DjLiveState(
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val isSpeaking: Boolean = false,
    val currentVibe: DjVibe = DjVibe.PERSONAL_MIX,
    val spokenCaption: String = "",
    val segmentTitle: String = "DJ Sony",
    val segmentStartIndices: List<Int> = emptyList(),
    val lastVoicePrompt: String = "",
    val aiNotice: String? = null,
    val aiError: String? = null,
    val aiTechnicalDetail: String? = null,
    val voiceEngine: DjVoiceEngine = DjVoiceEngine.GEMINI,
    val lastSpokenModel: String? = null,
    val lastSpokenVoice: String? = null,
    val lastEngineDetail: String? = null,
    val lastPreviewFeedback: String? = null
)

class DjLiveController(
    private val context: Context,
    private val repository: MusicRepository,
    private val playerController: PlayerController,
    private val aiDjIntentRepository: AiDjIntentRepository = PlaceholderAiDjIntentRepository(),
    private val djVoiceSynthesizer: DjVoiceSynthesizer = NoOpDjVoiceSynthesizer(),
    private val preferences: SonoraPreferences? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(DjLiveState())
    val state: StateFlow<DjLiveState> = _state.asStateFlow()

    private val _availableDeviceSpanishVoices = MutableStateFlow<List<DeviceTtsVoiceInfo>>(emptyList())
    val availableDeviceSpanishVoices: StateFlow<List<DeviceTtsVoiceInfo>> = _availableDeviceSpanishVoices.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var pendingUtterance: String? = null
    private var duckWatchdogJob: Job? = null
    private var speechJob: Job? = null
    private var preSynthTransitionJob: Job? = null
    private var preSynthTargetVideoId: String? = null
    private var startDjJob: Job? = null
    private var lastAnnouncedTrackId: String? = null
    private var blockTransitionIndices: Set<Int> = emptySet()

    @Volatile
    private var selectedVoiceName: String = FirebaseAiVoiceSynthesizer.DEFAULT_VOICE_NAME

    @Volatile
    private var selectedDeviceVoiceName: String = ""

    @Volatile
    private var deviceSpeechRate: Float = 1.0f

    @Volatile
    private var devicePitch: Float = 1.03f

    private val clipCacheLock = Any()
    private val speechClipCache = object : LinkedHashMap<String, SynthesizedSpeech>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SynthesizedSpeech>?): Boolean {
            return size > 3
        }
    }
    private val precomputedTransitionScripts = LinkedHashMap<String, String>()

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    init {
        observeVoicePreference()
        initFallbackTts()
        observePlayerTransitions()
    }

    private fun observeVoicePreference() {
        val prefs = preferences ?: return
        scope.launch {
            prefs.settingsFlow.collect { settings ->
                selectedVoiceName = DjVoiceCatalog.normalizeOrDefault(settings.djVoiceName)
                selectedDeviceVoiceName = settings.djDeviceVoiceName.trim()
                deviceSpeechRate = settings.djDeviceSpeechRate.coerceIn(0.7f, 1.4f)
                devicePitch = settings.djDevicePitch.coerceIn(0.7f, 1.4f)
                if (ttsReady) {
                    tts?.let { applyDeviceVoiceSettings(it) }
                }
            }
        }
    }

    private fun initFallbackTts() {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    val spanishMx = Locale("es", "MX")
                    val spanishUs = Locale("es", "US")
                    val spanishEs = Locale("es", "ES")
                    val res = when {
                        engine.isLanguageAvailable(spanishUs) >= TextToSpeech.LANG_AVAILABLE ->
                            engine.setLanguage(spanishUs)
                        engine.isLanguageAvailable(spanishMx) >= TextToSpeech.LANG_AVAILABLE ->
                            engine.setLanguage(spanishMx)
                        engine.isLanguageAvailable(spanishEs) >= TextToSpeech.LANG_AVAILABLE ->
                            engine.setLanguage(spanishEs)
                        else -> engine.setLanguage(Locale.getDefault())
                    }
                    refreshAvailableDeviceVoices(engine)
                    applyDeviceVoiceSettings(engine)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            scope.launch {
                                SonoraPlaybackBridge.setDucking(true)
                                _state.update { it.copy(isSpeaking = true) }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            scope.launch { finishSpeaking() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            scope.launch { finishSpeaking() }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            scope.launch { finishSpeaking() }
                        }
                    })
                    ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                    val queued = pendingUtterance
                    if (ttsReady && queued != null && _state.value.isActive) {
                        pendingUtterance = null
                        speakWithDeviceTts(queued)
                    }
                }
            }
        }
    }

    private fun refreshAvailableDeviceVoices(engine: TextToSpeech) {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val spanish = voices
            .filter { it.locale.language.equals("es", ignoreCase = true) }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { it.name }
            )
            .map { voice ->
                val localeLabel = voice.locale.toLanguageTag().ifBlank { "es" }
                val shortCode = voice.name
                    .removePrefix("es-")
                    .removePrefix("es_")
                    .take(22)
                val qLabel = if (voice.quality >= Voice.QUALITY_HIGH) "Alta" else "Normal"
                val netLabel = if (voice.isNetworkConnectionRequired) "Red" else "Local"
                DeviceTtsVoiceInfo(
                    name = voice.name,
                    localeTag = localeLabel,
                    quality = voice.quality,
                    isNetworkRequired = voice.isNetworkConnectionRequired,
                    displayLabel = "$shortCode ($localeLabel • $qLabel • $netLabel)"
                )
            }
        _availableDeviceSpanishVoices.value = spanish
    }

    private fun applyDeviceVoiceSettings(engine: TextToSpeech) {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val explicit = selectedDeviceVoiceName
            .takeIf { it.isNotBlank() }
            ?.let { target -> voices.firstOrNull { it.name == target } }
        val resolved = explicit ?: selectBestSpanishVoice(engine)
        if (resolved != null) {
            runCatching { engine.voice = resolved }
        }
        runCatching {
            engine.setSpeechRate(deviceSpeechRate)
            engine.setPitch(devicePitch)
        }
    }

    private fun activeDeviceVoiceLabel(): String {
        val matched = _availableDeviceSpanishVoices.value
            .firstOrNull { it.name == selectedDeviceVoiceName }
        if (matched != null) return matched.displayLabel
        val current = runCatching { tts?.voice?.name }.getOrNull().orEmpty()
        if (current.isNotBlank()) {
            val fromCatalog = _availableDeviceSpanishVoices.value.firstOrNull { it.name == current }
            return fromCatalog?.displayLabel ?: current
        }
        return "Voz española del teléfono"
    }

    private fun selectBestSpanishVoice(engine: TextToSpeech): Voice? {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        return voices
            .filter { voice ->
                voice.locale.language.equals("es", ignoreCase = true) &&
                    voice.latency <= Voice.LATENCY_HIGH
            }
            .maxByOrNull { voice ->
                var score = voice.quality
                val name = voice.name.lowercase()
                val country = voice.locale.country.uppercase()
                if ("google" in name) score += 120
                if (!voice.isNetworkConnectionRequired) score += 100
                if (country == "MX" || country == "US" || country == "419") score += 70
                if ("neural" in name || "wavenet" in name || "natural" in name || "studio" in name) score += 260
                if ("female" in name || "sfb" in name || "esa" in name || "esb" in name) score += 30
                score
            }
    }

    private fun observePlayerTransitions() {
        scope.launch {
            playerController.state.collect { playback ->
                if (!_state.value.isActive) return@collect
                val track = playback.currentTrack ?: return@collect
                val idx = playback.currentIndex
                val vibe = _state.value.currentVibe

                if (track.videoId != lastAnnouncedTrackId && idx in blockTransitionIndices) {
                    lastAnnouncedTrackId = track.videoId
                    val line = precomputedTransitionScripts.remove(track.videoId)
                        ?: buildTransitionScript(vibe, track)
                    speakScript(line, segmentTitle = "${vibe.label} • DJ Sony")
                }

                // Pre-generate next transition clip during current song so playback has zero latency
                val nextIdx = idx + 1
                val upcomingTrack = if (nextIdx in blockTransitionIndices) {
                    playback.queue.getOrNull(nextIdx)
                } else {
                    null
                }
                if (upcomingTrack != null && upcomingTrack.videoId != preSynthTargetVideoId) {
                    preSynthTargetVideoId = upcomingTrack.videoId
                    val upcomingScript = precomputedTransitionScripts.getOrPut(upcomingTrack.videoId) {
                        buildTransitionScript(vibe, upcomingTrack)
                    }
                    val voiceForClip = selectedVoiceName
                    preSynthTransitionJob?.cancel()
                    preSynthTransitionJob = scope.launch(Dispatchers.IO) {
                        synthesizeWithClipCache(upcomingScript, voiceForClip)
                    }
                }
            }
        }
    }

    fun startDjLive(vibe: DjVibe = _state.value.currentVibe) {
        startDjJob?.cancel()
        speechJob?.cancel()
        stopActiveAudioTrack()
        runCatching { tts?.stop() }
        finishSpeaking()
        startDjJob = scope.launch {
            _state.update {
                it.copy(
                    isActive = true,
                    isLoading = true,
                    currentVibe = vibe,
                    segmentTitle = vibe.label,
                    spokenCaption = "Preparando tu sesión de DJ Sony...",
                    aiNotice = null,
                    aiError = null,
                    aiTechnicalDetail = null
                )
            }

            val liked = runCatching { repository.observeLikedSongs().first() }.getOrDefault(emptyList())
            val mostPlayed = runCatching { repository.observeMostPlayedTracks().first() }.getOrDefault(emptyList())
            val recent = runCatching { repository.observeRecentTracks().first() }.getOrDefault(emptyList())

            val seeds = (mostPlayed.take(4) + liked.take(4) + recent.take(4)).distinctBy { it.videoId }
            val personalized = runCatching {
                if (seeds.isNotEmpty()) {
                    repository.getPersonalizedRecommendations(
                        seedTracks = recent.ifEmpty { seeds },
                        mostPlayedTracks = mostPlayed,
                        likedTracks = liked
                    ).flatMap { it.tracks }
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())

            val homeShelves = if (seeds.isEmpty() || vibe == DjVibe.DISCOVERY) {
                runCatching {
                    repository.getHomeShelves().getOrDefault(emptyList())
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val homeTracks = homeShelves.flatMap { it.tracks }.toMutableList()
            if (homeTracks.size < 6 && seeds.isEmpty()) {
                val firstCard = homeShelves.flatMap { it.items }.firstOrNull { it.id.isNotBlank() && it.videoId == null }
                if (firstCard != null) {
                    runCatching {
                        repository.getBrowseDetail(firstCard.id, firstCard.params).getOrNull()?.tracks.orEmpty()
                    }.getOrDefault(emptyList()).let { homeTracks += it }
                }
            }
            val coldStartSearch = if (seeds.isEmpty() && homeTracks.size < 6) {
                val vibeQuery = when (vibe) {
                    DjVibe.PERSONAL_MIX -> "top music hits"
                    DjVibe.ENERGY -> "workout dance hits"
                    DjVibe.CHILL -> "chill lofi acoustic"
                    DjVibe.DISCOVERY -> "indie pop alternativos hits"
                }
                runCatching {
                    repository.search(vibeQuery, SearchFilter.SONGS).getOrNull()?.songs.orEmpty()
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val homeFallback = (homeTracks + coldStartSearch).distinctBy { it.videoId }

            val seedForRadio = when (vibe) {
                DjVibe.PERSONAL_MIX -> mostPlayed.firstOrNull() ?: liked.firstOrNull() ?: recent.firstOrNull() ?: homeFallback.firstOrNull()
                DjVibe.ENERGY -> mostPlayed.getOrNull(1) ?: homeFallback.firstOrNull() ?: seeds.firstOrNull()
                DjVibe.CHILL -> liked.lastOrNull() ?: recent.lastOrNull() ?: homeFallback.firstOrNull() ?: seeds.firstOrNull()
                DjVibe.DISCOVERY -> personalized.firstOrNull() ?: homeFallback.firstOrNull() ?: seeds.firstOrNull()
            }

            val radioUpNext = if (seedForRadio != null) {
                runCatching { repository.getUpNextAndLyricsId(seedForRadio.videoId).first }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val curatedPool = curateMixQueue(
                vibe = vibe,
                liked = liked,
                mostPlayed = mostPlayed,
                recent = recent,
                personalized = personalized,
                radio = radioUpNext,
                homeFallback = homeFallback
            )

            if (curatedPool.isEmpty()) {
                val msg = "No pude cargar canciones para DJ Sony. Verifica tu conexión o reproduce un tema."
                _state.update {
                    it.copy(
                        isActive = false,
                        isLoading = false,
                        aiError = msg,
                        spokenCaption = msg
                    )
                }
                return@launch
            }

            val checkpoints = listOf(3, 7, 11, 15).filter { it < curatedPool.size }.toSet()
            blockTransitionIndices = checkpoints
            val leadTrack = curatedPool.first()
            lastAnnouncedTrackId = leadTrack.videoId

            val greeting = buildOpeningScript(vibe, leadTrack, liked.size, mostPlayed.isNotEmpty())
            val voiceForSession = selectedVoiceName
            val preSynthSpeech = async { synthesizeWithClipCache(greeting, voiceForSession) }

            _state.update {
                it.copy(
                    isActive = true,
                    isLoading = false,
                    currentVibe = vibe,
                    segmentTitle = vibe.label,
                    spokenCaption = greeting,
                    segmentStartIndices = checkpoints.toList(),
                    aiNotice = null,
                    aiError = null,
                    aiTechnicalDetail = null
                )
            }

            playerController.playQueue(curatedPool, 0)
            speakScript(
                text = greeting,
                segmentTitle = vibe.label,
                preSynthesized = preSynthSpeech
            )
        }
    }

    fun startVoiceDjMix(spokenPrompt: String) {
        val cleaned = spokenPrompt.trim()
        if (cleaned.isBlank()) return
        val effectivePrompt = stripWakeWords(cleaned)
        startDjJob?.cancel()
        speechJob?.cancel()
        stopActiveAudioTrack()
        runCatching { tts?.stop() }
        finishSpeaking()
        startDjJob = scope.launch {
            _state.update {
                it.copy(
                    isActive = true,
                    isLoading = true,
                    lastVoicePrompt = cleaned,
                    segmentTitle = "DJ Sony",
                    spokenCaption = "DJ Sony está armando: \"$effectivePrompt\"...",
                    aiNotice = null,
                    aiError = null,
                    aiTechnicalDetail = null
                )
            }

            var technicalDetail: String? = null
            val plan = try {
                aiDjIntentRepository.planVoiceMix(effectivePrompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SonoraDJ", "AI intent planning failed, activating local fallback", e)
                technicalDetail = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: e::class.java.simpleName
                PlaceholderAiDjIntentRepository.parsePromptLocally(effectivePrompt).copy(
                    notice = "DJ Sony sin IA: usando búsqueda directa"
                )
            }
            val resolvedNotice = if (technicalDetail != null) {
                "DJ Sony sin IA: usando búsqueda directa"
            } else {
                plan.notice
            }

            // Start synthesizing the human DJ speech in parallel while fetching the mix songs.
            val voiceForSession = selectedVoiceName
            val preSynthSpeech = async { synthesizeWithClipCache(plan.djReply, voiceForSession) }

            val parallelSongResults = coroutineScope {
                plan.searchQueries.map { query ->
                    async {
                        runCatching {
                            repository.search(query, SearchFilter.SONGS).getOrNull()?.songs.orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            val gathered = parallelSongResults
                .flatMap { MusicRepository.filterRelatedCleanTracks(null, it).take(8) }
                .distinctBy { it.videoId }
                .toMutableList()

            // Fallback to SearchFilter.ALL if targeted SONGS search returned too few tracks
            if (gathered.size < 4) {
                val fallbackQueries = listOfNotNull(
                    cleaned,
                    plan.searchQueries.firstOrNull()
                ).distinct()
                val allResults = coroutineScope {
                    fallbackQueries.map { q ->
                        async {
                            runCatching {
                                repository.search(q, SearchFilter.ALL).getOrNull()
                            }.getOrNull()
                        }
                    }.awaitAll()
                }
                val allSongs = allResults.filterNotNull().flatMap { it.songs }
                gathered += allSongs
                if (gathered.size < 4) {
                    val playlistCard = allResults.filterNotNull().flatMap { it.playlists }.firstOrNull()
                    if (playlistCard != null) {
                        val plTracks = runCatching {
                            repository.getBrowseDetail(playlistCard.id, playlistCard.params).getOrNull()?.tracks.orEmpty()
                        }.getOrDefault(emptyList())
                        gathered += plTracks.take(12)
                    }
                }
            }

            val leadSeed = gathered.firstOrNull()
            val upNextTracks = if (leadSeed != null) {
                runCatching { repository.getUpNextAndLyricsId(leadSeed.videoId).first }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val curatedPool = curateVoiceMixPool(
                gathered = gathered,
                upNextTracks = upNextTracks,
                limit = 24
            )

            if (curatedPool.isEmpty()) {
                preSynthSpeech.cancel()
                val emptyError = "No encontré canciones para \"$effectivePrompt\". Intenta con otro artista, canción o género."
                _state.update {
                    it.copy(
                        isActive = false,
                        isLoading = false,
                        aiNotice = resolvedNotice,
                        aiError = emptyError,
                        aiTechnicalDetail = technicalDetail,
                        spokenCaption = emptyError
                    )
                }
                return@launch
            }

            val checkpoints = listOf(3, 7, 11, 15).filter { it < curatedPool.size }.toSet()
            blockTransitionIndices = checkpoints
            lastAnnouncedTrackId = curatedPool.first().videoId

            _state.update {
                it.copy(
                    isActive = true,
                    isLoading = false,
                    segmentTitle = plan.vibeTitle,
                    spokenCaption = plan.djReply,
                    segmentStartIndices = checkpoints.toList(),
                    lastVoicePrompt = cleaned,
                    aiNotice = resolvedNotice,
                    aiError = null,
                    aiTechnicalDetail = technicalDetail
                )
            }

            playerController.playQueue(curatedPool, 0)
            speakScript(
                text = plan.djReply,
                segmentTitle = plan.vibeTitle,
                preSynthesized = preSynthSpeech
            )
        }
    }

    fun switchVibe() {
        val entries = DjVibe.entries
        val nextIdx = (entries.indexOf(_state.value.currentVibe) + 1) % entries.size
        startDjLive(entries[nextIdx])
    }

    fun stopDjLive() {
        startDjJob?.cancel()
        speechJob?.cancel()
        preSynthTransitionJob?.cancel()
        preSynthTargetVideoId = null
        precomputedTransitionScripts.clear()
        blockTransitionIndices = emptySet()
        lastAnnouncedTrackId = null
        pendingUtterance = null
        stopActiveAudioTrack()
        runCatching { tts?.stop() }
        finishSpeaking()
        _state.update {
            it.copy(
                isActive = false,
                isLoading = false,
                isSpeaking = false,
                spokenCaption = ""
            )
        }
    }

    fun previewVoice(voiceName: String) {
        val resolvedVoice = DjVoiceCatalog.normalizeOrDefault(voiceName)
        speechJob?.cancel()
        stopActiveAudioTrack()
        runCatching { tts?.stop() }
        finishSpeaking()

        speechJob = scope.launch {
            val previewScript = "Hola, soy DJ Sony. Esta es mi voz, ¿te gusta?"
            _state.update { it.copy(isSpeaking = true, lastPreviewFeedback = "Probando voz...") }
            val neuralSpeech = try {
                synthesizeWithClipCache(previewScript, resolvedVoice)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (neuralSpeech != null && neuralSpeech.pcmBytes.isNotEmpty()) {
                val modelLabel = neuralSpeech.modelName.ifBlank { "Gemini TTS" }
                val actualVoice = neuralSpeech.voiceName.ifBlank { resolvedVoice }
                val detail = "${neuralSpeech.engineLabel} ($modelLabel • Voz: $actualVoice)"
                _state.update {
                    it.copy(
                        voiceEngine = DjVoiceEngine.GEMINI,
                        isSpeaking = true,
                        lastSpokenModel = modelLabel,
                        lastSpokenVoice = actualVoice,
                        lastEngineDetail = detail,
                        lastPreviewFeedback = "Habló con $detail"
                    )
                }
                SonoraPlaybackBridge.setDucking(true)
                try {
                    playPcmSpeech(neuralSpeech)
                } finally {
                    finishSpeaking()
                }
            } else {
                val reason = djVoiceSynthesizer.lastSynthesisReport ?: "Gemini no devolvió audio"
                val deviceVoice = activeDeviceVoiceLabel()
                if (ttsReady) {
                    val detail = "Teléfono ($deviceVoice • motivo respaldo: $reason)"
                    _state.update {
                        it.copy(
                            voiceEngine = DjVoiceEngine.DEVICE,
                            lastSpokenModel = "Android TTS",
                            lastSpokenVoice = deviceVoice,
                            lastEngineDetail = detail,
                            aiTechnicalDetail = reason,
                            lastPreviewFeedback = "Habló con Teléfono ($deviceVoice)"
                        )
                    }
                    speakWithDeviceTts(previewScript)
                } else {
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            voiceEngine = DjVoiceEngine.DEVICE,
                            lastSpokenModel = "Android TTS",
                            lastSpokenVoice = deviceVoice,
                            lastEngineDetail = "Teléfono no disponible • $reason",
                            aiTechnicalDetail = reason,
                            lastPreviewFeedback = "No se pudo reproducir la voz ($reason)"
                        )
                    }
                }
            }
        }
    }

    fun previewDeviceVoice() {
        speechJob?.cancel()
        stopActiveAudioTrack()
        runCatching { tts?.stop() }
        finishSpeaking()

        val engine = tts
        if (engine == null || !ttsReady) {
            _state.update {
                it.copy(
                    isSpeaking = false,
                    voiceEngine = DjVoiceEngine.DEVICE,
                    lastPreviewFeedback = "El motor de voz del teléfono aún no está listo en este dispositivo."
                )
            }
            return
        }
        applyDeviceVoiceSettings(engine)
        val deviceVoice = activeDeviceVoiceLabel()
        val rateText = "%.2f".format(deviceSpeechRate)
        val pitchText = "%.2f".format(devicePitch)
        val detail = "Teléfono ($deviceVoice • Vel ${rateText}× • Tono ${pitchText}×)"
        _state.update {
            it.copy(
                voiceEngine = DjVoiceEngine.DEVICE,
                lastSpokenModel = "Android TTS",
                lastSpokenVoice = deviceVoice,
                lastEngineDetail = detail,
                lastPreviewFeedback = "Habló con Teléfono ($deviceVoice)"
            )
        }
        speakWithDeviceTts("Hola, soy DJ Sony usando la voz local de tu teléfono. ¿Cómo suena?")
    }

    private suspend fun synthesizeWithClipCache(text: String, voiceName: String): SynthesizedSpeech? {
        val cleaned = FirebaseAiVoiceSynthesizer.sanitizeTextForSpeech(text)
        if (cleaned.isBlank()) return null
        val normalizedVoice = DjVoiceCatalog.normalizeOrDefault(voiceName)
        val cacheKey = "$normalizedVoice|$cleaned"
        synchronized(clipCacheLock) {
            speechClipCache[cacheKey]?.let { return it }
        }
        val synth = djVoiceSynthesizer.synthesizeSpeech(cleaned, normalizedVoice) ?: return null
        if (synth.pcmBytes.isNotEmpty()) {
            synchronized(clipCacheLock) {
                speechClipCache[cacheKey] = synth
            }
        }
        return synth
    }

    private fun speakScript(
        text: String,
        segmentTitle: String,
        preSynthesized: Deferred<SynthesizedSpeech?>? = null
    ) {
        speechJob?.cancel()
        stopActiveAudioTrack()
        runCatching { tts?.stop() }
        _state.update { it.copy(spokenCaption = text, segmentTitle = segmentTitle) }

        speechJob = scope.launch {
            val neuralSpeech = try {
                preSynthesized?.await() ?: synthesizeWithClipCache(text, selectedVoiceName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (!_state.value.isActive) return@launch

            if (neuralSpeech != null && neuralSpeech.pcmBytes.isNotEmpty()) {
                val modelLabel = neuralSpeech.modelName.ifBlank { "Gemini TTS" }
                val actualVoice = neuralSpeech.voiceName.ifBlank { selectedVoiceName }
                val detail = "${neuralSpeech.engineLabel} ($modelLabel • Voz: $actualVoice)"
                SonoraPlaybackBridge.setDucking(true)
                _state.update {
                    it.copy(
                        isSpeaking = true,
                        voiceEngine = DjVoiceEngine.GEMINI,
                        lastSpokenModel = modelLabel,
                        lastSpokenVoice = actualVoice,
                        lastEngineDetail = detail
                    )
                }
                try {
                    playPcmSpeech(neuralSpeech)
                } finally {
                    finishSpeaking()
                }
                return@launch
            }

            // Fallback to device TTS if neural TTS is offline or unavailable
            val fallbackReason = djVoiceSynthesizer.lastSynthesisReport ?: "Motor neuronal no devolvió audio"
            val deviceVoice = activeDeviceVoiceLabel()
            val detail = "Teléfono ($deviceVoice • motivo respaldo: $fallbackReason)"
            _state.update {
                it.copy(
                    voiceEngine = DjVoiceEngine.DEVICE,
                    lastSpokenModel = "Android TTS",
                    lastSpokenVoice = deviceVoice,
                    lastEngineDetail = detail,
                    aiTechnicalDetail = it.aiTechnicalDetail?.let { prev -> "$prev\nVoz: $fallbackReason" } ?: fallbackReason
                )
            }
            if (ttsReady) {
                speakWithDeviceTts(text)
            } else {
                pendingUtterance = text
                duckWatchdogJob?.cancel()
                duckWatchdogJob = scope.launch {
                    _state.update { it.copy(isSpeaking = true) }
                    delay(5_500L)
                    finishSpeaking()
                }
            }
        }
    }

    private suspend fun playPcmSpeech(speech: SynthesizedSpeech) = withContext(Dispatchers.IO) {
        val sampleRate = speech.sampleRateHz.coerceIn(8_000, 96_000)
        val pcm = applyEdgeFades(speech.pcmBytes, sampleRate)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8_192)
        val bufSize = maxOf(minBuf * 2, pcm.size.coerceAtMost(minBuf * 4))

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeAudioTrack = track
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) return@withContext
            track.setVolume(1.0f)
            track.play()

            val startMs = System.currentTimeMillis()
            val expectedDurationMs = ((pcm.size / 2L) * 1000L) / sampleRate
            var offset = 0
            val chunkSize = 4_096

            while (offset < pcm.size && isActive) {
                val toWrite = minOf(chunkSize, pcm.size - offset)
                val written = track.write(pcm, offset, toWrite)
                if (written <= 0) break
                offset += written
            }

            if (isActive) {
                runCatching { track.stop() }
                val remainingMs = (startMs + expectedDurationMs) - System.currentTimeMillis()
                if (remainingMs > 0L) {
                    delay(remainingMs.coerceAtMost(12_000L) + 120L)
                }
            }
        } finally {
            if (activeAudioTrack === track) {
                activeAudioTrack = null
            }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.release()
            }
        }
    }

    private fun stopActiveAudioTrack() {
        val current = activeAudioTrack ?: return
        activeAudioTrack = null
        runCatching {
            if (current.playState == AudioTrack.PLAYSTATE_PLAYING) {
                current.pause()
                current.flush()
            }
            current.release()
        }
    }

    private fun speakWithDeviceTts(text: String) {
        val engine = tts ?: return
        applyDeviceVoiceSettings(engine)
        SonoraPlaybackBridge.setDucking(true)
        _state.update { it.copy(isSpeaking = true) }

        duckWatchdogJob?.cancel()
        duckWatchdogJob = scope.launch {
            delay(14_000L)
            finishSpeaking()
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.96f)
        }
        val utteranceId = "dj_${UUID.randomUUID()}"
        val status = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)
        if (status == TextToSpeech.ERROR) {
            scope.launch {
                delay(4_500L)
                finishSpeaking()
            }
        }
    }

    private fun finishSpeaking() {
        duckWatchdogJob?.cancel()
        SonoraPlaybackBridge.setDucking(false)
        _state.update { it.copy(isSpeaking = false) }
    }

    fun release() {
        stopDjLive()
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object {
        internal fun applyEdgeFades(rawPcm: ByteArray, sampleRateHz: Int): ByteArray {
            if (rawPcm.size < 256) return rawPcm
            val out = rawPcm.copyOf()
            val totalSamples = out.size / 2
            val fadeSamples = ((sampleRateHz * 6L) / 1000L).toInt().coerceIn(8, totalSamples / 4)

            for (i in 0 until fadeSamples) {
                val factor = i.toFloat() / fadeSamples.toFloat()
                scaleSample16Le(out, i * 2, factor)
                val tailIdx = (totalSamples - 1 - i) * 2
                scaleSample16Le(out, tailIdx, factor)
            }
            return out
        }

        private fun scaleSample16Le(bytes: ByteArray, byteOffset: Int, factor: Float) {
            if (byteOffset + 1 >= bytes.size) return
            val lo = bytes[byteOffset].toInt() and 0xFF
            val hi = bytes[byteOffset + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val scaled = (sample * factor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[byteOffset] = (scaled and 0xFF).toByte()
            bytes[byteOffset + 1] = ((scaled shr 8) and 0xFF).toByte()
        }

        fun curateVoiceMixPool(
            gathered: List<Track>,
            upNextTracks: List<Track>,
            limit: Int = 24
        ): List<Track> {
            val distinctCombined = (gathered + upNextTracks)
                .filter { it.videoId.isNotBlank() }
                .distinctBy { it.videoId }
            if (distinctCombined.isEmpty()) return emptyList()
            // Pass seedTrack = null so the primary requested track(s) in `gathered` are NOT stripped!
            val clean = MusicRepository.filterRelatedCleanTracks(
                seedTrack = null,
                candidates = distinctCombined
            )
            return if (clean.size >= 3 || gathered.isEmpty()) {
                clean.take(limit)
            } else {
                distinctCombined.take(limit)
            }
        }

        fun curateMixQueue(
            vibe: DjVibe,
            liked: List<Track>,
            mostPlayed: List<Track>,
            recent: List<Track>,
            personalized: List<Track>,
            radio: List<Track>,
            homeFallback: List<Track>
        ): List<Track> {
            val rawOrder: List<Track> = when (vibe) {
                DjVibe.PERSONAL_MIX -> {
                    val topFavs = (mostPlayed.take(4) + liked.shuffled().take(4))
                    val fresh = (personalized + radio + homeFallback).shuffled()
                    interleave(topFavs, fresh)
                }
                DjVibe.ENERGY -> {
                    (mostPlayed.shuffled() + radio + personalized + liked.shuffled() + homeFallback)
                }
                DjVibe.CHILL -> {
                    (liked.reversed().take(5) + personalized.reversed() + recent.reversed() + radio + homeFallback)
                }
                DjVibe.DISCOVERY -> {
                    val knownIds = (liked + mostPlayed + recent).map { it.videoId }.toSet()
                    val unseen = (personalized + radio + homeFallback).filterNot { it.videoId in knownIds }.shuffled()
                    if (unseen.size >= 6) unseen + mostPlayed.take(3) else (unseen + personalized + radio + liked + homeFallback).shuffled()
                }
            }
            return rawOrder.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }.take(24)
        }

        private fun interleave(a: List<Track>, b: List<Track>): List<Track> {
            val out = mutableListOf<Track>()
            val max = maxOf(a.size, b.size)
            for (i in 0 until max) {
                a.getOrNull(i)?.let(out::add)
                b.getOrNull(i)?.let(out::add)
            }
            return out
        }

        internal fun stripWakeWords(raw: String): String {
            val trimmed = raw.trim()
            val stripped = trimmed
                .replace(Regex("^(?:oye\\s+|hola\\s+)?dj\\s+sony[,:;!?.\\s]*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(?:oye\\s+|hola\\s+)dj[,:;!?.\\s]*", RegexOption.IGNORE_CASE), "")
                .trim()
            return stripped.ifBlank { trimmed }
        }

        private fun capSpeechScript(raw: String, maxWords: Int = 25): String {
            val sanitized = FirebaseAiVoiceSynthesizer.sanitizeTextForSpeech(raw)
            val words = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() }
            return if (words.size <= maxWords) sanitized else words.take(maxWords).joinToString(" ") + "."
        }

        private fun buildOpeningScript(
            vibe: DjVibe,
            firstTrack: Track,
            likedCount: Int,
            hasMostPlayed: Boolean
        ): String {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val greeting = when {
                hour in 5..11 -> "Buenos días"
                hour in 12..18 -> "Buenas tardes"
                else -> "Buenas noches"
            }
            val cleanTitle = FirebaseAiVoiceSynthesizer.sanitizeTextForSpeech(firstTrack.title).ifBlank { firstTrack.title }
            val artist = FirebaseAiVoiceSynthesizer.sanitizeTextForSpeech(
                firstTrack.artist.substringBefore(",").trim()
            ).ifBlank { "tu artista favorito" }
            val raw = when (vibe) {
                DjVibe.PERSONAL_MIX -> if (hasMostPlayed || likedCount > 0) {
                    "$greeting, soy DJ Sony. Preparé tu mezcla personal con $cleanTitle de $artist."
                } else {
                    "$greeting, soy DJ Sony. Arrancamos esta sesión con $cleanTitle de $artist."
                }
                DjVibe.ENERGY -> "Subimos la energía en DJ Sony. Empieza $cleanTitle de $artist."
                DjVibe.CHILL -> "Momento tranquilo con DJ Sony. Suena $cleanTitle de $artist."
                DjVibe.DISCOVERY -> "Vamos a descubrir algo fresco. Escucha $cleanTitle de $artist."
            }
            return capSpeechScript(raw)
        }

        private fun buildTransitionScript(vibe: DjVibe, nextTrack: Track): String {
            val cleanTitle = FirebaseAiVoiceSynthesizer.sanitizeTextForSpeech(nextTrack.title).ifBlank { nextTrack.title }
            val artist = FirebaseAiVoiceSynthesizer.sanitizeTextForSpeech(
                nextTrack.artist.substringBefore(",").trim()
            ).ifBlank { "este artista" }
            val templates = when (vibe) {
                DjVibe.PERSONAL_MIX -> listOf(
                    "Ahora en tu mezcla suena $cleanTitle de $artist.",
                    "Seguimos con otro tema para ti: $cleanTitle de $artist."
                )
                DjVibe.ENERGY -> listOf(
                    "No bajamos el ritmo. Viene $cleanTitle de $artist.",
                    "Seguimos arriba con $artist y $cleanTitle."
                )
                DjVibe.CHILL -> listOf(
                    "Seguimos en calma con $cleanTitle de $artist.",
                    "Suave y sin cortes, ahora suena $cleanTitle de $artist."
                )
                DjVibe.DISCOVERY -> listOf(
                    "Una recomendación nueva para ti: $cleanTitle de $artist.",
                    "Exploramos otro sonido con $artist y $cleanTitle."
                )
            }
            return capSpeechScript(templates.random())
        }
    }
}
