package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MotionPhotosAuto
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SpatialAudio
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.R
import com.example.model.AccentPreset
import com.example.model.AppIconVariant
import com.example.model.AudioQuality
import com.example.model.EqPreset
import com.example.model.GlassStyle
import com.example.ui.components.GlassCard
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraSuccess
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.viewmodel.SonoraViewModel
import com.example.ui.viewmodel.UpdateCheckUiState
import com.example.util.AppIconManager
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class SettingsPage(val title: String, val subtitle: String) {
    ROOT("Configuración", "Submenús de audio, apariencia y biblioteca"),
    AUDIO_QUALITY("Calidad de audio", "Dolby Atmos, Audio Espacial, AutoMix y ecualizador"),
    APPEARANCE("Apariencia e icono", "Personaliza tu icono, gama de colores y cristal"),
    STORAGE_BACKUP("Descargas y respaldo", "Almacenamiento sin conexión, caché y copias JSON"),
    ABOUT("Acerca de Sonora", "Creadora, repositorio en GitHub y actualizaciones")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SonoraViewModel,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onOpenAuth: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val accountSession by viewModel.accountSession.collectAsState()
    val isSyncingYt by viewModel.isSyncingYt.collectAsState()
    val sleepTimerRemainingSec by viewModel.sleepTimerRemainingSec.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val updateCheckState by viewModel.updateCheckState.collectAsState()
    val accent = LocalSonoraAccent.current
    val uriHandler = LocalUriHandler.current

    var currentPage by remember { mutableStateOf(SettingsPage.ROOT) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = currentPage != SettingsPage.ROOT) {
        currentPage = SettingsPage.ROOT
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportLibraryBackup(uri) { msg -> statusBanner = msg }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLibraryBackup(uri) { msg -> statusBanner = msg }
        }
    }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(3000L)
            statusBanner = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("settings_screen")
    ) {
        // Persistent Top Header with Smart Back Navigation (Submenu -> Root -> Previous Tab)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (currentPage != SettingsPage.ROOT) {
                        currentPage = SettingsPage.ROOT
                    } else {
                        onBack()
                    }
                },
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
                    text = currentPage.title,
                    style = MaterialTheme.typography.displayLarge,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = currentPage.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (statusBanner != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accent.copy(alpha = 0.22f),
                border = BorderStroke(0.8.dp, accent.copy(alpha = 0.65f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusBanner.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        AnimatedContent(
            targetState = currentPage,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "settings_submenu_transition",
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                SettingsPage.ROOT -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomContentPadding)
                    ) {
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

                        // Navigable Submenus Card
                        item {
                            Spacer(Modifier.height(16.dp))
                            SettingsSectionTitle("Preferencias y submenús")
                            val audioSummary = buildList {
                                add(settings.audioQuality.label)
                                if (settings.dolbyAtmosEnabled) add("Dolby Atmos")
                                if (settings.spatialAudioEnabled) add("Audio Espacial")
                                if (settings.autoMixEnabled) add("AutoMix")
                            }.joinToString(" • ")

                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    SettingsSubmenuRow(
                                        icon = Icons.Rounded.Headphones,
                                        title = "Calidad de audio y sonido",
                                        subtitle = audioSummary,
                                        badgeText = if (settings.dolbyAtmosEnabled) "ATMOS" else if (settings.spatialAudioEnabled) "360°" else null,
                                        accent = accent,
                                        tag = "settings_submenu_audio",
                                        onClick = { currentPage = SettingsPage.AUDIO_QUALITY }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )
                                    SettingsSubmenuRow(
                                        icon = Icons.Rounded.Palette,
                                        title = "Apariencia, icono y cristal",
                                        subtitle = "${settings.appIconVariant.label} • ${settings.glassStyle.label}",
                                        badgeText = null,
                                        accent = accent,
                                        tag = "settings_submenu_appearance",
                                        onClick = { currentPage = SettingsPage.APPEARANCE }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )
                                    SettingsSubmenuRow(
                                        icon = Icons.Rounded.Folder,
                                        title = "Descargas, respaldo y caché",
                                        subtitle = "${downloadedTracks.size} descargadas • Copias JSON",
                                        badgeText = null,
                                        accent = accent,
                                        tag = "settings_submenu_storage",
                                        onClick = { currentPage = SettingsPage.STORAGE_BACKUP }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )
                                    SettingsSubmenuRow(
                                        icon = Icons.Rounded.Info,
                                        title = "Acerca de Sonora",
                                        subtitle = "v${BuildConfig.VERSION_NAME} • IbethOrtega/Sonora",
                                        badgeText = if (updateCheckState is UpdateCheckUiState.UpdateAvailable) "NUEVA" else null,
                                        accent = accent,
                                        tag = "settings_submenu_about",
                                        onClick = { currentPage = SettingsPage.ABOUT }
                                    )
                                }
                            }
                        }

                        // Quick Audio Access Toggles directly visible on Root too
                        item {
                            Spacer(Modifier.height(16.dp))
                            SettingsSectionTitle("Accesos rápidos de sonido")
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.SurroundSound,
                                        title = "Dolby Atmos",
                                        subtitle = "Habilitar sonido envolvente y profundidad de escenario",
                                        checked = settings.dolbyAtmosEnabled,
                                        accent = accent,
                                        tag = "root_switch_dolby_atmos",
                                        onCheckedChange = { viewModel.setDolbyAtmosEnabled(it) }
                                    )
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.SpatialAudio,
                                        title = "Audio Espacial",
                                        subtitle = "Amplitud sonora 360° y respuesta envolvente",
                                        checked = settings.spatialAudioEnabled,
                                        accent = accent,
                                        tag = "root_switch_spatial_audio",
                                        onCheckedChange = { viewModel.setSpatialAudioEnabled(it) }
                                    )
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.AutoAwesome,
                                        title = "Mezcla Automática (AutoMix)",
                                        subtitle = "Calcula y mezcla automáticamente cada transición",
                                        checked = settings.autoMixEnabled,
                                        accent = accent,
                                        tag = "root_switch_automix",
                                        onCheckedChange = { viewModel.setAutoMixEnabled(it) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                }

                SettingsPage.AUDIO_QUALITY -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("settings_audio_quality_page"),
                        contentPadding = PaddingValues(bottom = bottomContentPadding)
                    ) {
                        item {
                            SettingsSectionTitle("Inmersión y mezcla inteligente")
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.SurroundSound,
                                        title = "Dolby Atmos",
                                        subtitle = "Activa sonido multidimensional, claridad vocal y atmósfera envolvente",
                                        checked = settings.dolbyAtmosEnabled,
                                        accent = accent,
                                        tag = "switch_dolby_atmos",
                                        onCheckedChange = { viewModel.setDolbyAtmosEnabled(it) }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.SpatialAudio,
                                        title = "Audio Espacial",
                                        subtitle = "Proyección acústica 360° en tiempo real para audífonos y bocinas",
                                        checked = settings.spatialAudioEnabled,
                                        accent = accent,
                                        tag = "switch_spatial_audio",
                                        onCheckedChange = { viewModel.setSpatialAudioEnabled(it) }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.AutoAwesome,
                                        title = "Mezcla Automática (AutoMix)",
                                        subtitle = "Analiza cada canción para calcular la duración y punto exacto del crossfade",
                                        checked = settings.autoMixEnabled,
                                        accent = accent,
                                        tag = "switch_automix_enabled",
                                        onCheckedChange = { viewModel.setAutoMixEnabled(it) }
                                    )
                                }
                            }
                        }

                        item {
                            Spacer(Modifier.height(16.dp))
                            SettingsSectionTitle("Calidad de transmisión")
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
                                            text = "Fidelidad de streaming",
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
                                }
                            }
                        }

                        item {
                            Spacer(Modifier.height(16.dp))
                            SettingsSectionTitle("Ecualizador Pro y reproducción")
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.Equalizer,
                                        title = "Ecualizador de audio",
                                        subtitle = "Ajusta frecuencias y graves en tiempo real",
                                        checked = settings.eqEnabled,
                                        accent = accent,
                                        tag = "switch_eq_enabled",
                                        onCheckedChange = { viewModel.setEqEnabled(it) }
                                    )

                                    if (settings.eqEnabled) {
                                        Spacer(Modifier.height(8.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            EqPreset.entries.forEach { preset ->
                                                val selected = settings.eqPreset == preset
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = { viewModel.setEqPreset(preset) },
                                                    label = { Text(preset.label) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = accent.copy(alpha = 0.28f),
                                                        selectedLabelColor = SonoraTextPrimary,
                                                        containerColor = Color.White.copy(alpha = 0.06f),
                                                        labelColor = SonoraTextSecondary
                                                    ),
                                                    modifier = Modifier.testTag("eq_preset_${preset.name}")
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(12.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Refuerzo de graves (Bass Boost)",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraTextPrimary
                                            )
                                            Text(
                                                text = "${settings.bassBoostStrength / 10}%",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = accent
                                            )
                                        }
                                        Slider(
                                            value = settings.bassBoostStrength.toFloat(),
                                            onValueChange = { viewModel.setBassBoostStrength(it.toInt()) },
                                            valueRange = 0f..1000f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = accent,
                                                activeTrackColor = accent
                                            ),
                                            modifier = Modifier.testTag("bass_boost_slider")
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )

                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.VolumeUp,
                                        title = "Normalizador de volumen",
                                        subtitle = "Equilibra la potencia entre canciones suaves y fuertes",
                                        checked = settings.normalizeVolume,
                                        accent = accent,
                                        tag = "switch_normalize_volume",
                                        onCheckedChange = { viewModel.setNormalizeVolume(it) }
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
                                            val isSelected = minutes == null && sleepTimerRemainingSec == null
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
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                }

                SettingsPage.APPEARANCE -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("settings_appearance_page"),
                        contentPadding = PaddingValues(bottom = bottomContentPadding)
                    ) {
                        item {
                            SettingsSectionTitle("Apariencia e icono")
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Palette, contentDescription = null, tint = accent)
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Icono de la aplicación",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = SonoraTextPrimary
                                            )
                                            Text(
                                                text = "Elige el color de tu icono estilo ondas",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraTextSecondary
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(AppIconVariant.entries) { variant ->
                                            val selected = settings.appIconVariant == variant
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .clickable {
                                                        val ok = AppIconManager.applyVariant(context, variant)
                                                        viewModel.setAppIconVariant(variant)
                                                        statusBanner = if (ok) {
                                                            "Icono cambiado a ${variant.label}"
                                                        } else {
                                                            "Preferencia guardada (${variant.label})"
                                                        }
                                                    }
                                                    .padding(6.dp)
                                                    .testTag("app_icon_variant_${variant.name}")
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(58.dp)
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(Color(variant.bgHex))
                                                        .then(
                                                            if (selected) {
                                                                Modifier.border(2.5.dp, Color.White, RoundedCornerShape(16.dp))
                                                            } else {
                                                                Modifier.border(0.8.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                                                            }
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    SpotifyWaveIconPreview(
                                                        discColor = Color(variant.discHex),
                                                        waveColor = Color(variant.waveHex),
                                                        modifier = Modifier.size(40.dp)
                                                    )
                                                }
                                                Spacer(Modifier.height(5.dp))
                                                Text(
                                                    text = variant.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (selected) SonoraTextPrimary else SonoraTextSecondary
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 14.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accent)
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Gama amplia de colores",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = SonoraTextPrimary
                                            )
                                            Text(
                                                text = "Elige un preset o desliza por el espectro completo",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraTextSecondary
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        AccentPreset.entries.forEach { preset ->
                                            val selected = settings.customAccentHex == null && settings.accentPreset == preset
                                            val swatchColor = Color(preset.primaryHex)
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier
                                                    .width(58.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { viewModel.setAccentPreset(preset) }
                                                    .padding(4.dp)
                                                    .testTag("accent_swatch_${preset.name}")
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
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
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(Modifier.height(3.dp))
                                                Text(
                                                    text = preset.label.substringBefore(" "),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (selected) SonoraTextPrimary else SonoraTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        text = "Selector de espectro libre",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SonoraTextSecondary
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    ColorSpectrumSlider(
                                        currentColor = accent,
                                        onColorSelected = { chosen ->
                                            val hexLong = chosen.toArgb().toLong() and 0xFFFFFFFFL
                                            viewModel.setCustomAccentHex(hexLong)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("custom_color_spectrum_slider")
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )

                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.MotionPhotosAuto,
                                        title = "Portadas animadas en reproductor",
                                        subtitle = "Muestra portadas a pantalla completa con movimiento estilo Apple Music",
                                        checked = settings.animatedArtworkEnabled,
                                        accent = accent,
                                        tag = "switch_animated_artwork",
                                        onCheckedChange = { viewModel.setAnimatedArtworkEnabled(it) }
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
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
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                }

                SettingsPage.STORAGE_BACKUP -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("settings_storage_page"),
                        contentPadding = PaddingValues(bottom = bottomContentPadding)
                    ) {
                        item {
                            SettingsSectionTitle("Descargas, respaldo y caché")
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.Wifi,
                                        title = "Descargar solo por Wi-Fi",
                                        subtitle = "Evita consumo de datos móviles al guardar canciones offline",
                                        checked = settings.wifiOnlyDownloads,
                                        accent = accent,
                                        tag = "switch_wifi_only_downloads",
                                        onCheckedChange = { viewModel.setWifiOnlyDownloads(it) }
                                    )

                                    SettingsActionRow(
                                        icon = Icons.Rounded.Backup,
                                        title = "Exportar copia de seguridad (JSON)",
                                        subtitle = "Guarda tus me gusta y playlists locales en un archivo",
                                        accent = accent,
                                        tag = "export_backup_button",
                                        onClick = { exportBackupLauncher.launch("sonora_respaldo.json") }
                                    )

                                    SettingsActionRow(
                                        icon = Icons.Rounded.Restore,
                                        title = "Restaurar copia de seguridad",
                                        subtitle = "Importa playlists y canciones desde un archivo JSON",
                                        accent = accent,
                                        tag = "import_backup_button",
                                        onClick = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) }
                                    )

                                    val offlineMb = downloadedTracks.sumOf { it.fileSizeBytes } / (1024f * 1024f)
                                    if (downloadedTracks.isNotEmpty()) {
                                        SettingsActionRow(
                                            icon = Icons.Rounded.DeleteSweep,
                                            title = "Eliminar todas las descargas (${downloadedTracks.size})",
                                            subtitle = "Libera %.1f MB de almacenamiento offline".format(offlineMb),
                                            accent = accent,
                                            tag = "clear_offline_downloads_button",
                                            onClick = {
                                                viewModel.clearAllDownloads()
                                                statusBanner = "Descargas eliminadas"
                                            }
                                        )
                                    }

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
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                }

                SettingsPage.ABOUT -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("settings_about_page"),
                        contentPadding = PaddingValues(bottom = bottomContentPadding)
                    ) {
                        item {
                            val aboutTitle = stringResource(R.string.about_section_title)
                            val appVersion = "Sonora Music • v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})"
                            val creatorText = stringResource(R.string.about_creator)
                            val githubButtonLabel = stringResource(R.string.about_github_button)
                            val githubUrl = stringResource(R.string.about_github_url)

                            SettingsSectionTitle(aboutTitle)
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .testTag("settings_about_card"),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(accent.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Info,
                                                contentDescription = null,
                                                tint = accent,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = appVersion,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = SonoraTextPrimary
                                            )
                                            Text(
                                                text = "Reproductor sin anuncios conectado a YouTube Music",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraTextSecondary
                                            )
                                        }
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = Color.White.copy(alpha = 0.08f)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.10f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Person,
                                                contentDescription = null,
                                                tint = SonoraTextPrimary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = creatorText,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = SonoraTextPrimary
                                            )
                                            Text(
                                                text = "IbethOrtega/Sonora",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraTextTertiary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(14.dp))

                                    when (val upd = updateCheckState) {
                                        is UpdateCheckUiState.UpdateAvailable -> {
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = SonoraSuccess.copy(alpha = 0.16f),
                                                border = BorderStroke(0.8.dp, SonoraSuccess.copy(alpha = 0.5f)),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 10.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(
                                                        text = "Nueva versión disponible: ${upd.release.releaseName}",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = SonoraTextPrimary
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    Button(
                                                        onClick = {
                                                            val url = upd.release.apkDownloadUrl ?: upd.release.htmlUrl
                                                            runCatching { uriHandler.openUri(url) }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = SonoraSuccess),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text("Descargar actualización")
                                                    }
                                                }
                                            }
                                        }
                                        is UpdateCheckUiState.UpToDate -> {
                                            Text(
                                                text = "Tienes la versión más reciente (${upd.currentVersion})",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraSuccess,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                        }
                                        is UpdateCheckUiState.Error -> {
                                            Text(
                                                text = upd.message,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraTextSecondary,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                        }
                                        else -> Unit
                                    }

                                    Button(
                                        onClick = { viewModel.checkForUpdates() },
                                        enabled = updateCheckState !is UpdateCheckUiState.Checking,
                                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("about_check_updates_button")
                                    ) {
                                        if (updateCheckState is UpdateCheckUiState.Checking) {
                                            CircularProgressIndicator(
                                                color = Color.White,
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Buscando...", style = MaterialTheme.typography.labelLarge)
                                        } else {
                                            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Buscar actualizaciones", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    OutlinedButton(
                                        onClick = {
                                            runCatching { uriHandler.openUri(githubUrl) }
                                                .onFailure {
                                                    statusBanner = "No se pudo abrir el enlace del repositorio"
                                                }
                                        },
                                        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("about_github_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Code,
                                            contentDescription = null,
                                            tint = accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = githubButtonLabel,
                                            color = SonoraTextPrimary,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = null,
                                            tint = SonoraTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSubmenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String?,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.18f))
                .border(0.7.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!badgeText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = accent.copy(alpha = 0.24f),
                        border = BorderStroke(0.6.dp, accent.copy(alpha = 0.65f))
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = "Abrir $title",
            tint = SonoraTextTertiary,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun SpotifyWaveIconPreview(
    discColor: Color,
    waveColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(color = discColor, radius = w * 0.46f, center = Offset(w / 2f, h / 2f))

        val stroke1 = Path().apply {
            moveTo(w * 0.25f, h * 0.37f)
            quadraticTo(w * 0.49f, h * 0.28f, w * 0.77f, h * 0.40f)
        }
        val stroke2 = Path().apply {
            moveTo(w * 0.29f, h * 0.50f)
            quadraticTo(w * 0.49f, h * 0.43f, w * 0.72f, h * 0.52f)
        }
        val stroke3 = Path().apply {
            moveTo(w * 0.33f, h * 0.62f)
            quadraticTo(w * 0.49f, h * 0.57f, w * 0.67f, h * 0.64f)
        }

        drawPath(stroke1, color = waveColor, style = Stroke(width = w * 0.085f, cap = StrokeCap.Round))
        drawPath(stroke2, color = waveColor, style = Stroke(width = w * 0.072f, cap = StrokeCap.Round))
        drawPath(stroke3, color = waveColor, style = Stroke(width = w * 0.062f, cap = StrokeCap.Round))
    }
}

@Composable
private fun ColorSpectrumSlider(
    currentColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val spectrumColors = remember {
        listOf(
            Color(0xFFFF2D55),
            Color(0xFFFF6B4A),
            Color(0xFFFFB300),
            Color(0xFF30D158),
            Color(0xFF1DB954),
            Color(0xFF32D7E8),
            Color(0xFF0A84FF),
            Color(0xFF7B61FF),
            Color(0xFFBF5AF2),
            Color(0xFFFF375F)
        )
    }

    var widthPx by remember { mutableIntStateOf(1) }
    val thumbSize = 24.dp
    val thumbSizePx = with(LocalDensity.current) { thumbSize.toPx() }
    val horizontalPadPx = with(LocalDensity.current) { 5.dp.toPx() }
    val initialFraction = remember(currentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColor.toArgb(), hsv)
        (hsv[0] / 360f).coerceIn(0f, 1f)
    }
    var thumbFraction by remember { mutableFloatStateOf(initialFraction) }
    LaunchedEffect(initialFraction) {
        thumbFraction = initialFraction
    }

    fun pickAt(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        thumbFraction = clamped
        val hue = clamped * 360f
        val hsvColor = Color.hsv(hue = hue, saturation = 0.85f, value = 0.98f)
        onColorSelected(hsvColor)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(spectrumColors))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        pickAt(offset.x / widthPx.toFloat())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        pickAt(change.position.x / widthPx.toFloat())
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val travelPx = (widthPx - thumbSizePx - horizontalPadPx * 2f).coerceAtLeast(0f)
            val offsetX = (horizontalPadPx + thumbFraction * travelPx).roundToInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX, 0) }
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(currentColor)
                    .border(2.5.dp, Color.White, CircleShape)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Desliza o toca para cualquier tono personalizado",
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextTertiary
            )
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(currentColor)
                    .border(1.dp, Color.White, CircleShape)
            )
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
