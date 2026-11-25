package com.example.data.ai

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.CancellationException

/**
 * Result of interpreting a natural-language Voice DJ request into executable YouTube Music
 * search queries and a spoken DJ response.
 */
data class AiDjPlanResult(
    val vibeTitle: String,
    val djReply: String,
    val searchQueries: List<String>,
    val isFirebaseBacked: Boolean,
    val notice: String? = null
)

data class SynthesizedSpeech(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int = 24_000,
    val engineLabel: String = "Gemini",
    val modelName: String = "",
    val voiceName: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesizedSpeech) return false
        return sampleRateHz == other.sampleRateHz &&
            engineLabel == other.engineLabel &&
            modelName == other.modelName &&
            voiceName == other.voiceName &&
            pcmBytes.contentEquals(other.pcmBytes)
    }

    override fun hashCode(): Int {
        var result = pcmBytes.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + engineLabel.hashCode()
        result = 31 * result + modelName.hashCode()
        result = 31 * result + voiceName.hashCode()
        return result
    }
}

/**
 * Single AI boundary for Voice DJ intent parsing.
 */
interface AiDjIntentRepository {
    suspend fun planVoiceMix(spokenPrompt: String): AiDjPlanResult
}

/**
 * Human-like neural TTS boundary for DJ announcements.
 */
interface DjVoiceSynthesizer {
    val lastSynthesisReport: String? get() = null

    suspend fun synthesizeSpeech(
        text: String,
        voiceName: String = FirebaseAiVoiceSynthesizer.DEFAULT_VOICE_NAME
    ): SynthesizedSpeech?
}

class NoOpDjVoiceSynthesizer : DjVoiceSynthesizer {
    override suspend fun synthesizeSpeech(text: String, voiceName: String): SynthesizedSpeech? = null
}

/**
 * AI-backed Voice DJ planner ("DJ Sony") using Firebase AI Logic (`Firebase.ai`) and App Check attestation.
 */
