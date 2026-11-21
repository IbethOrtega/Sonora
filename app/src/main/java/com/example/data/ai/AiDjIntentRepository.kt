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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream

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
    val sampleRateHz: Int = 24_000
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesizedSpeech) return false
        return sampleRateHz == other.sampleRateHz && pcmBytes.contentEquals(other.pcmBytes)
    }

    override fun hashCode(): Int = 31 * pcmBytes.contentHashCode() + sampleRateHz
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
    suspend fun synthesizeSpeech(text: String): SynthesizedSpeech?
}

class NoOpDjVoiceSynthesizer : DjVoiceSynthesizer {
    override suspend fun synthesizeSpeech(text: String): SynthesizedSpeech? = null
}

/**
 * Gemini-backed Voice DJ planner using Firebase AI Logic (`Firebase.ai`) and App Check attestation.
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
                "No se pudo inicializar Firebase en este dispositivo. Verifica google-services.json y reinstala la app."
            )
        Firebase.ai(
            app = firebaseApp,
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = mixSchema
                temperature = 0.65f
            },
            systemInstruction = content {
                text(
                    "Eres el DJ en vivo inteligente de Sonora, una app de música en español. " +
                        "Interpreta peticiones por voz o texto para armar colas musicales precisas en YouTube Music. " +
                        "Prioriza los artistas, canciones, géneros, épocas o estados de ánimo exactos que pidió el usuario, " +
                        "y evita incluir covers o versiones karaoke salvo que el usuario lo pida explícitamente. " +
                        "Redacta djReply con un tono humano, cercano, cálido y natural de locutora musical en español."
                )
            }
        )
    }

    override suspend fun planVoiceMix(spokenPrompt: String): AiDjPlanResult {
        val cleaned = spokenPrompt.trim()
        require(cleaned.isNotBlank()) { "Escribe o dicta una petición musical para el DJ." }

        try {
            val response = withTimeout(45_000L) {
                model.generateContent(
                    "Petición del oyente: \"$cleaned\". " +
                        "Devuelve 3 a 5 búsquedas específicas para armar un mix coherente en YouTube Music y un saludo breve, cálido y natural de DJ en español."
                )
            }
            val rawJson = response.text
                ?: throw IllegalStateException("Gemini no devolvió respuesta para tu petición.")
            val json = JSONObject(rawJson)

            val vibeTitle = json.optString("vibeTitle").trim().ifBlank { "Mix Gemini DJ" }
            val djReply = json.optString("djReply").trim()
                .ifBlank { "Qué buena elección, ya te estoy armando este mix de $vibeTitle en Sonora." }
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
                throw IllegalStateException("Gemini no generó búsquedas válidas para este pedido.")
            }

            return AiDjPlanResult(
                vibeTitle = vibeTitle,
                djReply = djReply,
                searchQueries = queries,
                isFirebaseBacked = true,
                notice = null
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini Voice DJ request timed out", e)
            throw IllegalStateException("La conexión con Gemini tardó demasiado. Intenta de nuevo.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Voice DJ request failed", e)
            val detail = e.localizedMessage?.trim().orEmpty()
            val message = when {
                detail.contains("403") || detail.contains("App Check", ignoreCase = true) ->
                    "Error de verificación de Gemini (App Check). Detalle: $detail"
                detail.contains("network", ignoreCase = true) || detail.contains("Unable to resolve host", ignoreCase = true) ->
                    "Sin conexión para consultar a Gemini. Verifica tu internet."
                detail.isNotBlank() -> detail
                else -> "No se pudo consultar Gemini para armar el mix."
            }
            throw IllegalStateException(message, e)
        }
    }

    companion object {
        private const val TAG = "FirebaseAiDjRepo"
    }
}

/**
 * Synthesizes natural, warm human radio-host speech using Gemini 3.1 Flash TTS via Firebase AI Logic.
 */
class FirebaseAiVoiceSynthesizer(
    private val context: Context
) : DjVoiceSynthesizer {

    @OptIn(PublicPreviewAPI::class)
    private val ttsModel: GenerativeModel by lazy {
        val firebaseApp = ensureFirebaseAndAppCheck(context)
            ?: throw IllegalStateException("Firebase no está inicializado para generar voz del DJ.")
        Firebase.ai(
            app = firebaseApp,
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            modelName = "gemini-3.1-flash-tts-preview",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(voice = Voice(DEFAULT_VOICE_NAME))
            }
        )
    }

    override suspend fun synthesizeSpeech(text: String): SynthesizedSpeech? {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return null

        return try {
            val prompt = buildTtsPrompt(cleaned)
            val response = withContext(Dispatchers.IO) {
                withTimeout(18_000L) {
                    ttsModel.generateContent(prompt)
                }
            }
            extractSpeechFromParts(response.candidates.firstOrNull()?.content?.parts.orEmpty())
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gemini TTS timed out, falling back to device voice", e)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini TTS failed, falling back to device voice: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "FirebaseAiVoiceTts"
        internal const val DEFAULT_VOICE_NAME = "Aoede"

        internal fun buildTtsPrompt(script: String): String {
            return "Lee exactamente el siguiente guion con una voz humana cálida, natural, expresiva y amigable " +
                "de locutora de radio musical en español latino, con entonación fluida y pronunciando de forma natural " +
                "los nombres de artistas o canciones en otros idiomas, sin agregar ni cambiar palabras:\n$script"
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
            "Modo local de demostración: el DJ inteligente con Gemini requiere activar Firebase AI Logic en AI Studio."

        private val fillerWords = setOf(
            "oye", "hola", "dj", "sonora", "por", "favor", "porfavor", "quiero", "pon",
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
                    vibeTitle = "Mix DJ por voz (Demo local)",
                    djReply = "Modo demostración local: armé una mezcla variada con éxitos del momento.",
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
            val reply = "Modo local: buscando \"$displayTopic\" en tu catálogo para armar el mix."

            return AiDjPlanResult(
                vibeTitle = "Voz: $displayTopic",
                djReply = reply,
                searchQueries = queries,
                isFirebaseBacked = false,
                notice = FIREBASE_REQUIRED_NOTICE
            )
        }
    }
}
