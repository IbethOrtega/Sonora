package com.example.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.data.ai.AiDjIntentRepository
import com.example.data.ai.DjVoiceSynthesizer
import com.example.data.ai.NoOpDjVoiceSynthesizer
import com.example.data.ai.PlaceholderAiDjIntentRepository
import com.example.data.ai.SynthesizedSpeech
import com.example.data.repository.MusicRepository
import com.example.model.DjVibe
import com.example.model.SearchFilter
import com.example.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

data class DjLiveState(
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val isSpeaking: Boolean = false,
    val currentVibe: DjVibe = DjVibe.PERSONAL_MIX,
    val spokenCaption: String = "",
    val segmentTitle: String = "Sonora DJ Live",
    val segmentStartIndices: List<Int> = emptyList(),
    val lastVoicePrompt: String = "",
    val aiNotice: String? = null,
    val aiError: String? = null
)

class DjLiveController(
    private val context: Context,
    private val repository: MusicRepository,
    private val playerController: PlayerController,
    private val aiDjIntentRepository: AiDjIntentRepository = PlaceholderAiDjIntentRepository(),
    private val djVoiceSynthesizer: DjVoiceSynthesizer = NoOpDjVoiceSynthesizer()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(DjLiveState())
    val state: StateFlow<DjLiveState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var pendingUtterance: String? = null
    private var duckWatchdogJob: Job? = null
    private var speechJob: Job? = null
    private var startDjJob: Job? = null
    private var lastAnnouncedTrackId: String? = null
    private var blockTransitionIndices: Set<Int> = emptySet()

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    init {
        initFallbackTts()
        observePlayerTransitions()
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
                    selectBestSpanishVoice(engine)?.let { preferred ->
                        runCatching { engine.voice = preferred }
                    }
                    engine.setSpeechRate(0.98f)
                    engine.setPitch(1.03f)
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

    private fun selectBestSpanishVoice(engine: TextToSpeech): Voice? {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        return voices
            .filter { voice ->
                voice.locale.language.equals("es", ignoreCase = true) &&
                    Voice.LATENCY_NORMAL <= voice.latency
            }
            .maxByOrNull { voice ->
                var score = voice.quality
                val name = voice.name.lowercase()
                val country = voice.locale.country.uppercase()
                if (!voice.isNetworkConnectionRequired) score += 120
                if (country == "MX" || country == "US") score += 60
                if ("neural" in name || "wavenet" in name || "natural" in name || "studio" in name) score += 250
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
                if (track.videoId != lastAnnouncedTrackId && idx in blockTransitionIndices) {
                    lastAnnouncedTrackId = track.videoId
                    val vibe = _state.value.currentVibe
                    val line = buildTransitionScript(vibe, track)
                    speakScript(line, segmentTitle = "${vibe.label} • DJ Live")
                }
            }
        }
    }

    fun startDjLive(vibe: DjVibe = _state.value.currentVibe) {
        startDjJob?.cancel()
        startDjJob = scope.launch {
            _state.update {
                it.copy(
                    isActive = true,
                    isLoading = true,
                    currentVibe = vibe,
                    segmentTitle = vibe.label,
                    spokenCaption = "Preparando tu sesión de DJ Live...",
                    aiError = null
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

            val homeFallback = if (seeds.isEmpty() || vibe == DjVibe.DISCOVERY) {
                runCatching {
                    repository.getHomeShelves().getOrDefault(emptyList()).flatMap { it.tracks }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val seedForRadio = when (vibe) {
                DjVibe.PERSONAL_MIX -> mostPlayed.firstOrNull() ?: liked.firstOrNull() ?: recent.firstOrNull()
                DjVibe.ENERGY -> mostPlayed.getOrNull(1) ?: homeFallback.firstOrNull() ?: seeds.firstOrNull()
                DjVibe.CHILL -> liked.lastOrNull() ?: recent.lastOrNull() ?: seeds.firstOrNull()
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
                _state.update {
                    it.copy(
                        isActive = false,
                        isLoading = false,
                        spokenCaption = "Reproduce o busca un tema primero para personalizar DJ Live."
                    )
                }
                return@launch
            }

            val checkpoints = listOf(3, 7, 11, 15).filter { it < curatedPool.size }.toSet()
            blockTransitionIndices = checkpoints
            val leadTrack = curatedPool.first()
            lastAnnouncedTrackId = leadTrack.videoId

            val greeting = buildOpeningScript(vibe, leadTrack, liked.size, mostPlayed.isNotEmpty())
            val preSynthSpeech = async { djVoiceSynthesizer.synthesizeSpeech(greeting) }

            _state.update {
                it.copy(
                    isActive = true,
                    isLoading = false,
                    currentVibe = vibe,
                    segmentTitle = vibe.label,
                    spokenCaption = greeting,
                    segmentStartIndices = checkpoints.toList(),
                    aiError = null
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
        startDjJob?.cancel()
        startDjJob = scope.launch {
            _state.update {
                it.copy(
                    isActive = true,
                    isLoading = true,
                    lastVoicePrompt = cleaned,
                    segmentTitle = "DJ por voz • Gemini",
                    spokenCaption = "Consultando a Gemini para: \"$cleaned\"...",
                    aiError = null
                )
            }

            val plan = try {
                aiDjIntentRepository.planVoiceMix(cleaned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errMsg = e.localizedMessage ?: "No se pudo conectar con Gemini."
                _state.update {
                    it.copy(
                        isActive = false,
                        isLoading = false,
                        aiError = errMsg,
                        spokenCaption = errMsg
                    )
                }
                return@launch
            }

            // Start synthesizing the human DJ speech in parallel while fetching the mix songs.
            val preSynthSpeech = async { djVoiceSynthesizer.synthesizeSpeech(plan.djReply) }

            val gathered = mutableListOf<Track>()
            for (query in plan.searchQueries) {
                val found = runCatching {
                    repository.search(query, SearchFilter.SONGS).getOrNull()?.songs.orEmpty()
                }.getOrDefault(emptyList())
                gathered += MusicRepository.filterRelatedCleanTracks(null, found).take(8)
            }

            val seedTrack = gathered.firstOrNull()
            val upNextTracks = if (seedTrack != null) {
                runCatching { repository.getUpNextAndLyricsId(seedTrack.videoId).first }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val curatedPool = MusicRepository.filterRelatedCleanTracks(
                seedTrack = seedTrack,
                candidates = (gathered + upNextTracks).distinctBy { it.videoId }
            ).take(24)

            if (curatedPool.isEmpty()) {
                preSynthSpeech.cancel()
                _state.update {
                    it.copy(
                        isActive = false,
                        isLoading = false,
                        aiNotice = plan.notice,
                        aiError = null,
                        spokenCaption = "No encontré canciones para \"$cleaned\". Intenta con otro artista o género."
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
                    aiNotice = plan.notice,
                    aiError = null
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
                preSynthesized?.await() ?: djVoiceSynthesizer.synthesizeSpeech(text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (!_state.value.isActive) return@launch

            if (neuralSpeech != null && neuralSpeech.pcmBytes.isNotEmpty()) {
                SonoraPlaybackBridge.setDucking(true)
                _state.update { it.copy(isSpeaking = true) }
                try {
                    playPcmSpeech(neuralSpeech)
                } finally {
                    finishSpeaking()
                }
                return@launch
            }

            // Fallback to device TTS if Gemini TTS is offline or unavailable
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
                    val fresh = (personalized + radio).shuffled()
                    interleave(topFavs, fresh)
                }
                DjVibe.ENERGY -> {
                    (mostPlayed.shuffled() + radio + personalized + liked.shuffled())
                }
                DjVibe.CHILL -> {
                    (liked.reversed().take(5) + personalized.reversed() + recent.reversed() + radio)
                }
                DjVibe.DISCOVERY -> {
                    val knownIds = (liked + mostPlayed + recent).map { it.videoId }.toSet()
                    val unseen = (personalized + radio + homeFallback).filterNot { it.videoId in knownIds }.shuffled()
                    if (unseen.size >= 6) unseen + mostPlayed.take(3) else (unseen + personalized + radio + liked).shuffled()
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
            val artist = firstTrack.artist.substringBefore(",").trim().ifBlank { "tu artista favorito" }
            return when (vibe) {
                DjVibe.PERSONAL_MIX -> if (hasMostPlayed || likedCount > 0) {
                    "¡$greeting! Qué gusto acompañarte en Sonora. Preparé esta mezcla con tus canciones favoritas y un par de sorpresas. Arrancamos con ${firstTrack.title} de $artist."
                } else {
                    "¡$greeting! Bienvenido a Sonora DJ Live. Vamos con una sesión continua sin pausas empezando por ${firstTrack.title} de $artist."
                }
                DjVibe.ENERGY -> "Subimos la energía ahora mismo en Sonora. Vamos con todo el ritmo empezando por ${firstTrack.title} de $artist."
                DjVibe.CHILL -> "Momento perfecto para bajar revoluciones y relajarse. Disfruta este bloque suave que abre con ${firstTrack.title} de $artist."
                DjVibe.DISCOVERY -> "Vamos a descubrir música nueva hecha a tu medida. Escucha ${firstTrack.title} de $artist."
            }
        }

        private fun buildTransitionScript(vibe: DjVibe, nextTrack: Track): String {
            val artist = nextTrack.artist.substringBefore(",").trim().ifBlank { "este artista" }
            val templates = when (vibe) {
                DjVibe.PERSONAL_MIX -> listOf(
                    "Cambiamos de aire en tu mezcla personal. Ahora suena ${nextTrack.title} de $artist.",
                    "Seguimos con otra joya elegida para ti: $artist con ${nextTrack.title}."
                )
                DjVibe.ENERGY -> listOf(
                    "No bajamos el ritmo en Sonora. Viene ${nextTrack.title} de $artist.",
                    "Subimos un punto más la energía con $artist y ${nextTrack.title}."
                )
                DjVibe.CHILL -> listOf(
                    "Seguimos en calma y buena compañía con ${nextTrack.title} de $artist.",
                    "Suave y sin cortes, ahora escuchas a $artist con ${nextTrack.title}."
                )
                DjVibe.DISCOVERY -> listOf(
                    "Una recomendación fresca para tu radar: ${nextTrack.title} de $artist.",
                    "Seguimos explorando sonidos nuevos con $artist y ${nextTrack.title}."
                )
            }
            return templates.random()
        }
    }
}