class FirebaseAiDjIntentRepository(
    private val context: Context
) : AiDjIntentRepository {

    private val mixSchema = Schema.obj(
        properties = mapOf(
            "vibeTitle" to Schema.string(
                description = "Short Spanish session title (2 to 5 words) summarizing the mix."
            ),
            "djReply" to Schema.string(
                description = "Warm, natural, expressive 1-2 sentence Spanish radio host response confirming the mix."
            ),
            "searchQueries" to Schema.array(
                items = Schema.string(),
                description = "3 to 5 targeted YouTube Music search queries matching the exact artists, songs, genres, or mood requested. Avoid covers or karaoke unless explicitly requested."
            )
        )
    )

    private val model: GenerativeModel by lazy {
        val firebaseApp = ensureFirebaseAndAppCheck(context)
            ?: throw IllegalStateException(
                "No se pudo inicializar el servicio de DJ Sony en este dispositivo."
            )
        logAppCheckDiagnosticNonBlocking(firebaseApp)
        Firebase.ai(
            app = firebaseApp,
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            modelName = MODEL_TEXT,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = mixSchema
                temperature = 0.65f
            },
            systemInstruction = content {
                text(
                    "Eres DJ Sony, el DJ en vivo inteligente de Sonora, una app de música en español. " +
                        "Interpreta peticiones por voz o texto para armar colas musicales precisas en YouTube Music. " +
                        "Prioriza los artistas, canciones, géneros, épocas o estados de ánimo exactos que pidió el usuario, " +
                        "y evita incluir covers o versiones karaoke salvo que el usuario lo pida explícitamente. " +
                        "Redacta djReply en 1 o 2 frases cortas (máximo 25 palabras), sin emojis, comillas ni guiones largos, con tono humano, cercano y natural de locutor musical en español."
                )
            }
        )
    }

    override suspend fun planVoiceMix(spokenPrompt: String): AiDjPlanResult {
        val cleaned = spokenPrompt.trim()
        require(cleaned.isNotBlank()) { "Escribe o dicta una petición musical para DJ Sony." }

        try {
            val response = withTimeout(45_000L) {
                model.generateContent(
                    "Petición del oyente: \"$cleaned\". " +
                        "Devuelve 3 a 5 búsquedas específicas para armar un mix coherente en YouTube Music y una respuesta breve (1 a 2 frases), cálida y natural de DJ Sony en español."
                )
            }
            val rawJson = response.text
                ?: throw IllegalStateException("DJ Sony no devolvió respuesta para tu petición.")
            val json = JSONObject(rawJson)

            val vibeTitle = json.optString("vibeTitle").trim().ifBlank { "Mix de DJ Sony" }
            val djReply = json.optString("djReply").trim()
                .ifBlank { "Soy DJ Sony, qué buena elección. Ya te armé este mix de $vibeTitle." }
            val arr = json.optJSONArray("searchQueries")
            val queries = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val q = arr.optString(i).trim()
                        if (q.isNotBlank()) add(q)
                    }
                }
            }.distinct().take(5)

            if (queries.isEmpty()) {
                throw IllegalStateException("DJ Sony no generó búsquedas válidas para este pedido.")
            }

            return AiDjPlanResult(
                vibeTitle = vibeTitle,
                djReply = djReply,
                searchQueries = queries,
                isFirebaseBacked = true,
                notice = null
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(DIAGNOSTIC_TAG, "DJ Sony intent request timed out", e)
            throw IllegalStateException("La conexión con DJ Sony tardó demasiado. Intenta de nuevo.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(DIAGNOSTIC_TAG, "DJ Sony intent request failed", e)
            val detail = e.localizedMessage?.trim().orEmpty()
            val message = when {
                detail.contains("403") || detail.contains("App Check", ignoreCase = true) ->
                    "Error de verificación de DJ Sony (App Check). Detalle: $detail"
                detail.contains("network", ignoreCase = true) || detail.contains("Unable to resolve host", ignoreCase = true) ->
                    "Sin conexión para consultar a DJ Sony. Verifica tu internet."
                detail.isNotBlank() -> detail
                else -> "No se pudo consultar a DJ Sony para armar el mix."
            }
            throw IllegalStateException(message, e)
        }
    }

    private fun logAppCheckDiagnosticNonBlocking(firebaseApp: com.google.firebase.FirebaseApp) {
        runCatching {
            FirebaseAppCheck.getInstance(firebaseApp)
                .getAppCheckToken(false)
                .addOnSuccessListener { tokenResult ->
                    val tokenLen = tokenResult.token.length
                    Log.d(
                        DIAGNOSTIC_TAG,
                        "App Check token ready (length=$tokenLen, expireMs=${tokenResult.expireTimeMillis})"
                    )
                }
                .addOnFailureListener { err ->
                    Log.w(DIAGNOSTIC_TAG, "App Check token retrieval failed: ${err.message}", err)
                }
        }.onFailure { err ->
            Log.w(DIAGNOSTIC_TAG, "App Check diagnostic check error: ${err.message}", err)
        }
    }

    companion object {
        internal const val DIAGNOSTIC_TAG = "SonoraDJ"
        const val MODEL_TEXT = "gemini-3.8-flash"
    }
}

/**
 * Synthesizes natural, warm human radio-host speech using Gemini Flash TTS via Firebase AI Logic.
 */
