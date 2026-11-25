package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.SpatialAudio
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.AudioOutputRoute
import com.example.model.EqPreset
import com.example.model.SonoraSettings
import com.example.ui.components.GlassCard
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickEqualizerSheet(
    settings: SonoraSettings,
    activeAudioRoute: AudioOutputRoute,
    autoMixStatus: String?,
    sleepTimerRemainingSec: Int?,
    sleepAfterCurrentTrack: Boolean,
    onDismiss: () -> Unit,
    onSetEqEnabled: (Boolean) -> Unit,
    onSetEqPreset: (EqPreset) -> Unit,
    onSetBassBoost: (Int) -> Unit,
    onSetNormalizeVolume: (Boolean) -> Unit,
    onSetDolbyAtmos: (Boolean) -> Unit,
    onSetSpatialAudio: (Boolean) -> Unit,
    onSetAutoMix: (Boolean) -> Unit,
    onSetAutoMixOverlapSeconds: (Int) -> Unit,
    onSetCrossfadeSeconds: (Int) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetPlaybackPitch: (Float) -> Unit,
    onResetSpeedAndPitch: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    onSetSleepAfterCurrentTrack: (Boolean) -> Unit,
    onOpenStatsForNerds: () -> Unit
) {
    val accent = LocalSonoraAccent.current
    com.example.ui.components.SonoraLiquidModalSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSetDolbyAtmos(!settings.dolbyAtmosEnabled) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.SurroundSound, contentDescription = null, tint = accent)
                    Column {
                        Text("Dolby Atmos", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                        Text("Sonido envolvente y escena multidimensional", style = MaterialTheme.typography.bodySmall, color = SonoraTextSecondary)
                    }
                }
                Switch(
                    checked = settings.dolbyAtmosEnabled,
                    onCheckedChange = onSetDolbyAtmos,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent),
                    modifier = Modifier.testTag("sheet_dolby_atmos_switch")
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSetSpatialAudio(!settings.spatialAudioEnabled) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.SpatialAudio, contentDescription = null, tint = accent)
                    Column {
                        Text("Audio Espacial", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                        Text("Amplitud acústica 360°", style = MaterialTheme.typography.bodySmall, color = SonoraTextSecondary)
                    }
                }
                Switch(
                    checked = settings.spatialAudioEnabled,
                    onCheckedChange = onSetSpatialAudio,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent),
                    modifier = Modifier.testTag("sheet_spatial_audio_switch")
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = accent)
                        Column {
                            Text("Fundido (Crossfade)", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                            Text(
                                text = when {
                                    settings.crossfadeSeconds < 0 -> "Automático inteligente entre pistas"
                                    settings.crossfadeSeconds == 0 -> "Desactivado (corte directo)"
                                    else -> "Mezcla suave de ${settings.crossfadeSeconds}s al cambiar"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (settings.crossfadeSeconds != 0) accent else SonoraTextSecondary
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = settings.crossfadeSeconds == 0,
                        onClick = { onSetCrossfadeSeconds(0) },
                        label = { Text("Desactivado") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        ),
                        modifier = Modifier.testTag("sheet_crossfade_off_chip")
                    )
                    FilterChip(
                        selected = settings.crossfadeSeconds < 0,
                        onClick = { onSetCrossfadeSeconds(-1) },
                        label = { Text("Auto") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        ),
                        modifier = Modifier.testTag("sheet_crossfade_auto_chip")
                    )
                }
                Slider(
                    value = when {
                        settings.crossfadeSeconds > 0 -> settings.crossfadeSeconds.toFloat()
                        settings.crossfadeSeconds < 0 -> 6f
                        else -> 0f
                    },
                    onValueChange = { value ->
                        onSetCrossfadeSeconds(value.roundToInt().coerceIn(0, 12))
                    },
                    valueRange = 0f..12f,
                    steps = 11,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.testTag("sheet_crossfade_slider")
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSetAutoMix(!settings.autoMixEnabled) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accent)
                    Column {
                        Text("Mezcla Automática (AutoMix)", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                        Text(
                            text = autoMixStatus ?: "Calcula y mezcla cada transición entre canciones",
                            style = MaterialTheme.typography.bodySmall,
                            color = SonoraTextSecondary
                        )
                    }
                }
                Switch(
                    checked = settings.autoMixEnabled,
                    onCheckedChange = onSetAutoMix,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent),
                    modifier = Modifier.testTag("sheet_automix_switch")
                )
            }

            if (settings.autoMixEnabled) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Entrada de la siguiente canción", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                            Text(
                                text = if (settings.autoMixOverlapSeconds == 0) "Automático (IA)" else "${settings.autoMixOverlapSeconds}s antes del final",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent
                            )
                        }
                        FilterChip(
                            selected = settings.autoMixOverlapSeconds == 0,
                            onClick = { onSetAutoMixOverlapSeconds(0) },
                            label = { Text("Auto") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.28f),
                                selectedLabelColor = SonoraTextPrimary
                            ),
                            modifier = Modifier.testTag("sheet_automix_auto_chip")
                        )
                    }
                    Slider(
                        value = if (settings.autoMixOverlapSeconds == 0) 6f else settings.autoMixOverlapSeconds.toFloat(),
                        onValueChange = { onSetAutoMixOverlapSeconds(it.roundToInt().coerceIn(2, 12)) },
                        valueRange = 2f..12f,
                        steps = 9,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                        modifier = Modifier.testTag("sheet_automix_overlap_slider")
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = accent)
                    Text("Velocidad y tono", style = MaterialTheme.typography.titleMedium, color = SonoraTextPrimary)
                }
                if (abs(settings.playbackSpeed - 1.0f) > 0.02f || abs(settings.playbackPitch - 1.0f) > 0.02f) {
                    TextButton(onClick = onResetSpeedAndPitch) {
                        Text("Restablecer", color = accent)
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { speedPreset ->
                    FilterChip(
                        selected = abs(settings.playbackSpeed - speedPreset) < 0.03f,
                        onClick = { onSetPlaybackSpeed(speedPreset) },
                        label = { Text("${"%.2f".format(speedPreset)}×") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        )
                    )
                }
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Velocidad", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                    Text("${"%.2f".format(settings.playbackSpeed)}×", style = MaterialTheme.typography.labelLarge, color = accent)
                }
                Slider(
                    value = settings.playbackSpeed,
                    onValueChange = { onSetPlaybackSpeed((it * 20f).roundToInt() / 20f) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.testTag("sheet_speed_slider")
                )
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tono (Pitch)", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                    Text("${"%.2f".format(settings.playbackPitch)}×", style = MaterialTheme.typography.labelLarge, color = accent)
                }
                Slider(
                    value = settings.playbackPitch,
                    onValueChange = { onSetPlaybackPitch((it * 20f).roundToInt() / 20f) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.testTag("sheet_pitch_slider")
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = accent)
                    Text("Temporizador rápido", style = MaterialTheme.typography.titleMedium, color = SonoraTextPrimary)
                }
                if (sleepTimerRemainingSec != null) {
                    Text("%d:%02d".format(sleepTimerRemainingSec / 60, sleepTimerRemainingSec % 60), style = MaterialTheme.typography.labelLarge, color = accent)
                } else if (sleepAfterCurrentTrack) {
                    Text("Al finalizar pista", style = MaterialTheme.typography.labelMedium, color = accent)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = sleepTimerRemainingSec == null && !sleepAfterCurrentTrack,
                    onClick = {
                        onSetSleepTimer(null)
                        onSetSleepAfterCurrentTrack(false)
                    },
                    label = { Text("Off") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.28f),
                        selectedLabelColor = SonoraTextPrimary
                    )
                )
                listOf(15, 30, 60).forEach { mins ->
                    FilterChip(
                        selected = false,
                        onClick = { onSetSleepTimer(mins) },
                        label = { Text("${mins}m") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        )
                    )
                }
                FilterChip(
                    selected = sleepAfterCurrentTrack,
                    onClick = { onSetSleepAfterCurrentTrack(!sleepAfterCurrentTrack) },
                    label = { Text("Al terminar canción") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.28f),
                        selectedLabelColor = SonoraTextPrimary
                    ),
                    modifier = Modifier.testTag("sheet_sleep_after_track_chip")
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = accent)
                    Column {
                        Text("Ecualizador Pro", style = MaterialTheme.typography.titleLarge, color = SonoraTextPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.Headphones, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
                            Text("Perfil guardado para ${activeAudioRoute.label}", style = MaterialTheme.typography.labelSmall, color = SonoraTextSecondary)
                        }
                    }
                }
                Switch(
                    checked = settings.eqEnabled,
                    onCheckedChange = onSetEqEnabled,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EqPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.eqPreset == preset,
                        enabled = settings.eqEnabled,
                        onClick = { onSetEqPreset(preset) },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.28f),
                            selectedLabelColor = SonoraTextPrimary
                        )
                    )
                }
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Refuerzo de graves", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                    Text("${settings.bassBoostStrength / 10}%", style = MaterialTheme.typography.labelLarge, color = accent)
                }
                Slider(
                    value = settings.bassBoostStrength.toFloat(),
                    onValueChange = { onSetBassBoost(it.toInt()) },
                    enabled = settings.eqEnabled,
                    valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSetNormalizeVolume(!settings.normalizeVolume) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Normalizar volumen", style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
                    Text("Nivela la potencia entre pistas", style = MaterialTheme.typography.bodyMedium, color = SonoraTextSecondary)
                }
                Switch(
                    checked = settings.normalizeVolume,
                    onCheckedChange = onSetNormalizeVolume,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                )
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sheet_stats_for_nerds_row"),
                shape = RoundedCornerShape(16.dp),
                onClick = onOpenStatsForNerds
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Estadísticas para geeks", style = MaterialTheme.typography.titleMedium, color = SonoraTextPrimary)
                        Text("Códec, bitrate, muestreo, sesión y búfer en vivo", style = MaterialTheme.typography.bodySmall, color = SonoraTextSecondary)
                    }
                }
            }
        }
    }
}
