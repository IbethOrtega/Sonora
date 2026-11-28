package com.example.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MotionPhotosAuto
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SpatialAudio
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.SpotifyCanvasCredentialsStore
import com.example.data.network.CanvasRepository
import com.example.model.AccentPreset
import com.example.model.AppIconVariant
import com.example.model.AudioQuality
import com.example.model.CanvasDiagnosticReport
import com.example.model.DjVoiceCatalog
import com.example.model.EqPreset
import com.example.model.GlassStyle
import com.example.model.PlayerBackdropStyle
import com.example.model.SonoraSettings
import com.example.player.DeviceTtsVoiceInfo
import com.example.player.DjLiveState
import com.example.player.DjVoiceEngine
import com.example.player.SonoraPlaybackBridge
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraIosSwitch
import com.example.ui.components.SonoraLiquidModalSheet
import com.example.ui.components.openTtsVoiceInstaller
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraSuccess
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.viewmodel.SonoraViewModel
import com.example.ui.viewmodel.UpdateCheckUiState
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal val SettingsDividerColor = Color.White.copy(alpha = 0.08f)

@Composable
internal fun SettingsSectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.8.sp
        ),
        color = LocalSonoraPalette.current.textSecondary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@Composable
internal fun SettingsHairlineDivider(horizontalPadding: Dp = 16.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = horizontalPadding),
        thickness = 0.5.dp,
        color = SettingsDividerColor
    )
}

@Composable
internal fun SettingsIconTile(
    icon: ImageVector,
    tileColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(tileColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
internal fun SettingsSubmenuRow(
    icon: ImageVector,
    tileColor: Color,
    title: String,
    valueText: String? = null,
    subtitle: String? = null,
    badgeText: String? = null,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconTile(icon = icon, tileColor = tileColor)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!badgeText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = accent.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = accent,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!valueText.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = "Abrir $title",
            tint = SonoraTextTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun SettingsSwitchRow(
    icon: ImageVector,
    tileColor: Color,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconTile(icon = icon, tileColor = tileColor)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = SonoraTextPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        SonoraIosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
internal fun SettingsActionRow(
    icon: ImageVector,
    tileColor: Color,
    title: String,
    subtitle: String? = null,
    valueText: String? = null,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconTile(icon = icon, tileColor = tileColor)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = SonoraTextPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary
                )
            }
        }
        if (!valueText.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextSecondary
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = SonoraTextTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun SettingsRadioOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = SonoraTextPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Seleccionado",
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Spacer(Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun SwaveAppIconPreview(
    variant: AppIconVariant,
    modifier: Modifier = Modifier
) {
    val bgColor = Color(variant.bgHex)
    val discColor = Color(variant.discHex)
    val waveColor = if (variant == AppIconVariant.OBSIDIAN) Color.White else Color.White
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        bgColor,
                        discColor.copy(alpha = 0.28f),
                        bgColor
                    )
                )
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)
            val radius = minOf(w, h) * 0.44f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        discColor.copy(alpha = 0.92f),
                        discColor.copy(alpha = 0.55f)
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.24f),
                radius = radius,
                center = center,
                style = Stroke(width = w * 0.03f)
            )
            val sPath = Path().apply {
                moveTo(w * 0.68f, h * 0.29f)
                cubicTo(w * 0.44f, h * 0.17f, w * 0.21f, h * 0.34f, w * 0.50f, h * 0.50f)
                cubicTo(w * 0.79f, h * 0.66f, w * 0.56f, h * 0.83f, w * 0.32f, h * 0.71f)
            }
            drawPath(
                path = sPath,
                color = waveColor,
                style = Stroke(width = w * 0.12f, cap = StrokeCap.Round)
            )
        }
    }
}