class FirebaseAiVoiceSynthesizer private constructor(
    private val context: Context?,
    private val customRunner: (suspend (modelName: String, voiceName: String, prompt: String) -> SynthesizedSpeech?)?
) : DjVoiceSynthesizer {

    constructor(context: Context) : this(context = context, customRunner = null)

    internal constructor(
        runner: suspend (modelName: String, voiceName: String, prompt: String) -> SynthesizedSpeech?
    ) : this(context = null, customRunner = runner)

    private val modelCache = ConcurrentHashMap<String, GenerativeModel>()

    @Volatile
    private var cachedWorkingModel: String? = null

    @Volatile
    private var completedFirstAttempt: Boolean = false

    @Volatile
    private var _lastSynthesisReport: String? = null

    override val lastSynthesisReport: String?
        get() = _lastSynthesisReport

    @OptIn(PublicPreviewAPI::class)
    private fun getOrCreateTtsModel(modelName: String, voiceName: String): GenerativeModel {
        val cacheKey = "$modelName:$voiceName"
        modelCache[cacheKey]?.let { return it }
        val appContext = context ?: throw IllegalStateException("Contexto requerido para TTS.")
        val firebaseApp = ensureFirebaseAndAppCheck(appContext)
            ?: throw IllegalStateException("El motor de voz de DJ Sony no está inicializado.")
        val built = Firebase.ai(
            app = firebaseApp,
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(voice = Voice(voiceName))
            }
        )
        modelCache[cacheKey] = built
        return built
    }

    override suspend fun synthesizeSpeech(text: String, voiceName: String): SynthesizedSpeech? {
        val sanitized = sanitizeTextForSpeech(text)
        if (sanitized.isBlank()) return null

        val prompt = buildTtsPrompt(sanitized)
        val requestedVoice = com.example.model.DjVoiceCatalog.normalizeOrDefault(voiceName)
        val diagnosticLines = mutableListOf<String>()

        tryWithVoice(prompt = prompt, voiceName = requestedVoice, diagnosticLines = diagnosticLines)?.let {
            return it
        }

        if (!requestedVoice.equals(DEFAULT_VOICE_NAME, ignoreCase = true)) {
            Log.w(TAG, "DJ Sony voice '$requestedVoice' failed, retrying with '$DEFAULT_VOICE_NAME'")
            tryWithVoice(prompt = prompt, voiceName = DEFAULT_VOICE_NAME, diagnosticLines = diagnosticLines)?.let {
                return it
            }
        }

        if (customRunner == null) {
            tryWithLiveModel(sanitized = sanitized, voiceName = requestedVoice, diagnosticLines = diagnosticLines)?.let {
                return it
            }
        }

        val summary = diagnosticLines.joinToString(" | ").ifBlank {
            "Sin audio PCM devuelto por Gemini TTS ni Gemini Live"
        }
        _lastSynthesisReport = summary
        Log.w(TAG, "DJ Sony fallback to Android TTS. Report: $summary")
        return null
    }

    private suspend fun tryWithVoice(
        prompt: String,
        voiceName: String,
        diagnosticLines: MutableList<String>
    ): SynthesizedSpeech? {
        val models = buildList {
            cachedWorkingModel?.let { add(it) }
            TTS_MODEL_CANDIDATES.forEach { if (it !in this) add(it) }
        }
        val timeoutMs = if (!completedFirstAttempt) FIRST_CALL_TIMEOUT_MS else STEADY_TIMEOUT_MS

        for (modelName in models) {
            var attemptsRemaining = 2 // initial + 1 retry on timeout
            while (attemptsRemaining > 0) {
                attemptsRemaining--
                try {
                    val result = withContext(Dispatchers.IO) {
                        withTimeout(timeoutMs) {
                            if (customRunner != null) {
                                customRunner.invoke(modelName, voiceName, prompt)
                                    ?.copy(
                                        engineLabel = "Gemini",
                                        modelName = modelName,
                                        voiceName = voiceName
                                    )
                            } else {
                                val model = getOrCreateTtsModel(modelName, voiceName)
                                val response = model.generateContent(prompt)
                                val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
                                val partTypes = describeParts(parts)
                                Log.d(TAG, "TTS response model=$modelName voice=$voiceName parts=[$partTypes]")
                                val extracted = extractSpeechFromParts(parts)
                                if (extracted == null) {
                                    diagnosticLines += "$modelName($voiceName): sin audio [partes: $partTypes]"
                                    null
                                } else {
                                    extracted.copy(
                                        engineLabel = "Gemini",
                                        modelName = modelName,
                                        voiceName = voiceName
                                    )
                                }
                            }
                        }
                    }
                    completedFirstAttempt = true
                    if (result != null && result.pcmBytes.isNotEmpty()) {
                        cachedWorkingModel = modelName
                        _lastSynthesisReport = "Gemini ($modelName • Voz: $voiceName • ${result.pcmBytes.size}B PCM)"
                        Log.d(TAG, "DJ Sony audio ready via $modelName ($voiceName, ${result.pcmBytes.size}B)")
                        return result
                    }
                    break // Empty audio on this model -> advance to next model
                } catch (e: TimeoutCancellationException) {
                    completedFirstAttempt = true
                    val msg = "timeout ${timeoutMs}ms"
                    diagnosticLines += "$modelName($voiceName): $msg"
                    Log.w(TAG, "DJ Sony TTS timed out on $modelName ($voiceName), remaining retries=$attemptsRemaining", e)
                    if (attemptsRemaining <= 0) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    completedFirstAttempt = true
                    val msg = e.localizedMessage?.take(120) ?: e::class.java.simpleName
                    diagnosticLines += "$modelName($voiceName): $msg"
                    Log.w(TAG, "DJ Sony TTS failed on $modelName ($voiceName): $msg", e)
                    break
                }
            }
        }
        return null
    }

    @OptIn(PublicPreviewAPI::class)
    private suspend fun tryWithLiveModel(
        sanitized: String,
        voiceName: String,
        diagnosticLines: MutableList<String>
    ): SynthesizedSpeech? {
        val appContext = context ?: return null
        val firebaseApp = ensureFirebaseAndAppCheck(appContext) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(LIVE_FALLBACK_TIMEOUT_MS) {
                    val liveModel = Firebase.ai(
                        app = firebaseApp,
                        backend = GenerativeBackend.googleAI()
                    ).liveModel(
                        modelName = LIVE_AUDIO_MODEL,
                        generationConfig = liveGenerationConfig {
                            responseModality = ResponseModality.AUDIO
                            speechConfig = SpeechConfig(voice = Voice(voiceName))
                        }
                    )
                    val session = liveModel.connect()
                    val out = ByteArrayOutputStream()
                    var sampleRate = 24_000
                    try {
                        session.send(
                            content {
                                text("Lee en voz alta en español latino natural exactamente este guion de locutor sin añadir palabras:\n$sanitized")
                            }
                        )
                        var completed = false
                        session.receive()
                            .takeWhile { !completed }
                            .collect { message ->
                                if (message is LiveServerContent) {
                                    val parts = message.content?.parts.orEmpty()
                                    if (parts.isNotEmpty()) {
                                        Log.d(TAG, "Live API parts=[${describeParts(parts)}]")
                                    }
                                    for (part in parts) {
                                        val inline = part as? InlineDataPart ?: continue
                                        if (inline.mimeType.startsWith("audio/", ignoreCase = true) && inline.inlineData.isNotEmpty()) {
                                            sampleRate = parseSampleRateFromMime(inline.mimeType)
                                            val cleanPcm = stripWavHeaderIfPresent(inline.inlineData)
                                            if (cleanPcm.isNotEmpty()) {
                                                out.write(cleanPcm)
                                            }
                                        }
                                    }
                                    if (message.turnComplete) {
                                        completed = true
                                    }
                                }
                            }
                    } finally {
                        runCatching { session.close() }
                    }
                    val bytes = out.toByteArray()
                    if (bytes.isEmpty()) {
                        diagnosticLines += "$LIVE_AUDIO_MODEL($voiceName): sin audio en Live API"
                        null
                    } else {
                        _lastSynthesisReport = "Gemini Live ($LIVE_AUDIO_MODEL • Voz: $voiceName • ${bytes.size}B PCM)"
                        Log.d(TAG, "DJ Sony Live API audio ready ($voiceName, ${bytes.size}B)")
                        SynthesizedSpeech(
                            pcmBytes = bytes,
                            sampleRateHz = sampleRate,
                            engineLabel = "Gemini Live",
                            modelName = LIVE_AUDIO_MODEL,
                            voiceName = voiceName
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.localizedMessage?.take(120) ?: e::class.java.simpleName
            diagnosticLines += "$LIVE_AUDIO_MODEL: $msg"
            Log.w(TAG, "DJ Sony Live API fallback failed: $msg", e)
            null
        }
    }

    companion object {
        private const val TAG = "SonoraDJ"
        internal const val DEFAULT_VOICE_NAME = "Aoede"
        internal const val FIRST_CALL_TIMEOUT_MS = 25_000L
        internal const val STEADY_TIMEOUT_MS = 20_000L
        internal const val LIVE_FALLBACK_TIMEOUT_MS = 16_000L
        internal const val LIVE_AUDIO_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        internal val TTS_MODEL_CANDIDATES = listOf(
            "gemini-3.8-flash-tts",
            "gemini-3.8-flash-lite-tts",
            "gemini-3.1-flash-tts-preview"
        )

        private fun describeParts(parts: List<com.google.firebase.ai.type.Part>): String {
            if (parts.isEmpty()) return "vacío"
            return parts.joinToString(", ") { part ->
                when (part) {
                    is InlineDataPart -> "InlineDataPart(${part.mimeType}, ${part.inlineData.size}B)"
                    else -> part::class.java.simpleName
                }
            }
        }

        internal fun sanitizeTextForSpeech(raw: String): String {
            if (raw.isBlank()) return ""
            return raw
                // Remove parenthetical & bracketed notes like (Official Video), [Remastered], (Audio)
                .replace(Regex("\\([^)]*\\)|\\[[^\\]]*]"), " ")
                // Remove emojis / pictographs / variation selectors / ZWJ
                .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF\\uFE0F\\u200D]+"), " ")
                // Remove quotes
                .replace(Regex("[\"'«»“”‘’]"), "")
                // Replace em/en dashes with soft pause
                .replace(Regex("[—–]"), ", ")
                // Replace feat. / ft. cleanly
                .replace(Regex("\\b(?:feat|ft)\\b\\.?", RegexOption.IGNORE_CASE), "y")
                // Normalize spaces and stray punctuation before punctuation
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\s+([,.!?])"), "$1")
                .trim()
        }

        internal fun buildTtsPrompt(script: String): String {
            val cleaned = sanitizeTextForSpeech(script)
            return "Habla como locutor de radio musical en español latino, con voz humana cálida, " +
                "natural y expresiva (sin sonar robótico). Pronuncia con naturalidad los nombres en otros idiomas " +
                "y lee exactamente este texto sin agregar ni cambiar palabras:\n$cleaned"
        }

        internal fun parseSampleRateFromMime(mimeType: String): Int {
            val match = Regex("rate=(\\d{4,6})", RegexOption.IGNORE_CASE).find(mimeType)
            val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            return if (parsed != null && parsed in 8_000..96_000) parsed else 24_000
        }

        internal fun stripWavHeaderIfPresent(bytes: ByteArray): ByteArray {
            if (bytes.size > 44 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
            ) {
                return bytes.copyOfRange(44, bytes.size)
            }
            return bytes
        }

        private fun extractSpeechFromParts(parts: List<com.google.firebase.ai.type.Part>): SynthesizedSpeech? {
            if (parts.isEmpty()) return null
            val out = ByteArrayOutputStream()
            var sampleRate = 24_000
            for (part in parts) {
                val inline = part as? InlineDataPart ?: continue
                if (!inline.mimeType.startsWith("audio/", ignoreCase = true) && inline.inlineData.isEmpty()) {
                    continue
                }
                sampleRate = parseSampleRateFromMime(inline.mimeType)
                val cleanPcm = stripWavHeaderIfPresent(inline.inlineData)
                if (cleanPcm.isNotEmpty()) {
                    out.write(cleanPcm)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return null
            return SynthesizedSpeech(pcmBytes = bytes, sampleRateHz = sampleRate)
        }
    }
}


/**
 * Pure local prompt parser kept for unit testing and offline inspection.
 */
class PlaceholderAiDjIntentRepository : AiDjIntentRepository {

    override suspend fun planVoiceMix(spokenPrompt: String): AiDjPlanResult {
        return parsePromptLocally(spokenPrompt)
    }

    companion object {
        const val FIREBASE_REQUIRED_NOTICE =
            "DJ Sony sin IA: usando búsqueda directa"

        private val fillerWords = setOf(
            "oye", "hola", "dj", "sony", "sonora", "por", "favor", "porfavor", "quiero", "pon",
            "ponme", "ponte", "arma", "armame", "haz", "hazme", "dame", "reproduce",
            "toca", "un", "una", "unos", "unas", "el", "la", "los", "las", "de", "del",
            "con", "para", "algo", "musica", "música", "canciones", "mix", "mezcla",
            "playlist", "sesion", "sesión", "tal", "como", "tipo", "estilo"
        )

        private val moodHints = mapOf(
            "regueton" to listOf("reggaeton clasico hits", "reggaeton nuevo mix", "perreo intenso exitos"),
            "reggaeton" to listOf("reggaeton clasico hits", "reggaeton nuevo mix", "perreo intenso exitos"),
            "salsa" to listOf("salsa brava exitos", "salsa romantica clasicos", "marc anthony grupo niche"),
            "bachata" to listOf("bachata romantica hits", "romeo santos aventura mix"),
            "rock" to listOf("rock en espanol clasicos", "rock alternativo hits", "indie rock mix"),
            "pop" to listOf("pop latino exitos", "top pop hits", "synth pop moderno"),
            "electronica" to listOf("electronic dance hits", "house melodic mix", "club edm essentials"),
            "electronic" to listOf("electronic dance hits", "house melodic mix", "club edm essentials"),
            "chill" to listOf("chill lofi beats", "indie chill acoustic", "musica relajante tardes"),
            "relajad" to listOf("chill lofi beats", "acustico suave mix", "r&b chill"),
            "estudiar" to listOf("lofi hip hop study beats", "ambient instrumental focus"),
            "entrenar" to listOf("workout gym motivation hits", "phonk edm workout", "reggaeton gym mix"),
            "gym" to listOf("workout gym motivation hits", "reggaeton workout energy"),
            "corridos" to listOf("corridos tumbados exitos", "regional mexicano hits"),
            "balada" to listOf("baladas romanticas en espanol", "pop romantico clasicos"),
            "jazz" to listOf("smooth jazz lounge", "modern jazz instrumental"),
            "hip hop" to listOf("hip hop classics", "rap latino hits"),
            "rap" to listOf("rap en espanol exitos", "hip hop essentials")
        )

        fun parsePromptLocally(spokenPrompt: String): AiDjPlanResult {
            val cleaned = spokenPrompt.trim()
            if (cleaned.isBlank()) {
                return AiDjPlanResult(
                    vibeTitle = "Mix de DJ Sony",
                    djReply = "Soy DJ Sony. Armé una mezcla variada con éxitos del momento.",
                    searchQueries = listOf("top music hits", "latin pop hits", "chill electronic mix"),
                    isFirebaseBacked = false,
                    notice = FIREBASE_REQUIRED_NOTICE
                )
            }

            val lower = cleaned.lowercase()
                .replace("á", "a")
                .replace("é", "e")
                .replace("í", "i")
                .replace("ó", "o")
                .replace("ú", "u")
                .replace("ñ", "n")

            val matchedMoodQueries = moodHints.entries
                .filter { (keyword, _) -> lower.contains(keyword) }
                .flatMap { it.value }

            val subjectTokens = cleaned
                .replace(Regex("[^\\p{L}\\p{N}\\s&+-]"), " ")
                .split(Regex("\\s+"))
                .filter { token ->
                    val norm = token.lowercase()
                    norm.length >= 2 && norm !in fillerWords
                }

            val subject = subjectTokens.joinToString(" ").trim()
            val queries = buildList {
                if (subject.isNotBlank()) {
                    add("$subject mix")
                    add("$subject mejores canciones")
                }
                addAll(matchedMoodQueries)
                if (isEmpty()) {
                    add(cleaned)
                    add("exitos musicales variados")
                }
            }.distinct().take(4)

            val displayTopic = subject.ifBlank { cleaned }.take(38)
            val reply = "Soy DJ Sony, buscando \"$displayTopic\" para armar tu mix."

            return AiDjPlanResult(
                vibeTitle = "DJ Sony: $displayTopic",
                djReply = reply,
                searchQueries = queries,
                isFirebaseBacked = false,
                notice = FIREBASE_REQUIRED_NOTICE
            )
        }
    }
}
