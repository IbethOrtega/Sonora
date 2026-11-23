package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.model.DjVoiceCatalog
import com.example.player.DjLiveState
import com.example.player.DjVoiceEngine
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VoiceDjSheet(
    djLiveState: DjLiveState,
    selectedVoiceName: String = "Aoede",
    onSelectVoice: (String) -> Unit = {},
    onPreviewVoice: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSubmitPrompt: (String) -> Unit
) {
    val context = LocalContext.current
    val accent = LocalSonoraAccent.current

    var isListening by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf(djLiveState.lastVoicePrompt) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var awaitingSubmitResult by remember { mutableStateOf(false) }

    LaunchedEffect(
        awaitingSubmitResult,
        djLiveState.isLoading,
        djLiveState.aiError,
        djLiveState.isActive
    ) {
        if (awaitingSubmitResult && !djLiveState.isLoading) {
            awaitingSubmitResult = false
            if (djLiveState.aiError == null && djLiveState.isActive) {
                onDismiss()
            }
        }
    }

    fun submitCurrentPrompt(text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank() || djLiveState.isLoading) return
        promptText = cleaned
        awaitingSubmitResult = true
        onSubmitPrompt(cleaned)
    }

    val speechAvailable = remember(context) {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    val speechRecognizer = remember(context, speechAvailable) {
        if (speechAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    fun startSpeechRecognition() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            voiceStatus = "Reconocimiento de voz no disponible en este dispositivo; escribe tu petición abajo."
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ej: Oye DJ Sony, pon un mix de reggaetón clásico")
        }
        voiceStatus = "Escuchando... di qué género o artista quieres"
        isListening = true
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                isListening = false
                voiceStatus = "No se pudo iniciar el micrófono. Escribe tu mix abajo."
            }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                voiceStatus = "Escuchando ahora... habla claro"
            }

            override fun onBeginningOfSpeech() {
                voiceStatus = "Captando tu petición..."
            }

            override fun onRmsChanged(rmsdB: Float) {
                rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isListening = false
                rmsLevel = 0f
                voiceStatus = "Procesando petición..."
            }

            override fun onError(error: Int) {
                isListening = false
                rmsLevel = 0f
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No alcancé a escucharte bien. Toca el micrófono o escribe abajo."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Se necesita permiso de micrófono para escuchar tu voz."
                    else -> "No se pudo capturar la voz. Puedes escribir o elegir una sugerencia."
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                rmsLevel = 0f
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!spoken.isNullOrBlank()) {
                    voiceStatus = "Pedido capturado: \"$spoken\""
                    submitCurrentPrompt(spoken)
                } else {
                    voiceStatus = "No se detectó texto. Intenta de nuevo."
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!partial.isNullOrBlank()) {
                    promptText = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        onDispose {
            runCatching {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSpeechRecognition()
        } else {
            voiceStatus = "Permiso de micrófono denegado. Puedes escribir tu mezcla manualmente abajo."
        }
    }

    fun toggleListening() {
        if (isListening) {
            isListening = false
            rmsLevel = 0f
            runCatching { speechRecognizer?.stopListening() }
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startSpeechRecognition()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "voice_orb")
    val idlePulse by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "voice_orb_scale"
    )
    val orbScale = if (isListening) (1.06f + rmsLevel * 0.24f) else 1.0f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF514141E),
        contentColor = SonoraTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("voice_dj_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                    Column {
                        Text(
                            text = "DJ Sony",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = "Di: \"Oye DJ Sony, quiero un mix de...\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = SonoraTextSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar DJ Sony", tint = SonoraTextSecondary)
                }
            }

            // DJ Sony Status / Diagnostic Banner
            val hasAiError = !djLiveState.aiError.isNullOrBlank()
            var showTechnicalDetail by remember(djLiveState.aiTechnicalDetail) { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (hasAiError) Color(0x33FF453A) else Color(0xFF17171C),
                border = BorderStroke(
                    1.dp,
                    if (hasAiError) Color(0x88FF453A) else accent.copy(alpha = 0.45f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("voice_dj_ai_notice")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (hasAiError) Icons.Rounded.Info else Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = if (hasAiError) Color(0xFFFF6961) else accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = djLiveState.aiError
                                ?: djLiveState.aiNotice
                                ?: if (djLiveState.voiceEngine == DjVoiceEngine.DEVICE) {
                                    "Usando voz del dispositivo (sin conexión rápida)"
                                } else {
                                    "DJ Sony con voz natural • Pide artistas, géneros o momentos"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasAiError) Color(0xFFFFD6D3) else SonoraTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        if (!djLiveState.aiTechnicalDetail.isNullOrBlank()) {
                            Text(
                                text = if (showTechnicalDetail) "Ocultar" else "Ver detalle",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = accent,
                                modifier = Modifier
                                    .clickable { showTechnicalDetail = !showTechnicalDetail }
                                    .testTag("voice_dj_detail_toggle")
                            )
                        }
                    }
                    if (showTechnicalDetail && !djLiveState.aiTechnicalDetail.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = djLiveState.aiTechnicalDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = SonoraTextSecondary.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Interactive Voice Orb Button
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .scale(if (isListening) orbScale * idlePulse else 1f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = if (isListening) {
                                listOf(accent, accent.copy(alpha = 0.35f))
                            } else {
                                listOf(accent.copy(alpha = 0.85f), Color(0xFF232334))
                            }
                        )
                    )
                    .border(
                        width = if (isListening) 2.dp else 1.dp,
                        color = Color.White.copy(alpha = if (isListening) 0.72f else 0.28f),
                        shape = CircleShape
                    )
                    .clickable { toggleListening() }
                    .testTag("voice_dj_mic_orb"),
                contentAlignment = Alignment.Center
            ) {
                if (djLiveState.isLoading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.6.dp, modifier = Modifier.size(34.dp))
                } else {
                    Icon(
                        imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = if (isListening) "Detener escucha" else "Hablar con DJ",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Text(
                text = when {
                    djLiveState.isLoading -> "DJ Sony está buscando temas..."
                    !voiceStatus.isNullOrBlank() -> voiceStatus!!
                    isListening -> "Escuchando..."
                    else -> "Toca el micrófono y pide tu mix por artista, género o ambiente"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isListening || djLiveState.isLoading) accent else SonoraTextSecondary,
                textAlign = TextAlign.Center
            )

            // Quick suggested voice prompts
            val samplePrompts = remember {
                listOf(
                    "Mix de reggaetón clásico",
                    "Éxitos de Bad Bunny y Feid",
                    "Música chill para estudiar",
                    "Rock en español 2000s"
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                samplePrompts.forEach { sample ->
                    FilterChip(
                        selected = promptText == sample,
                        onClick = { submitCurrentPrompt(sample) },
                        label = { Text(sample) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = SonoraTextSecondary
                        )
                    )
                }
            }

            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("O escribe tu petición para DJ Sony") },
                placeholder = { Text("Ej: Oye DJ Sony, pon algo de salsa o pop para entrenar") },
                singleLine = false,
                maxLines = 2,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { submitCurrentPrompt(promptText) }
                ),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    focusedLabelColor = accent,
                    cursorColor = accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.20f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("voice_dj_prompt_input")
            )

            // Compact DJ Sony voice selector & preview right inside the sheet
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voz de DJ Sony",
                        style = MaterialTheme.typography.labelLarge,
                        color = SonoraTextPrimary
                    )
                    OutlinedButton(
                        onClick = { onPreviewVoice(selectedVoiceName) },
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("voice_dj_preview_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RecordVoiceOver,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Probar voz",
                            style = MaterialTheme.typography.labelMedium,
                            color = SonoraTextPrimary
                        )
                    }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(DjVoiceCatalog.voices, key = { it.id }) { voice ->
                        val isSelected = voice.id.equals(selectedVoiceName, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectVoice(voice.id) },
                            label = {
                                Text("${voice.displayName} • ${voice.toneDescription.substringBefore(" •")}")
                            },
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = Color.White.copy(alpha = 0.14f),
                                selectedBorderColor = accent
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1F1F26),
                                selectedLabelColor = SonoraTextPrimary,
                                containerColor = Color(0xFF141418),
                                labelColor = SonoraTextSecondary
                            ),
                            modifier = Modifier.testTag("voice_dj_chip_${voice.id}")
                        )
                    }
                }
            }

            Button(
                onClick = { submitCurrentPrompt(promptText) },
                enabled = promptText.isNotBlank() && !djLiveState.isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("voice_dj_submit_button")
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (djLiveState.isLoading) "Armando tu mix..." else "Armar mix ahora",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
