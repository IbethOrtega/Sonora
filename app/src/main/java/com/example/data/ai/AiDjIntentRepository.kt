package com.example.data.ai

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

/**
 * Single AI boundary for Voice DJ intent parsing.
 * When Firebase AI Logic is enabled, this interface can be backed by Gemini (`Firebase.ai`).
 */
interface AiDjIntentRepository {
    suspend fun planVoiceMix(spokenPrompt: String): AiDjPlanResult
}

/**
 * Local placeholder implementation used when Firebase setup was declined.
 * Clearly marks responses as local placeholders while extracting meaningful artist/genre
 * queries so Voice DJ playback and queue curation remain testable offline/without Firebase.
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
