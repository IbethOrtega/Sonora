package com.example.ui.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AudioOutputRoute
import com.example.model.SonoraSettings
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraSurface
import com.example.ui.theme.SonoraSurfaceElevated
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary

private data class DetectedOutputRoute(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val isExternal: Boolean
)

/**
 * Dedicated Audio Output route inspector & spatial toggles sheet backed by real
 * Android [AudioManager.GET_DEVICES_OUTPUTS] telemetry, with direct jump to EQ & Sound FX.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputSheet(
    activeAudioRoute: AudioOutputRoute,
    settings: SonoraSettings,
    onDismiss: () -> Unit,
    onSetDolbyAtmos: (Boolean) -> Unit,
    onSetSpatialAudio: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val context = LocalContext.current
    val accent = LocalSonoraAccent.current
    val haptics = rememberPlayerHaptics()
    val routes = remember(activeAudioRoute, context) { detectOutputRoutes(context) }

    com.example.ui.components.SonoraLiquidModalSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Salida de audio",
                        style = MaterialTheme.typography.headlineMedium,
                        color = SonoraTextPrimary
                    )
                    Text(
                        text = activeAudioRoute.label.ifBlank { "Dispositivo activo" },
                        style = MaterialTheme.typography.bodySmall,
                        color = accent
                    )
                }
                OutlinedButton(
                    onClick = {
                        haptics.tick()
                        runCatching {
                            val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Intent(Settings.Panel.ACTION_VOLUME)
                            } else {
                                Intent(Settings.ACTION_SOUND_SETTINGS)
                            }
                            context.startActivity(panelIntent)
                        }
                    },
                    modifier = Modifier.testTag("audio_output_system_panel_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sistema")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                routes.forEachIndexed { idx, route ->
                    val highlighted = (idx == 0 && routes.any { it.isExternal } && route.isExternal) ||
                        (!routes.any { it.isExternal } && idx == 0)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (highlighted) accent.copy(alpha = 0.14f) else SonoraSurface,
                        border = BorderStroke(
                            0.7.dp,
                            if (highlighted) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = route.icon,
                                contentDescription = route.label,
                                tint = if (highlighted) accent else SonoraTextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = route.label,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = SonoraTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = route.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary
                                )
                            }
                            if (highlighted) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Ruta activa",
                                    tint = accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SonoraSurface,
                border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            Icon(Icons.Rounded.SurroundSound, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Dolby Atmos", style = MaterialTheme.typography.titleSmall, color = SonoraTextPrimary)
                                Text("Amplitud dinámica y subgraves", style = MaterialTheme.typography.bodySmall, color = SonoraTextSecondary)
                            }
                        }
                        Switch(
                            checked = settings.dolbyAtmosEnabled,
                            onCheckedChange = {
                                haptics.tick()
                                onSetDolbyAtmos(it)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Rounded.Headphones, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Audio Espacial 360°", style = MaterialTheme.typography.titleSmall, color = SonoraTextPrimary)
                                Text("Escenario amplio para audífonos", style = MaterialTheme.typography.bodySmall, color = SonoraTextSecondary)
                            }
                        }
                        Switch(
                            checked = settings.spatialAudioEnabled,
                            onCheckedChange = {
                                haptics.tick()
                                onSetSpatialAudio(it)
                            }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    haptics.tick()
                    onDismiss()
                    onOpenEqualizer()
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("audio_output_open_eq_button")
            ) {
                Icon(Icons.Rounded.Equalizer, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ecualizador, Velocidad y Crossfade")
            }
        }
    }
}

private fun detectOutputRoutes(context: Context): List<DetectedOutputRoute> {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return listOf(
        DetectedOutputRoute("Altavoz del teléfono", "Salida integrada", Icons.Rounded.Speaker, isExternal = false)
    )
    val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val mapped = devices.mapNotNull { dev ->
        val name = dev.productName?.toString()?.trim().orEmpty()
        when (dev.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID -> DetectedOutputRoute(
                label = name.ifBlank { "Bluetooth Audio" },
                subtitle = "Audio inalámbrico conectado",
                icon = Icons.Rounded.BluetoothAudio,
                isExternal = true
            )
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> DetectedOutputRoute(
                label = name.ifBlank { "Audífonos con cable" },
                subtitle = "Conexión directa 3.5mm",
                icon = Icons.Rounded.Headphones,
                isExternal = true
            )
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> DetectedOutputRoute(
                label = name.ifBlank { "DAC / Audífonos USB-C" },
                subtitle = "Salida digital USB",
                icon = Icons.Rounded.Usb,
                isExternal = true
            )
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> DetectedOutputRoute(
                label = "Altavoz del teléfono",
                subtitle = "Salida integrada del dispositivo",
                icon = Icons.Rounded.Speaker,
                isExternal = false
            )
            else -> null
        }
    }.distinctBy { "${it.label}|${it.isExternal}" }
        .sortedByDescending { it.isExternal }

    return mapped.ifEmpty {
        listOf(DetectedOutputRoute("Altavoz del teléfono", "Salida integrada", Icons.Rounded.Speaker, isExternal = false))
    }
}