internal fun applyAppIconVariantSafely(context: Context, variant: AppIconVariant) {
    runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val mainComponent = ComponentName(pkg, "com.example.MainActivity")
        val aliases = AppIconVariant.entries.filter { it != AppIconVariant.EMERALD }
        if (variant == AppIconVariant.EMERALD) {
            pm.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            aliases.forEach { entry ->
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(pkg, entry.aliasClassName),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        } else {
            val targetExists = runCatching {
                pm.getActivityInfo(ComponentName(pkg, variant.aliasClassName), PackageManager.MATCH_DISABLED_COMPONENTS)
            }.isSuccess
            if (!targetExists) return@runCatching
            aliases.forEach { entry ->
                val state = if (entry == variant) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, entry.aliasClassName),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            }
            pm.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsAudioQualityPage(
    viewModel: SonoraViewModel,
    settings: SonoraSettings,
    sleepTimerRemainingSec: Int?,
    sleepAfterCurrentTrack: Boolean,
    accent: Color,
    bottomContentPadding: Dp,
    onStatusBanner: (String) -> Unit
) {
    val palette = LocalSonoraPalette.current
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
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.SurroundSound,
                        tileColor = Color(0xFF5856D6),
                        title = "Dolby Atmos",
                        subtitle = "Sonido multidimensional y claridad vocal",
                        checked = settings.dolbyAtmosEnabled,
                        tag = "switch_dolby_atmos",
                        onCheckedChange = { viewModel.setDolbyAtmosEnabled(it) }
                    )
                    SettingsHairlineDivider(58.dp)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.SpatialAudio,
                        tileColor = Color(0xFF0A84FF),
                        title = "Audio Espacial",
                        subtitle = "Proyección acústica 360° en tiempo real",
                        checked = settings.spatialAudioEnabled,
                        tag = "switch_spatial_audio",
                        onCheckedChange = { viewModel.setSpatialAudioEnabled(it) }
                    )
                    SettingsHairlineDivider(58.dp)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.AutoAwesome,
                        tileColor = Color(0xFFFF2D55),
                        title = "Mezcla Automática (AutoMix)",
                        subtitle = "Calcula el punto óptimo de cruce entre pistas",
                        checked = settings.autoMixEnabled,
                        tag = "switch_automix_enabled",
                        onCheckedChange = { viewModel.setAutoMixEnabled(it) }
                    )

                    if (settings.autoMixEnabled) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Duración del cruce (AutoMix)",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = SonoraTextPrimary
                                    )
                                    Text(
                                        text = if (settings.autoMixOverlapSeconds == 0) {
                                            "Automático según ritmo y energía"
                                        } else {
                                            "Entra ${settings.autoMixOverlapSeconds}s antes del final"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = accent
                                    )
                                }
                                FilterChip(
                                    selected = settings.autoMixOverlapSeconds == 0,
                                    onClick = { viewModel.setAutoMixOverlapSeconds(0) },
                                    label = { Text("Automático") },
                                    border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accent.copy(alpha = 0.24f),
                                        selectedLabelColor = SonoraTextPrimary,
                                        containerColor = palette.elevated,
                                        labelColor = SonoraTextSecondary
                                    ),
                                    modifier = Modifier.testTag("automix_auto_chip")
                                )
                            }
                            Slider(
                                value = if (settings.autoMixOverlapSeconds == 0) 6f else settings.autoMixOverlapSeconds.toFloat(),
                                onValueChange = { viewModel.setAutoMixOverlapSeconds(it.roundToInt().coerceIn(2, 12)) },
                                valueRange = 2f..12f,
                                steps = 9,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = accent,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                                ),
                                modifier = Modifier.testTag("automix_overlap_slider")
                            )
                        }
                    }

                    SettingsHairlineDivider(16.dp)

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Fundido (Crossfade)",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = SonoraTextPrimary
                                )
                                Text(
                                    text = when {
                                        settings.crossfadeSeconds < 0 -> "Automático inteligente por canción"
                                        settings.crossfadeSeconds == 0 -> "Apagado"
                                        else -> "Entra suave ${settings.crossfadeSeconds}s antes de terminar"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (settings.crossfadeSeconds != 0) accent else SonoraTextSecondary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = settings.crossfadeSeconds == 0,
                                    onClick = { viewModel.setCrossfadeSeconds(0) },
                                    label = { Text("Off") },
                                    border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.White.copy(alpha = 0.18f),
                                        selectedLabelColor = SonoraTextPrimary,
                                        containerColor = palette.elevated,
                                        labelColor = SonoraTextSecondary
                                    ),
                                    modifier = Modifier.testTag("crossfade_off_chip")
                                )
                                FilterChip(
                                    selected = settings.crossfadeSeconds < 0,
                                    onClick = { viewModel.setCrossfadeSeconds(-1) },
                                    label = { Text("Auto") },
                                    border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accent.copy(alpha = 0.24f),
                                        selectedLabelColor = SonoraTextPrimary,
                                        containerColor = palette.elevated,
                                        labelColor = SonoraTextSecondary
                                    ),
                                    modifier = Modifier.testTag("crossfade_auto_chip")
                                )
                            }
                        }
                        Slider(
                            value = if (settings.crossfadeSeconds <= 0) 0f else settings.crossfadeSeconds.toFloat(),
                            onValueChange = { viewModel.setCrossfadeSeconds(it.roundToInt().coerceIn(0, 12)) },
                            valueRange = 0f..12f,
                            steps = 11,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier.testTag("crossfade_seconds_slider")
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Calidad en Wi-Fi y descargas")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    AudioQuality.entries.forEachIndexed { idx, quality ->
                        SettingsRadioOptionRow(
                            title = quality.label,
                            subtitle = quality.description,
                            selected = settings.wifiAudioQuality == quality,
                            accent = accent,
                            tag = "wifi_quality_option_${quality.name}",
                            onClick = { viewModel.setWifiAudioQuality(quality) }
                        )
                        if (idx < AudioQuality.entries.lastIndex) SettingsHairlineDivider()
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Calidad con datos móviles")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    AudioQuality.entries.forEachIndexed { idx, quality ->
                        SettingsRadioOptionRow(
                            title = quality.label,
                            subtitle = quality.description,
                            selected = settings.mobileAudioQuality == quality,
                            accent = accent,
                            tag = "mobile_quality_option_${quality.name}",
                            onClick = { viewModel.setMobileAudioQuality(quality) }
                        )
                        if (idx < AudioQuality.entries.lastIndex) SettingsHairlineDivider()
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Ecualizador y reproducción")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    val route = SonoraPlaybackBridge.activeAudioRoute.collectAsState().value
                    Text(
                        text = "Perfil activo: ${route.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    )
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Equalizer,
                        tileColor = Color(0xFFFF9500),
                        title = "Ecualizador de audio",
                        subtitle = "Ajusta frecuencias por salida de audio",
                        checked = settings.eqEnabled,
                        tag = "switch_eq_enabled",
                        onCheckedChange = { viewModel.setEqEnabled(it) }
                    )

                    if (settings.eqEnabled) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                EqPreset.entries.forEach { preset ->
                                    FilterChip(
                                        selected = settings.eqPreset == preset,
                                        onClick = { viewModel.setEqPreset(preset) },
                                        label = { Text(preset.label) },
                                        border = null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = accent.copy(alpha = 0.24f),
                                            selectedLabelColor = SonoraTextPrimary,
                                            containerColor = palette.elevated,
                                            labelColor = SonoraTextSecondary
                                        ),
                                        modifier = Modifier.testTag("eq_preset_${preset.name}")
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Refuerzo de graves", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                                Text("${settings.bassBoostStrength / 10}%", style = MaterialTheme.typography.labelLarge, color = accent)
                            }
                            Slider(
                                value = settings.bassBoostStrength.toFloat(),
                                onValueChange = { viewModel.setBassBoostStrength(it.toInt()) },
                                valueRange = 0f..1000f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = accent,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                                ),
                                modifier = Modifier.testTag("bass_boost_slider")
                            )
                        }
                    }

                    SettingsHairlineDivider(58.dp)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.VolumeUp,
                        tileColor = Color(0xFF34C759),
                        title = "Normalizador de volumen",
                        subtitle = "Equilibra la potencia entre canciones",
                        checked = settings.normalizeVolume,
                        tag = "switch_normalize_volume",
                        onCheckedChange = { viewModel.setNormalizeVolume(it) }
                    )
                    SettingsHairlineDivider(58.dp)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Radio,
                        tileColor = Color(0xFFAF52DE),
                        title = "Radio automática",
                        subtitle = "Agrega música relacionada al terminar la cola",
                        checked = settings.autoplayRadio,
                        tag = "switch_autoplay_radio",
                        onCheckedChange = { viewModel.setAutoplayRadio(it) }
                    )
                    SettingsHairlineDivider(58.dp)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.HistoryToggleOff,
                        tileColor = Color(0xFF32ADE6),
                        title = "Saltar silencios",
                        subtitle = "Recorta pausas largas automáticamente",
                        checked = settings.skipSilence,
                        tag = "switch_skip_silence",
                        onCheckedChange = { viewModel.setSkipSilence(it) }
                    )
                    SettingsHairlineDivider(58.dp)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.MusicNote,
                        tileColor = Color(0xFFFF375F),
                        title = "Modo solo música",
                        subtitle = "Prioriza audio de estudio sobre videoclips",
                        checked = settings.audioOnlyPreference,
                        tag = "switch_audio_only_pref",
                        onCheckedChange = { viewModel.setAudioOnlyPreference(it) }
                    )
                    SettingsHairlineDivider(16.dp)

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsIconTile(icon = Icons.Rounded.Speed, tileColor = Color(0xFF636366))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Velocidad y tono",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = SonoraTextPrimary
                                )
                            }
                            if (abs(settings.playbackSpeed - 1.0f) > 0.02f || abs(settings.playbackPitch - 1.0f) > 0.02f) {
                                TextButton(onClick = { viewModel.resetSpeedAndPitch() }) {
                                    Text("Restablecer", color = accent)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Velocidad", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                            Text("${"%.2f".format(settings.playbackSpeed)}×", style = MaterialTheme.typography.labelLarge, color = accent)
                        }
                        Slider(
                            value = settings.playbackSpeed,
                            onValueChange = { viewModel.setPlaybackSpeed((it * 20f).roundToInt() / 20f) },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier.testTag("settings_speed_slider")
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tono (Pitch)", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                            Text("${"%.2f".format(settings.playbackPitch)}×", style = MaterialTheme.typography.labelLarge, color = accent)
                        }
                        Slider(
                            value = settings.playbackPitch,
                            onValueChange = { viewModel.setPlaybackPitch((it * 20f).roundToInt() / 20f) },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier.testTag("settings_pitch_slider")
                        )
                    }

                    SettingsHairlineDivider(16.dp)

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsIconTile(icon = Icons.Rounded.Bedtime, tileColor = Color(0xFF5856D6))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Temporizador de apagado",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = SonoraTextPrimary
                                )
                            }
                            if (sleepTimerRemainingSec != null) {
                                Text(
                                    text = "%d:%02d".format(sleepTimerRemainingSec / 60, sleepTimerRemainingSec % 60),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = accent
                                )
                            } else if (sleepAfterCurrentTrack) {
                                Text(
                                    text = "Al terminar pista",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accent
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val timerOptions = listOf(null to "Desactivado", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "60 min")
                            timerOptions.forEach { (minutes, label) ->
                                val isSelected = minutes == null && sleepTimerRemainingSec == null && !sleepAfterCurrentTrack
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.setSleepTimer(minutes)
                                        if (minutes == null) viewModel.setSleepAfterCurrentTrack(false)
                                        onStatusBanner(
                                            if (minutes == null) "Temporizador desactivado"
                                            else "La música se pausará en $minutes minutos"
                                        )
                                    },
                                    label = { Text(label) },
                                    border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accent.copy(alpha = 0.24f),
                                        selectedLabelColor = SonoraTextPrimary,
                                        containerColor = palette.elevated,
                                        labelColor = SonoraTextSecondary
                                    ),
                                    modifier = Modifier.testTag("sleep_chip_${minutes ?: 0}")
                                )
                            }
                            FilterChip(
                                selected = sleepAfterCurrentTrack,
                                onClick = {
                                    val next = !sleepAfterCurrentTrack
                                    viewModel.setSleepAfterCurrentTrack(next)
                                    onStatusBanner(
                                        if (next) "Se pausará al terminar la canción actual"
                                        else "Temporizador al finalizar canción desactivado"
                                    )
                                },
                                label = { Text("Al terminar canción") },
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accent.copy(alpha = 0.24f),
                                    selectedLabelColor = SonoraTextPrimary,
                                    containerColor = palette.elevated,
                                    labelColor = SonoraTextSecondary
                                ),
                                modifier = Modifier.testTag("sleep_chip_after_track")
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
internal fun SettingsDjSonyPage(
    viewModel: SonoraViewModel,
    settings: SonoraSettings,
    djLiveState: DjLiveState,
    availableDeviceSpanishVoices: List<DeviceTtsVoiceInfo>,
    accent: Color,
    bottomContentPadding: Dp
) {
    val context = LocalContext.current
    val palette = LocalSonoraPalette.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_dj_page"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            SettingsSectionTitle("Voz principal de DJ Sony")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            SettingsIconTile(icon = Icons.Rounded.RecordVoiceOver, tileColor = Color(0xFFFF9500))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Locutor de tus mezclas",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SonoraTextPrimary
                                )
                                val engineSummary = if (djLiveState.voiceEngine == DjVoiceEngine.GEMINI) {
                                    "Motor activo: Gemini"
                                } else {
                                    "Motor activo: Teléfono"
                                }
                                Text(
                                    text = engineSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.previewDjVoice(settings.djVoiceName) },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.elevated, contentColor = SonoraTextPrimary),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("settings_dj_preview_voice_button")
                        ) {
                            Text("Probar voz", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (!djLiveState.lastPreviewFeedback.isNullOrBlank()) {
                        Text(
                            text = djLiveState.lastPreviewFeedback.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("settings_dj_preview_feedback")
                        )
                    }

                    SettingsHairlineDivider()
                    DjVoiceCatalog.voices.forEachIndexed { idx, voice ->
                        val selected = settings.djVoiceName.equals(voice.id, ignoreCase = true)
                        SettingsRadioOptionRow(
                            title = voice.displayName,
                            subtitle = voice.toneDescription,
                            selected = selected,
                            accent = accent,
                            tag = "dj_voice_option_${voice.id}",
                            onClick = { viewModel.setDjVoiceName(voice.id) }
                        )
                        if (idx < DjVoiceCatalog.voices.lastIndex) SettingsHairlineDivider()
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Respaldo de voz en el teléfono")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Voz local de respaldo",
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "Se usa si no hay red o Gemini TTS no responde",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.previewDeviceDjVoice() },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.elevated, contentColor = SonoraTextPrimary),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("settings_dj_preview_device_voice_button")
                        ) {
                            Text("Probar local", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "device_voice_auto") {
                            FilterChip(
                                selected = settings.djDeviceVoiceName.isBlank(),
                                onClick = { viewModel.setDjDeviceVoiceName("") },
                                label = { Text("Automática") },
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accent.copy(alpha = 0.24f),
                                    selectedLabelColor = SonoraTextPrimary,
                                    containerColor = palette.elevated,
                                    labelColor = SonoraTextSecondary
                                )
                            )
                        }
                        items(availableDeviceSpanishVoices, key = { it.name }) { voice ->
                            FilterChip(
                                selected = settings.djDeviceVoiceName == voice.name,
                                onClick = { viewModel.setDjDeviceVoiceName(voice.name) },
                                label = { Text(voice.displayLabel) },
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accent.copy(alpha = 0.24f),
                                    selectedLabelColor = SonoraTextPrimary,
                                    containerColor = palette.elevated,
                                    labelColor = SonoraTextSecondary
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Velocidad voz local", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                        Text("${"%.2f".format(settings.djDeviceSpeechRate)}×", style = MaterialTheme.typography.labelLarge, color = accent)
                    }
                    Slider(
                        value = settings.djDeviceSpeechRate,
                        onValueChange = { viewModel.setDjDeviceSpeechRate(((it * 20f).roundToInt() / 20f).coerceIn(0.7f, 1.4f)) },
                        valueRange = 0.7f..1.4f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Tono voz local", style = MaterialTheme.typography.bodyMedium, color = SonoraTextPrimary)
                        Text("${"%.2f".format(settings.djDevicePitch)}×", style = MaterialTheme.typography.labelLarge, color = accent)
                    }
                    Slider(
                        value = settings.djDevicePitch,
                        onValueChange = { viewModel.setDjDevicePitch(((it * 20f).roundToInt() / 20f).coerceIn(0.7f, 1.4f)) },
                        valueRange = 0.7f..1.4f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { openTtsVoiceInstaller(context) },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_dj_download_voices_button")
                    ) {
                        Text(
                            text = "Descargar voces de mejor calidad",
                            style = MaterialTheme.typography.labelLarge,
                            color = SonoraTextPrimary
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsAppearancePage(
    viewModel: SonoraViewModel,
    settings: SonoraSettings,
    accent: Color,
    bottomContentPadding: Dp,
    onStatusBanner: (String) -> Unit
) {
    val context = LocalContext.current
    var showSpotifySetupSheet by remember { mutableStateOf(false) }
    var hasSpotifyCookie by remember {
        mutableStateOf(SpotifyCanvasCredentialsStore.hasSpDc(context))
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_appearance_page"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            SettingsSectionTitle("Color de acento y paleta")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Tono principal",
                        style = MaterialTheme.typography.titleMedium,
                        color = SonoraTextPrimary
                    )
                    Text(
                        text = "Tiñe sutilmente todo el fondo negro e ilumina los controles activos",
                        style = MaterialTheme.typography.bodySmall,
                        color = SonoraTextSecondary
                    )
                    Spacer(Modifier.height(14.dp))
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
                                            if (selected) Modifier.border(2.5.dp, Color.White, CircleShape)
                                            else Modifier.border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
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
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Ícono de la aplicación")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Elige el estilo del ícono de Sonora",
                        style = MaterialTheme.typography.bodySmall,
                        color = SonoraTextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(AppIconVariant.entries, key = { it.name }) { variant ->
                            val selected = settings.appIconVariant == variant
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(74.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        viewModel.setAppIconVariant(variant)
                                        applyAppIconVariantSafely(context, variant)
                                        onStatusBanner("Ícono cambiado a ${variant.label}")
                                    }
                                    .padding(4.dp)
                                    .testTag("app_icon_option_${variant.name}")
                            ) {
                                SwaveAppIconPreview(
                                    variant = variant,
                                    modifier = Modifier
                                        .size(62.dp)
                                        .then(
                                            if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(16.dp))
                                            else Modifier
                                        )
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = variant.label.substringBefore(" "),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (selected) SonoraTextPrimary else SonoraTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Reproductor y cristal líquido")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.MotionPhotosAuto,
                        tileColor = Color(0xFFFF2D55),
                        title = "Portadas animadas en reproductor",
                        subtitle = "Muestra portadas animadas oficiales de Apple Music",
                        checked = settings.animatedArtworkEnabled,
                        tag = "switch_animated_artwork",
                        onCheckedChange = { viewModel.setAnimatedArtworkEnabled(it) }
                    )
                    if (settings.animatedArtworkEnabled) {
                        SettingsHairlineDivider(58.dp)
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Wifi,
                            tileColor = Color(0xFF0A84FF),
                            title = "Canvas con datos móviles",
                            subtitle = "Permite portadas animadas fuera de Wi-Fi",
                            checked = settings.animatedArtworkMobileData,
                            tag = "switch_animated_artwork_mobile_data",
                            onCheckedChange = { viewModel.setAnimatedArtworkMobileData(it) }
                        )
                        SettingsHairlineDivider(58.dp)
                        SettingsSwitchRow(
                            icon = Icons.Rounded.VisibilityOff,
                            tileColor = Color(0xFF32ADE6),
                            title = "Ocultar controles automáticamente",
                            subtitle = "Oculta los controles tras 5 s cuando hay portada animada reproduciéndose",
                            checked = settings.autoHidePlayerControls,
                            tag = "switch_auto_hide_controls",
                            onCheckedChange = { viewModel.setAutoHidePlayerControls(it) }
                        )
                        SettingsHairlineDivider(58.dp)
                        SettingsSwitchRow(
                            icon = Icons.Rounded.AutoAwesome,
                            tileColor = Color(0xFF1DB954),
                            title = "Spotify Canvas (experimental)",
                            subtitle = if (hasSpotifyCookie) {
                                "Respaldo opcional con tu cookie sp_dc configurada"
                            } else {
                                "Usa tu sesión de Spotify de forma no oficial; bajo tu propio riesgo"
                            },
                            checked = settings.spotifyCanvasEnabled,
                            tag = "switch_spotify_canvas",
                            onCheckedChange = { enabled ->
                                viewModel.setSpotifyCanvasEnabled(enabled)
                                if (enabled && !hasSpotifyCookie) {
                                    showSpotifySetupSheet = true
                                }
                            }
                        )
                        SettingsHairlineDivider(58.dp)
                        SettingsActionRow(
                            icon = Icons.Rounded.Key,
                            tileColor = Color(0xFF1DB954),
                            title = "Configurar cookie de Spotify (sp_dc)",
                            subtitle = "Almacenada cifrada localmente con EncryptedSharedPreferences",
                            valueText = if (hasSpotifyCookie) "Guardada" else "Sin configurar",
                            tag = "action_configure_spotify_canvas",
                            onClick = { showSpotifySetupSheet = true }
                        )
                    }
                    SettingsHairlineDivider(58.dp)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.AutoAwesome,
                        tileColor = Color(0xFFAF52DE),
                        title = "Cristal líquido",
                        subtitle = "Refracción en barras, mini reproductor y hojas (Android 12+)",
                        checked = settings.liquidGlassEnabled,
                        tag = "switch_liquid_glass",
                        onCheckedChange = { viewModel.setLiquidGlassEnabled(it) }
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Fondo del reproductor")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    PlayerBackdropStyle.entries.forEachIndexed { idx, style ->
                        SettingsRadioOptionRow(
                            title = style.label,
                            subtitle = style.description,
                            selected = settings.playerBackdropStyle == style,
                            accent = accent,
                            tag = "backdrop_style_${style.name}",
                            onClick = { viewModel.setPlayerBackdropStyle(style) }
                        )
                        if (idx < PlayerBackdropStyle.entries.lastIndex) SettingsHairlineDivider()
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Estilo de superficie")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    GlassStyle.entries.forEachIndexed { idx, style ->
                        SettingsRadioOptionRow(
                            title = style.label,
                            subtitle = style.description,
                            selected = settings.glassStyle == style,
                            accent = accent,
                            tag = "glass_style_${style.name}",
                            onClick = { viewModel.setGlassStyle(style) }
                        )
                        if (idx < GlassStyle.entries.lastIndex) SettingsHairlineDivider()
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    if (showSpotifySetupSheet) {
        val palette = LocalSonoraPalette.current
        var cookieInput by remember { mutableStateOf("") }
        SonoraLiquidModalSheet(onDismissRequest = { showSpotifySetupSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Spotify Canvas (experimental)",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonoraTextPrimary
                )
                Text(
                    text = "Aviso: esta opción usa tu sesión de Spotify de forma no oficial; bajo tu propio riesgo. Tu cookie sp_dc se cifra en este dispositivo y nunca se registra ni se incluye en copias de seguridad.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary
                )
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { Text("Cookie sp_dc") },
                    placeholder = { Text(if (hasSpotifyCookie) "Ya hay una cookie cifrada guardada" else "Pega aquí el valor de sp_dc") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                        focusedTextColor = SonoraTextPrimary,
                        unfocusedTextColor = SonoraTextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("spotify_spdc_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (hasSpotifyCookie) {
                        OutlinedButton(
                            onClick = {
                                SpotifyCanvasCredentialsStore.clearSpDc(context)
                                hasSpotifyCookie = false
                                cookieInput = ""
                                onStatusBanner("Cookie sp_dc eliminada")
                                showSpotifySetupSheet = false
                            },
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Borrar", color = SonoraTextPrimary)
                        }
                    }
                    Button(
                        onClick = {
                            val trimmed = cookieInput.trim()
                            if (trimmed.isNotBlank()) {
                                SpotifyCanvasCredentialsStore.saveSpDc(context, trimmed)
                                hasSpotifyCookie = true
                                viewModel.setSpotifyCanvasEnabled(true)
                                CanvasRepository.clearCache()
                                onStatusBanner("Cookie sp_dc cifrada y guardada")
                                showSpotifySetupSheet = false
                            } else {
                                onStatusBanner("Ingresa un valor válido para sp_dc")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("spotify_spdc_save_button")
                    ) {
                        Text("Guardar", color = Color.White)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
internal fun SettingsLyricsPage(
    viewModel: SonoraViewModel,
    settings: SonoraSettings,
    accent: Color,
    bottomContentPadding: Dp,
    onStatusBanner: (String) -> Unit
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val currentTrack = playbackState.currentTrack
    val offsetMs = settings.lyricsOffsetFor(currentTrack?.videoId)
    val palette = LocalSonoraPalette.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_lyrics_page"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            SettingsSectionTitle("Sincronización de letras")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (currentTrack != null) {
                            "Desfase para «${currentTrack.title}»"
                        } else {
                            "Desfase general de letras"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = SonoraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Ajusta en pasos de 100 ms entre −2.0 s y +2.0 s si la letra va adelantada o atrasada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SonoraTextSecondary
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.adjustLyricsOffsetMs(-100L) },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier.testTag("lyrics_offset_minus_button")
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = null, tint = SonoraTextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("100 ms", color = SonoraTextPrimary)
                        }
                        Text(
                            text = if (offsetMs > 0) "+${offsetMs} ms" else "${offsetMs} ms",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (offsetMs == 0L) SonoraTextPrimary else accent,
                            modifier = Modifier.testTag("lyrics_offset_value_text")
                        )
                        OutlinedButton(
                            onClick = { viewModel.adjustLyricsOffsetMs(100L) },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier.testTag("lyrics_offset_plus_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = SonoraTextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("100 ms", color = SonoraTextPrimary)
                        }
                    }
                    if (offsetMs != 0L) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                viewModel.resetLyricsOffsetMs()
                                onStatusBanner("Desfase de letras restablecido a 0 ms")
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Restablecer a 0 ms", color = accent)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Proveedores y recarga")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    SettingsActionRow(
                        icon = Icons.Rounded.Refresh,
                        tileColor = Color(0xFF32ADE6),
                        title = "Recargar letras de la canción actual",
                        subtitle = if (currentTrack != null) {
                            "Consulta LRCLIB, KuGou, BetterLyrics y PaxSenix nuevamente"
                        } else {
                            "Reproduce una canción para actualizar sus letras"
                        },
                        tag = "settings_reload_lyrics_button",
                        onClick = {
                            viewModel.reloadCurrentLyrics()
                            onStatusBanner(
                                if (currentTrack != null) "Recargando letras para ${currentTrack.title}..."
                                else "No hay canción reproduciéndose"
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
internal fun SettingsStorageBackupPage(
    viewModel: SonoraViewModel,
    settings: SonoraSettings,
    downloadedTracks: List<DownloadedTrackEntity>,
    bottomContentPadding: Dp,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onStatusBanner: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_storage_page"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            SettingsSectionTitle("Descargas sin conexión")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Wifi,
                        tileColor = Color(0xFF0A84FF),
                        title = "Descargar solo por Wi-Fi",
                        subtitle = "Evita consumo de datos móviles offline",
                        checked = settings.wifiOnlyDownloads,
                        tag = "switch_wifi_only_downloads",
                        onCheckedChange = { viewModel.setWifiOnlyDownloads(it) }
                    )
                    if (downloadedTracks.isNotEmpty()) {
                        SettingsHairlineDivider(58.dp)
                        val offlineMb = downloadedTracks.sumOf { it.fileSizeBytes } / (1024f * 1024f)
                        SettingsActionRow(
                            icon = Icons.Rounded.DeleteSweep,
                            tileColor = Color(0xFFFF3B30),
                            title = "Eliminar todas las descargas (${downloadedTracks.size})",
                            subtitle = "Libera %.1f MB de almacenamiento offline".format(offlineMb),
                            tag = "clear_offline_downloads_button",
                            onClick = {
                                viewModel.clearAllDownloads()
                                onStatusBanner("Descargas eliminadas")
                            }
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Copia de seguridad")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    SettingsActionRow(
                        icon = Icons.Rounded.Backup,
                        tileColor = Color(0xFF34C759),
                        title = "Exportar copia de seguridad (JSON)",
                        subtitle = "Guarda tus me gusta y playlists locales",
                        tag = "export_backup_button",
                        onClick = onExportBackup
                    )
                    SettingsHairlineDivider(58.dp)
                    SettingsActionRow(
                        icon = Icons.Rounded.Restore,
                        tileColor = Color(0xFF5856D6),
                        title = "Restaurar copia de seguridad",
                        subtitle = "Importa playlists y canciones desde JSON",
                        tag = "import_backup_button",
                        onClick = onImportBackup
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Caché e historial")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column {
                    SettingsActionRow(
                        icon = Icons.Rounded.Cached,
                        tileColor = Color(0xFFFF9500),
                        title = "Limpiar caché de audio",
                        subtitle = "Fuerza la recarga de enlaces de reproducción",
                        tag = "clear_stream_cache_button",
                        onClick = {
                            viewModel.clearStreamCache()
                            onStatusBanner("Caché de enlaces de audio limpiada")
                        }
                    )
                    SettingsHairlineDivider(58.dp)
                    val artCacheMb = remember(downloadedTracks.size) { viewModel.computeLocalArtCacheMb() }
                    SettingsActionRow(
                        icon = Icons.Rounded.Image,
                        tileColor = Color(0xFF32ADE6),
                        title = "Limpiar caché de carátulas locales",
                        subtitle = "Espacio usado: %.2f MB".format(artCacheMb),
                        tag = "clear_local_art_cache_button",
                        onClick = {
                            viewModel.clearLocalArtCache()
                            onStatusBanner("Caché de carátulas locales limpiada")
                        }
                    )
                    SettingsHairlineDivider(58.dp)
                    SettingsActionRow(
                        icon = Icons.Rounded.DeleteSweep,
                        tileColor = Color(0xFFFF375F),
                        title = "Borrar historial reciente",
                        subtitle = "Limpia las canciones escuchadas recientemente",
                        tag = "clear_play_history_button",
                        onClick = {
                            viewModel.clearPlaybackHistory()
                            onStatusBanner("Historial de reproducción eliminado")
                        }
                    )
                    SettingsHairlineDivider(58.dp)
                    SettingsActionRow(
                        icon = Icons.Rounded.DeleteSweep,
                        tileColor = Color(0xFF636366),
                        title = "Borrar historial de búsqueda",
                        subtitle = "Elimina tus búsquedas guardadas",
                        tag = "clear_search_history_button",
                        onClick = {
                            viewModel.clearSearchHistory()
                            onStatusBanner("Historial de búsqueda eliminado")
                        }
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
internal fun SettingsIntegrationsPage(
    viewModel: SonoraViewModel,
    settings: SonoraSettings,
    accent: Color,
    bottomContentPadding: Dp,
    onStatusBanner: (String) -> Unit
) {
    val palette = LocalSonoraPalette.current
    var lbToken by remember(settings.listenBrainzToken) { mutableStateOf(settings.listenBrainzToken) }
    var lbBusy by remember { mutableStateOf(false) }

    var lfmUser by remember(settings.lastFmUsername) { mutableStateOf(settings.lastFmUsername) }
    var lfmPass by remember { mutableStateOf("") }
    var lfmApiKey by remember(settings.lastFmApiKey) { mutableStateOf(settings.lastFmApiKey) }
    var lfmApiSecret by remember(settings.lastFmApiSecret) { mutableStateOf(settings.lastFmApiSecret) }
    var lfmBusy by remember { mutableStateOf(false) }

    var discordToken by remember(settings.discordToken) { mutableStateOf(settings.discordToken) }
    var discordBusy by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accent,
        focusedLabelColor = accent,
        cursorColor = accent,
        focusedTextColor = SonoraTextPrimary,
        unfocusedTextColor = SonoraTextPrimary,
        unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
        focusedContainerColor = palette.elevated,
        unfocusedContainerColor = palette.elevated
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_integrations_page"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            SettingsSectionTitle("ListenBrainz Scrobbler")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (settings.listenBrainzUsername.isNotBlank()) {
                                    "Conectado como ${settings.listenBrainzUsername}"
                                } else {
                                    "ListenBrainz"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "Registra automáticamente tus reproducciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary
                            )
                        }
                        if (settings.listenBrainzToken.isNotBlank()) {
                            SonoraIosSwitch(
                                checked = settings.listenBrainzEnabled,
                                onCheckedChange = { viewModel.setListenBrainzEnabled(it) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = lbToken,
                        onValueChange = { lbToken = it },
                        label = { Text("Token de usuario (User Token)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("lb_token_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                lbBusy = true
                                viewModel.connectListenBrainz(lbToken) { res ->
                                    lbBusy = false
                                    res.onSuccess { user ->
                                        onStatusBanner("ListenBrainz conectado ($user)")
                                    }.onFailure { err ->
                                        onStatusBanner(err.localizedMessage ?: "No se pudo validar el token")
                                    }
                                }
                            },
                            enabled = !lbBusy && lbToken.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag("lb_connect_button")
                        ) {
                            if (lbBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text("Validar y conectar")
                            }
                        }
                        if (settings.listenBrainzToken.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.disconnectListenBrainz()
                                    lbToken = ""
                                    onStatusBanner("ListenBrainz desconectado")
                                },
                                border = BorderStroke(0.5.dp, accent.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Quitar", color = accent)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SettingsSectionTitle("Last.fm AudioScrobbler")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (settings.lastFmSessionKey.isNotBlank()) {
                                    "Sesión activa: ${settings.lastFmUsername}"
                                } else {
                                    "Last.fm Scrobbler"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "Envía «Escuchando ahora» y scrobbles oficiales",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary
                            )
                        }
                        if (settings.lastFmSessionKey.isNotBlank()) {
                            SonoraIosSwitch(
                                checked = settings.lastFmEnabled,
                                onCheckedChange = { viewModel.setLastFmEnabled(it) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = lfmUser,
                        onValueChange = { lfmUser = it },
                        label = { Text("Usuario de Last.fm") },
                        singleLine = true,
                        colors = fieldColors,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("lfm_user_input")
                    )
                    OutlinedTextField(
                        value = lfmPass,
                        onValueChange = { lfmPass = it },
                        label = { Text("Contraseña") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("lfm_pass_input")
                    )
                    OutlinedTextField(
                        value = lfmApiKey,
                        onValueChange = { lfmApiKey = it },
                        label = { Text("API Key de Last.fm") },
                        singleLine = true,
                        colors = fieldColors,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("lfm_apikey_input")
                    )
                    OutlinedTextField(
                        value = lfmApiSecret,
                        onValueChange = { lfmApiSecret = it },
                        label = { Text("API Secret de Last.fm") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("lfm_secret_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                lfmBusy = true
                                viewModel.connectLastFm(lfmUser, lfmPass, lfmApiKey, lfmApiSecret) { res ->
                                    lfmBusy = false
                                    res.onSuccess { resolved ->
                                        lfmPass = ""
                                        onStatusBanner("Last.fm conectado como $resolved")
                                    }.onFailure { err ->
                                        onStatusBanner(err.localizedMessage ?: "Error al autenticar en Last.fm")
                                    }
                                }
                            },
                            enabled = !lfmBusy && lfmUser.isNotBlank() && lfmPass.isNotBlank() && lfmApiKey.isNotBlank() && lfmApiSecret.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag("lfm_connect_button")
                        ) {
                            if (lfmBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text("Conectar Last.fm")
                            }
                        }
                        if (settings.lastFmSessionKey.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.disconnectLastFm()
                                    onStatusBanner("Last.fm desconectado")
                                },
                                border = BorderStroke(0.5.dp, accent.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Quitar", color = accent)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SettingsSectionTitle("Discord Rich Presence")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (settings.discordUsername.isNotBlank()) {
                                    "Conectado como ${settings.discordUsername}"
                                } else {
                                    "Presencia «Escuchando Sonora»"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "Muestra en vivo tu canción en Discord Gateway",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary
                            )
                        }
                        if (settings.discordToken.isNotBlank()) {
                            SonoraIosSwitch(
                                checked = settings.discordRpcEnabled,
                                onCheckedChange = { viewModel.setDiscordRpcEnabled(it) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = discordToken,
                        onValueChange = { discordToken = it },
                        label = { Text("Token de sesión de Discord") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("discord_token_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                discordBusy = true
                                viewModel.connectDiscord(discordToken) { res ->
                                    discordBusy = false
                                    res.onSuccess { name ->
                                        onStatusBanner("Discord RPC listo ($name)")
                                    }.onFailure { err ->
                                        onStatusBanner(err.localizedMessage ?: "Discord rechazó el token")
                                    }
                                }
                            },
                            enabled = !discordBusy && discordToken.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag("discord_connect_button")
                        ) {
                            if (discordBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text("Conectar Discord")
                            }
                        }
                        if (settings.discordToken.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.disconnectDiscord()
                                    discordToken = ""
                                    onStatusBanner("Discord RPC desconectado")
                                },
                                border = BorderStroke(0.5.dp, accent.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Quitar", color = accent)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsAboutPage(
    viewModel: SonoraViewModel,
    updateCheckState: UpdateCheckUiState,
    accent: Color,
    bottomContentPadding: Dp,
    onStatusBanner: (String) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val palette = LocalSonoraPalette.current
    var showLicensesSheet by remember { mutableStateOf(false) }

    val aboutTitle = stringResource(R.string.about_section_title)
    val appVersion = "Sonora Music • v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})"
    val creatorText = stringResource(R.string.about_creator)
    val githubButtonLabel = stringResource(R.string.about_github_button)
    val githubUrl = stringResource(R.string.about_github_url)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_about_page"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            SettingsSectionTitle(aboutTitle)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("settings_about_card"),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsIconTile(icon = Icons.Rounded.Info, tileColor = accent)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val signingShortSha1 = remember(context) {
                                runCatching {
                                    val pkgName = context.packageName
                                    val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        val info = context.packageManager.getPackageInfo(
                                            pkgName,
                                            PackageManager.GET_SIGNING_CERTIFICATES
                                        )
                                        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                                    } else {
                                        @Suppress("DEPRECATION")
                                        val info = context.packageManager.getPackageInfo(
                                            pkgName,
                                            PackageManager.GET_SIGNATURES
                                        )
                                        @Suppress("DEPRECATION")
                                        info.signatures?.firstOrNull()?.toByteArray()
                                    }
                                    sigBytes?.let { bytes ->
                                        MessageDigest.getInstance("SHA-1")
                                            .digest(bytes)
                                            .take(6)
                                            .joinToString(":") { "%02X".format(it) }
                                    }
                                }.getOrNull()
                            }
                            Text(
                                text = appVersion + (signingShortSha1?.let { " • Firma: $it" } ?: ""),
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Las actualizaciones con la misma firma se instalan encima sin desinstalar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SettingsHairlineDivider(0.dp)
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsIconTile(icon = Icons.Rounded.Person, tileColor = Color(0xFF5856D6))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = creatorText,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "IbethOrtega/Sonora",
                                style = MaterialTheme.typography.bodySmall,
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
                                .onFailure { onStatusBanner("No se pudo abrir el enlace del repositorio") }
                        },
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("about_github_button")
                    ) {
                        Icon(Icons.Rounded.Code, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(githubButtonLabel, color = SonoraTextPrimary, style = MaterialTheme.typography.labelLarge)
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

            Spacer(Modifier.height(18.dp))
            SettingsSectionTitle("Legal y atribuciones")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                SettingsActionRow(
                    icon = Icons.Rounded.Description,
                    tileColor = Color(0xFF636366),
                    title = "Licencias de código abierto",
                    subtitle = "Kyant0/backdrop (Apache 2.0), Media3, Jetpack Compose y más",
                    tag = "about_licenses_button",
                    onClick = { showLicensesSheet = true }
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showLicensesSheet) {
        SonoraLiquidModalSheet(onDismissRequest = { showLicensesSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Licencias de código abierto",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonoraTextPrimary
                )
                val licenses = listOf(
                    "Kyant0/backdrop (AndroidLiquidGlass)" to "Licencia Apache 2.0 — Efectos de cristal líquido y refracción para Jetpack Compose.",
                    "AndroidX Media3 (ExoPlayer)" to "Licencia Apache 2.0 — Motor de reproducción de audio, caché y sesión multimedia.",
                    "Jetpack Compose & Palette" to "Licencia Apache 2.0 — Interfaz declarativa y extracción cromática de carátulas.",
                    "Coil" to "Licencia Apache 2.0 — Carga y caché asíncrona de imágenes.",
                    "NewPipeExtractor" to "Licencia GPLv3 — Extracción de metadatos y flujos multimedia."
                )
                licenses.forEach { (lib, desc) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.surface)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = lib,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = SonoraTextPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = SonoraTextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
internal fun ColorSpectrumSlider(
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
                .border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
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
                style = MaterialTheme.typography.bodySmall,
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
internal fun SettingsDiagnosticsPage(
    settings: SonoraSettings,
    accent: Color,
    bottomContentPadding: Dp
) {
    val context = LocalContext.current
    val palette = LocalSonoraPalette.current
    val scope = rememberCoroutineScope()

    var titleInput by remember { mutableStateOf("Style (Taylor's Version)") }
    var artistInput by remember { mutableStateOf("Taylor Swift") }
    var albumInput by remember { mutableStateOf("1989 (Taylor's Version)") }
    var isRunning by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CanvasDiagnosticReport?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_diagnostics_page"),
        contentPadding = PaddingValues(bottom = bottomContentPadding)
    ) {
        item {
            SettingsSectionTitle("Prueba de portada animada")
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                borderWidth = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SettingsIconTile(icon = Icons.Rounded.BugReport, tileColor = Color(0xFF30D158))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Probar portada animada",
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "Consulta Apple Music web, puntuación de candidatos, albumId y claves editorialVideo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary
                            )
                        }
                    }

                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Título") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                            focusedTextColor = SonoraTextPrimary,
                            unfocusedTextColor = SonoraTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diag_title_input")
                    )

                    OutlinedTextField(
                        value = artistInput,
                        onValueChange = { artistInput = it },
                        label = { Text("Artista") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                            focusedTextColor = SonoraTextPrimary,
                            unfocusedTextColor = SonoraTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diag_artist_input")
                    )

                    OutlinedTextField(
                        value = albumInput,
                        onValueChange = { albumInput = it },
                        label = { Text("Álbum (opcional)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                            focusedTextColor = SonoraTextPrimary,
                            unfocusedTextColor = SonoraTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diag_album_input")
                    )

                    Button(
                        onClick = {
                            if (isRunning) return@Button
                            isRunning = true
                            scope.launch {
                                report = runCatching {
                                    CanvasRepository.runDiagnostic(
                                        title = titleInput.trim(),
                                        artist = artistInput.trim(),
                                        album = albumInput.trim().ifBlank { null },
                                        context = context,
                                        spotifyCanvasEnabled = settings.spotifyCanvasEnabled
                                    )
                                }.getOrNull()
                                isRunning = false
                            }
                        },
                        enabled = !isRunning && titleInput.isNotBlank() && artistInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("diag_run_canvas_test_button")
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Consultando...")
                        } else {
                            Text("Probar portada animada")
                        }
                    }
                }
            }
        }

        val currentReport = report
        if (currentReport != null) {
            item {
                Spacer(Modifier.height(16.dp))
                SettingsSectionTitle("Resultado de la prueba")
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("diag_report_card"),
                    shape = RoundedCornerShape(20.dp),
                    borderWidth = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val expLabel = currentReport.tokenExpiresAtEpochSec?.let { epoch ->
                            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            "${df.format(Date(epoch * 1000L))} ($epoch)"
                        } ?: "N/D"
                        val statusTitle = currentReport.resolvedArtwork?.menuStatusLabel(preferPortrait = true)
                            ?: "Sin portada animada"

                        Text(
                            text = statusTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (currentReport.resolvedArtwork != null) SonoraSuccess else accent
                        )
                        if (!currentReport.failureReason.isNullOrBlank()) {
                            Text(
                                text = "Motivo: ${currentReport.failureReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary
                            )
                        }

                        HorizontalDivider(color = SettingsDividerColor)

                        DiagnosticKvRow("Token Apple", currentReport.tokenStatus)
                        DiagnosticKvRow("Expira", expLabel)
                        DiagnosticKvRow("Storefront", currentReport.storefront)
                        DiagnosticKvRow("Término", currentReport.searchTerm)
                        DiagnosticKvRow("Album ID", currentReport.selectedAlbumId ?: "N/D")
                        DiagnosticKvRow(
                            "editorialVideo",
                            currentReport.editorialKeysFound.joinToString(", ").ifBlank { "Ninguna" }
                        )

                        if (currentReport.sanitizedUrls.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "URLs detectadas",
                                style = MaterialTheme.typography.labelMedium,
                                color = SonoraTextPrimary
                            )
                            currentReport.sanitizedUrls.forEach { url ->
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = SonoraTextSecondary
                                )
                            }
                        }

                        if (currentReport.candidates.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Candidatos (${currentReport.candidates.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = SonoraTextPrimary
                            )
                            currentReport.candidates.forEachIndexed { idx, cand ->
                                val scoreStr = if (cand.score == Int.MIN_VALUE) "DESCARTADO" else "pts ${cand.score}"
                                val reasonStr = cand.rejectedReason?.let { " · $it" }.orEmpty()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.surface)
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${cand.title} — ${cand.artist}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = SonoraTextPrimary
                                    )
                                    Text(
                                        text = "${cand.album ?: "Sin álbum"} · albumId=${cand.appleAlbumId ?: "-"} · $scoreStr$reasonStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SonoraTextSecondary
                                    )
                                }
                            }
                        }

                        if (currentReport.steps.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Registro (Logcat tag: Canvas)",
                                style = MaterialTheme.typography.labelMedium,
                                color = SonoraTextPrimary
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.35f))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                currentReport.steps.forEach { step ->
                                    Text(
                                        text = "• $step",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
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
}

@Composable
private fun DiagnosticKvRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SonoraTextSecondary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = SonoraTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
