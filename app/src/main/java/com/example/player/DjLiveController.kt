package com.example.player

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.data.repository.MusicRepository
import com.example.model.DjVibe
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val segmentStartIndices: List<Int> = emptyList()
)

class DjLiveController(
    private val context: Context,
    private val repository: MusicRepository,
    private val playerController: PlayerController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(DjLiveState())
    val state: StateFlow<DjLiveState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var pendingUtterance: String? = null
    private var duckWatchdogJob: Job? = null
    private var startDjJob: Job? = null
    private var lastAnnouncedTrackId: String? = null
    private var blockTransitionIndices: Set<Int> = emptySet()

    init {
        initTts()
        observePlayerTransitions()
    }

    private fun initTts() {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    val spanishMx = Locale("es", "MX")
                    val spanishEs = Locale("es", "ES")
                    val res = when {
                        engine.isLanguageAvailable(spanishMx) >= TextToSpeech.LANG_AVAILABLE ->
                            engine.setLanguage(spanishMx)
                        engine.isLanguageAvailable(spanishEs) >= TextToSpeech.LANG_AVAILABLE ->
                            engine.setLanguage(spanishEs)
                        else -> engine.setLanguage(Locale.getDefault())
                    }
                    engine.setSpeechRate(1.03f)
                    engine.setPitch(0.98f)
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
                        speakNow(queued)
                    }
                }
            }
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
                    spokenCaption = "Preparando tu sesión de DJ Live..."
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
            _state.update {
                it.copy(
                    isActive = true,
                    isLoading = false,
                    currentVibe = vibe,
                    segmentTitle = vibe.label,
                    spokenCaption = greeting,
                    segmentStartIndices = checkpoints.toList()
                )
            }

            playerController.playQueue(curatedPool, 0)
            speakScript(greeting, segmentTitle = vibe.label)
        }
    }

    fun switchVibe() {
        val entries = DjVibe.entries
        val nextIdx = (entries.indexOf(_state.value.currentVibe) + 1) % entries.size
        startDjLive(entries[nextIdx])
    }

    fun stopDjLive() {
        startDjJob?.cancel()
        blockTransitionIndices = emptySet()
        lastAnnouncedTrackId = null
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

    private fun speakScript(text: String, segmentTitle: String) {
        _state.update { it.copy(spokenCaption = text, segmentTitle = segmentTitle) }
        if (ttsReady) {
            speakNow(text)
        } else {
            pendingUtterance = text
            // Simulated visual caption cadence if TTS voice pack isn't ready on device
            duckWatchdogJob?.cancel()
            duckWatchdogJob = scope.launch {
                _state.update { it.copy(isSpeaking = true) }
                delay(5_500L)
                finishSpeaking()
            }
        }
    }

    private fun speakNow(text: String) {
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
                    "$greeting. Soy tu DJ de Sonora. Armé este bloque con lo que más escuchas y un par de sorpresas. Empezamos con ${firstTrack.title} de $artist."
                } else {
                    "$greeting. Soy tu DJ de Sonora. Arrancamos una sesión continua sin anuncios con ${firstTrack.title} de $artist."
                }
                DjVibe.ENERGY -> "Subimos la energía en Sonora DJ Live. Vamos con ritmo constante empezando por $artist y ${firstTrack.title}."
                DjVibe.CHILL -> "Momento de bajar las revoluciones. Disfruta este bloque suave que abre con ${firstTrack.title} de $artist."
                DjVibe.DISCOVERY -> "Vamos a descubrir música nueva cerca de tus gustos. Escucha ${firstTrack.title} de $artist."
            }
        }

        private fun buildTransitionScript(vibe: DjVibe, nextTrack: Track): String {
            val artist = nextTrack.artist.substringBefore(",").trim().ifBlank { "este artista" }
            val templates = when (vibe) {
                DjVibe.PERSONAL_MIX -> listOf(
                    "Cambiamos de ritmo en tu mezcla personal. Ahora suena ${nextTrack.title} de $artist.",
                    "Seguimos con una selección hecha para ti: $artist con ${nextTrack.title}."
                )
                DjVibe.ENERGY -> listOf(
                    "No bajamos el ritmo. Viene ${nextTrack.title} de $artist.",
                    "Siguiente bloque arriba: $artist y ${nextTrack.title}."
                )
                DjVibe.CHILL -> listOf(
                    "Seguimos en modo tranquilo con ${nextTrack.title} de $artist.",
                    "Suave y sin pausas, ahora escuchas a $artist con ${nextTrack.title}."
                )
                DjVibe.DISCOVERY -> listOf(
                    "Una joya recomendada para tu radar: ${nextTrack.title} de $artist.",
                    "Explorando sonidos nuevos con $artist y ${nextTrack.title}."
                )
            }
            return templates.random()
        }
    }
}
