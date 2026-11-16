package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.AccentPreset
import com.example.model.AudioQuality
import com.example.model.GlassStyle
import com.example.ui.components.GlassCard
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraSuccess
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.viewmodel.SonoraViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SonoraViewModel,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onOpenAuth: () -> Unit,
    onSignOut: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val accountSession by viewModel.accountSession.collectAsState()
    val isSyncingYt by viewModel.isSyncingYt.collectAsState()
    val sleepTimerRemainingSec by viewModel.sleepTimerRemainingSec.collectAsState()
    val accent = LocalSonoraAccent.current

    var statusBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(2500L)
            statusBanner = null
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Volver",
                        tint = SonoraTextPrimary
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Configuración",
                        style = MaterialTheme.typography.displayLarge,
                        color = SonoraTextPrimary
                    )
                    Text(
                        text = "Personaliza tu audio, apariencia y cuenta",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary
                    )
                }
            }
        }

        if (statusBanner != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = accent.copy(alpha = 0.22f),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = statusBanner.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // Section: Google / YouTube Music Account
        item {
            SettingsSectionTitle("Cuenta y sincronización")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (accountSession != null && accountSession!!.avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = accountSession!!.avatarUrl,
                                contentDescription = accountSession!!.accountName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountCircle,
                                    contentDescription = null,
                                    tint = if (accountSession != null) SonoraSuccess else SonoraTextPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = accountSession?.accountName ?: "Sin cuenta conectada",
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (accountSession != null) {
                                    "Sincroniza tu historial y playlists de YouTube Music"
                                } else {
                                    "Conecta tu cuenta de Google para traer tu historial y sugerencias"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    if (accountSession == null) {
                        Button(
                            onClick = onOpenAuth,
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_signin_button")
                        ) {
                            Text("Conectar con Google / YouTube Music")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.syncYouTubeLibrary()
                                    statusBanner = "Sincronizando historial y biblioteca..."
                                },
                                enabled = !isSyncingYt,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("settings_sync_button")
                            ) {
                                Icon(Icons.Rounded.Sync, contentDescription = null, tint = SonoraTextPrimary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isSyncingYt) "Sincronizando..." else "Sincronizar", color = SonoraTextPrimary)
                            }
                            OutlinedButton(
                                onClick = onSignOut,
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("settings_signout_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Desconectar", color = accent)
                            }
                        }
                    }
                }
            }
        }

        // Section: Audio & Playback
        item {
            Spacer(Modifier.height(16.dp))
            SettingsSectionTitle("Audio y reproducción")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Calidad de transmisión",
                            style = MaterialTheme.typography.titleMedium,
                            color = SonoraTextPrimary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AudioQuality.entries.forEach { quality ->
                        val selected = settings.audioQuality == quality
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { viewModel.setAudioQuality(quality) }
                                .padding(horizontal = 6.dp, vertical = 8.dp)
                                .testTag("quality_option_${quality.name}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.setAudioQuality(quality) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = accent,
                                    unselectedColor = SonoraTextSecondary
                                )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = quality.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = SonoraTextPrimary
                                )
                                Text(
                                    text = quality.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonoraTextSecondary
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    )

                    SettingsSwitchRow(
                        icon = Icons.Rounded.Radio,
                        title = "Radio automática",
                        subtitle = "Agregar canciones relacionadas al terminar la actual",
                        checked = settings.autoplayRadio,
                        accent = accent,
                        tag = "switch_autoplay_radio",
                        onCheckedChange = { viewModel.setAutoplayRadio(it) }
                    )

                    SettingsSwitchRow(
                        icon = Icons.Rounded.HistoryToggleOff,
                        title = "Saltar silencios",
                        subtitle = "Recorta automáticamente pausas largas en el audio",
                        checked = settings.skipSilence,
                        accent = accent,
                        tag = "switch_skip_silence",
                        onCheckedChange = { viewModel.setSkipSilence(it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = accent)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Temporizador de apagado",
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                        }
                        if (sleepTimerRemainingSec != null) {
                            val mins = (sleepTimerRemainingSec!! / 60)
                            val secs = (sleepTimerRemainingSec!! % 60)
                            Text(
                                text = "%d:%02d".format(mins, secs),
                                style = MaterialTheme.typography.labelLarge,
                                color = accent
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val timerOptions = listOf(null to "Desactivado", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "60 min")
                        timerOptions.forEach { (minutes, label) ->
                            val isSelected = if (minutes == null) {
                                sleepTimerRemainingSec == null
                            } else {
                                false
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    viewModel.setSleepTimer(minutes)
                                    statusBanner = if (minutes == null) {
                                        "Temporizador desactivado"
                                    } else {
                                        "La música se pausará en $minutes minutos"
                                    }
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accent.copy(alpha = 0.24f),
                                    selectedLabelColor = SonoraTextPrimary,
                                    containerColor = Color.White.copy(alpha = 0.06f),
                                    labelColor = SonoraTextSecondary
                                ),
                                modifier = Modifier.testTag("sleep_chip_${minutes ?: 0}")
                            )
                        }
                    }
                }
            }
        }

        // Section: Appearance & Design
        item {
            Spacer(Modifier.height(16.dp))
            SettingsSectionTitle("Apariencia y diseño")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accent)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Color de acento",
                            style = MaterialTheme.typography.titleMedium,
                            color = SonoraTextPrimary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AccentPreset.entries.forEach { preset ->
                            val selected = settings.accentPreset == preset
                            val swatchColor = Color(preset.primaryHex)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.setAccentPreset(preset) }
                                    .padding(6.dp)
                                    .testTag("accent_swatch_${preset.name}")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .then(
                                            if (selected) {
                                                Modifier.border(2.5.dp, Color.White, CircleShape)
                                            } else {
                                                Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = preset.label,
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = preset.label.substringBefore(" "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) SonoraTextPrimary else SonoraTextSecondary
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    )

                    Text(
                        text = "Estilo de superficie",
                        style = MaterialTheme.typography.titleMedium,
                        color = SonoraTextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassStyle.entries.forEach { style ->
                        val selected = settings.glassStyle == style
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { viewModel.setGlassStyle(style) }
                                .padding(horizontal = 6.dp, vertical = 8.dp)
                                .testTag("glass_style_${style.name}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.setGlassStyle(style) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = accent,
                                    unselectedColor = SonoraTextSecondary
                                )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = style.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = SonoraTextPrimary
                                )
                                Text(
                                    text = style.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonoraTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Storage & Privacy
        item {
            Spacer(Modifier.height(16.dp))
            SettingsSectionTitle("Almacenamiento e historial")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    SettingsActionRow(
                        icon = Icons.Rounded.Cached,
                        title = "Limpiar caché de audio",
                        subtitle = "Fuerza la recarga de enlaces de reproducción",
                        accent = accent,
                        tag = "clear_stream_cache_button",
                        onClick = {
                            viewModel.clearStreamCache()
                            statusBanner = "Caché de enlaces de audio limpiada"
                        }
                    )
                    SettingsActionRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "Borrar historial reciente",
                        subtitle = "Limpia las canciones escuchadas recientemente",
                        accent = accent,
                        tag = "clear_play_history_button",
                        onClick = {
                            viewModel.clearPlaybackHistory()
                            statusBanner = "Historial de reproducción eliminado"
                        }
                    )
                    SettingsActionRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "Borrar historial de búsqueda",
                        subtitle = "Elimina tus búsquedas guardadas",
                        accent = accent,
                        tag = "clear_search_history_button",
                        onClick = {
                            viewModel.clearSearchHistory()
                            statusBanner = "Historial de búsqueda eliminado"
                        }
                    )
                }
            }
        }

        // About Footer
        item {
            Spacer(Modifier.height(16.dp))
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = SonoraTextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Sonora Music • v1.0",
                            style = MaterialTheme.typography.titleMedium,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = "Reproductor sin anuncios conectado a YouTube Music",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextTertiary
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = SonoraTextPrimary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accent)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = SonoraTextSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accent)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = SonoraTextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = SonoraTextSecondary)
        }
    }
}
