package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpatialAudio
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.ui.components.GlassCard
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraSuccess
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.viewmodel.SonoraViewModel
import com.example.ui.viewmodel.UpdateCheckUiState
import kotlinx.coroutines.delay

private enum class SettingsPage(val title: String, val subtitle: String) {
    ROOT("Ajustes", "Cuenta, audio, apariencia y biblioteca"),
    AUDIO_QUALITY("Reproducción y sonido", "Dolby Atmos, Audio Espacial, AutoMix y ecualizador"),
    DJ_SONY("DJ Sony", "Locutor de mezclas y voz local de respaldo"),
    APPEARANCE("Apariencia e ícono", "Paleta, ícono de app y cristal líquido"),
    LYRICS("Letras sincronizadas", "Desfase por canción y recarga de proveedores"),
    STORAGE_BACKUP("Descargas y respaldo", "Almacenamiento sin conexión, copias JSON y caché"),
    INTEGRATIONS("Scrobbling y Discord", "ListenBrainz, Last.fm y Discord Rich Presence"),
    DIAGNOSTICS("Diagnóstico", "Probar portada animada, token y candidatos"),
    ABOUT("Acerca de Sonora", "Versión, repositorio en GitHub y licencias")
}

private data class SettingsSearchEntry(
    val title: String,
    val subtitle: String,
    val keywords: String,
    val icon: ImageVector,
    val tileColor: Color,
    val targetPage: SettingsPage,
    val tag: String
)

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
    val sleepAfterCurrentTrack by viewModel.sleepAfterCurrentTrack.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val updateCheckState by viewModel.updateCheckState.collectAsState()
    val djLiveState by viewModel.djLiveState.collectAsState()
    val availableDeviceSpanishVoices by viewModel.availableDeviceSpanishVoices.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val accent = LocalSonoraAccent.current
    val palette = LocalSonoraPalette.current

    var currentPage by remember { mutableStateOf(SettingsPage.ROOT) }
    var searchQuery by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = currentPage != SettingsPage.ROOT || searchQuery.isNotBlank()) {
        if (currentPage != SettingsPage.ROOT) {
            currentPage = SettingsPage.ROOT
        } else {
            searchQuery = ""
        }
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

    val searchIndex = remember {
        listOf(
            SettingsSearchEntry(
                title = "Cuenta de Google / YouTube Music",
                subtitle = "Conectar, sincronizar historial o desconectar",
                keywords = "cuenta google youtube music sincronizar sesion",
                icon = Icons.Rounded.AccountCircle,
                tileColor = Color(0xFF0A84FF),
                targetPage = SettingsPage.ROOT,
                tag = "search_item_account"
            ),
            SettingsSearchEntry(
                title = "Dolby Atmos y Audio Espacial",
                subtitle = "Sonido envolvente, proyección 360° y AutoMix",
                keywords = "dolby atmos espacial automix crossfade fundido mezcla",
                icon = Icons.Rounded.SurroundSound,
                tileColor = Color(0xFF5856D6),
                targetPage = SettingsPage.AUDIO_QUALITY,
                tag = "search_item_atmos"
            ),
            SettingsSearchEntry(
                title = "Calidad de audio y ecualizador",
                subtitle = "Calidad Wi-Fi/móvil, bajos, normalizador, velocidad y temporizador",
                keywords = "calidad wifi movil ecualizador eq bajos normalizador radio silencios velocidad tono temporizador",
                icon = Icons.Rounded.Headphones,
                tileColor = Color(0xFFFF3B30),
                targetPage = SettingsPage.AUDIO_QUALITY,
                tag = "search_item_audio"
            ),
            SettingsSearchEntry(
                title = "DJ Sony",
                subtitle = "Voz de locutor Gemini y respaldo local del teléfono",
                keywords = "dj sony voz locutor gemini tts telefono",
                icon = Icons.Rounded.RecordVoiceOver,
                tileColor = Color(0xFFFF9500),
                targetPage = SettingsPage.DJ_SONY,
                tag = "search_item_dj"
            ),
            SettingsSearchEntry(
                title = "Color de acento y espectro libre",
                subtitle = "Paleta de negro teñido y presets de acento",
                keywords = "acento color paleta tema espectro personalizado",
                icon = Icons.Rounded.Palette,
                tileColor = Color(0xFFAF52DE),
                targetPage = SettingsPage.APPEARANCE,
                tag = "search_item_accent"
            ),
            SettingsSearchEntry(
                title = "Ícono de la aplicación",
                subtitle = "Elige entre las 8 variantes de Sonora S-Wave",
                keywords = "icono app launcher variante esmeralda carmesi obsidiana",
                icon = Icons.Rounded.AutoAwesome,
                tileColor = Color(0xFFFF2D55),
                targetPage = SettingsPage.APPEARANCE,
                tag = "search_item_icon"
            ),
            SettingsSearchEntry(
                title = "Portadas animadas y cristal líquido",
                subtitle = "Canvas con datos móviles, fondo de reproductor y desenfoque",
                keywords = "canvas portada animada datos moviles cristal liquido backdrop obsidiana",
                icon = Icons.Rounded.AutoAwesome,
                tileColor = Color(0xFFFF375F),
                targetPage = SettingsPage.APPEARANCE,
                tag = "search_item_canvas"
            ),
            SettingsSearchEntry(
                title = "Letras sincronizadas y desfase",
                subtitle = "Ajusta ±2 s en pasos de 100 ms o recarga proveedores",
                keywords = "letras sincronizadas offset desfase lrclib kugou recargar",
                icon = Icons.Rounded.Lyrics,
                tileColor = Color(0xFF32ADE6),
                targetPage = SettingsPage.LYRICS,
                tag = "search_item_lyrics"
            ),
            SettingsSearchEntry(
                title = "Descargas y caché",
                subtitle = "Solo Wi-Fi, liberar almacenamiento y limpiar historial",
                keywords = "descargas offline wifi cache caratulas historial busqueda",
                icon = Icons.Rounded.Folder,
                tileColor = Color(0xFF0A84FF),
                targetPage = SettingsPage.STORAGE_BACKUP,
                tag = "search_item_storage"
            ),
            SettingsSearchEntry(
                title = "Copia de seguridad (JSON)",
                subtitle = "Exportar o restaurar playlists y canciones guardadas",
                keywords = "copia seguridad respaldo backup exportar importar json",
                icon = Icons.Rounded.Folder,
                tileColor = Color(0xFF34C759),
                targetPage = SettingsPage.STORAGE_BACKUP,
                tag = "search_item_backup"
            ),
            SettingsSearchEntry(
                title = "Scrobbling y Discord RPC",
                subtitle = "Conecta ListenBrainz, Last.fm y Discord Rich Presence",
                keywords = "listenbrainz lastfm last.fm discord rpc scrobble",
                icon = Icons.Rounded.Hub,
                tileColor = Color(0xFF5856D6),
                targetPage = SettingsPage.INTEGRATIONS,
                tag = "search_item_integrations"
            ),
            SettingsSearchEntry(
                title = "Diagnóstico de portada animada",
                subtitle = "Probar búsqueda oficial en Apple Music, token, candidatos y URLs",
                keywords = "diagnostico probar portada animada canvas apple music spotify token log",
                icon = Icons.Rounded.BugReport,
                tileColor = Color(0xFF30D158),
                targetPage = SettingsPage.DIAGNOSTICS,
                tag = "search_item_diagnostics"
            ),
            SettingsSearchEntry(
                title = "Acerca de Sonora y actualizaciones",
                subtitle = "Versión, actualizaciones en la app, Wi-Fi y licencias",
                keywords = "acerca version actualizaciones abrir wifi github licencias apache backdrop",
                icon = Icons.Rounded.Info,
                tileColor = Color(0xFF636366),
                targetPage = SettingsPage.ABOUT,
                tag = "search_item_about"
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("settings_screen")
    ) {
        // Persistent Top Header with Liquid Glass Back Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                    .sonoraLiquidGlassSurface(
                        shape = CircleShape,
                        blurRadius = 8.dp,
                        refractionHeight = 6.dp,
                        refractionAmount = 10.dp,
                        surfaceAlpha = 0.40f
                    )
                    .minimumInteractiveComponentSize()
                    .testTag("settings_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Volver",
                    tint = SonoraTextPrimary
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentPage.title,
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = currentPage.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (statusBanner != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.elevated,
                border = BorderStroke(0.5.dp, accent.copy(alpha = 0.65f)),
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
                    val filteredSearch = remember(searchQuery, searchIndex) {
                        val q = searchQuery.trim().lowercase()
                        if (q.isEmpty()) emptyList()
                        else searchIndex.filter { entry ->
                            entry.title.lowercase().contains(q) ||
                                entry.subtitle.lowercase().contains(q) ||
                                entry.keywords.contains(q)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomContentPadding)
                    ) {
                        // Search Bar (Apple Music / iOS Settings style)
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(palette.surface)
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = SonoraTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = "Buscar ajustes...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SonoraTextSecondary
                                            )
                                        }
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = SonoraTextPrimary),
                                            cursorBrush = SolidColor(accent),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("settings_search_input")
                                        )
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Limpiar búsqueda",
                                            tint = SonoraTextSecondary,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clickable { searchQuery = "" }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        if (searchQuery.isNotBlank()) {
                            item {
                                SettingsSectionTitle("Resultados")
                                if (filteredSearch.isEmpty()) {
                                    GlassCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        borderWidth = 0.dp
                                    ) {
                                        Text(
                                            text = "No se encontraron ajustes para «$searchQuery»",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = SonoraTextSecondary,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                } else {
                                    GlassCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        borderWidth = 0.dp
                                    ) {
                                        Column {
                                            filteredSearch.forEachIndexed { idx, entry ->
                                                SettingsSubmenuRow(
                                                    icon = entry.icon,
                                                    tileColor = entry.tileColor,
                                                    title = entry.title,
                                                    subtitle = entry.subtitle,
                                                    accent = accent,
                                                    tag = entry.tag,
                                                    onClick = {
                                                        searchQuery = ""
                                                        currentPage = entry.targetPage
                                                    }
                                                )
                                                if (idx < filteredSearch.lastIndex) SettingsHairlineDivider(58.dp)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(18.dp))
                            }
                        } else {
                            // Section: Google / YouTube Music Account (Moved cleanly here from Library)
                            item {
                                SettingsSectionTitle("Cuenta y sincronización")
                                GlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .testTag("account_connection_card"),
                                    shape = RoundedCornerShape(20.dp),
                                    borderWidth = 0.dp
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (accountSession != null && accountSession!!.avatarUrl.isNotBlank()) {
                                                AsyncImage(
                                                    model = accountSession!!.avatarUrl,
                                                    contentDescription = accountSession!!.accountName,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(CircleShape)
                                                        .background(palette.elevated),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.AccountCircle,
                                                        contentDescription = null,
                                                        tint = if (accountSession != null) SonoraSuccess else SonoraTextPrimary,
                                                        modifier = Modifier.size(30.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = accountSession?.accountName ?: "Sin cuenta conectada",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = SonoraTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = if (accountSession != null) {
                                                        "Conectada con Google / YouTube Music"
                                                    } else {
                                                        "Conecta tu cuenta para sincronizar historial y playlists"
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = SonoraTextSecondary
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(14.dp))
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
                                                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(46.dp)
                                                        .testTag("settings_sync_button")
                                                ) {
                                                    Icon(Icons.Rounded.Sync, contentDescription = null, tint = SonoraTextPrimary, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(if (isSyncingYt) "Sincronizando..." else "Sincronizar", color = SonoraTextPrimary)
                                                }
                                                OutlinedButton(
                                                    onClick = onSignOut,
                                                    border = BorderStroke(0.5.dp, accent.copy(alpha = 0.5f)),
                                                    shape = RoundedCornerShape(14.dp),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(46.dp)
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

                            // Navigable iOS Grouped Submenus
                            item {
                                Spacer(Modifier.height(18.dp))
                                SettingsSectionTitle("Preferencias")
                                val activeOffsetMs = settings.lyricsOffsetFor(playbackState.currentTrack?.videoId)
                                val lyricsValue = if (activeOffsetMs > 0) "+${activeOffsetMs} ms" else "${activeOffsetMs} ms"
                                val anyIntegrationActive = settings.listenBrainzEnabled ||
                                    settings.lastFmEnabled ||
                                    settings.discordRpcEnabled

                                GlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    borderWidth = 0.dp
                                ) {
                                    Column {
                                        SettingsSubmenuRow(
                                            icon = Icons.Rounded.Headphones,
                                            tileColor = Color(0xFFFF3B30),
                                            title = "Reproducción y sonido",
                                            valueText = settings.wifiAudioQuality.label,
                                            badgeText = if (settings.dolbyAtmosEnabled) "ATMOS" else if (settings.spatialAudioEnabled) "360°" else null,
                                            accent = accent,
                                            tag = "settings_submenu_audio",
                                            onClick = { currentPage = SettingsPage.AUDIO_QUALITY }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSubmenuRow(
                                            icon = Icons.Rounded.RecordVoiceOver,
                                            tileColor = Color(0xFFFF9500),
                                            title = "DJ Sony",
                                            valueText = settings.djVoiceName,
                                            accent = accent,
                                            tag = "settings_submenu_dj",
                                            onClick = { currentPage = SettingsPage.DJ_SONY }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSubmenuRow(
                                            icon = Icons.Rounded.Palette,
                                            tileColor = Color(0xFFAF52DE),
                                            title = "Apariencia e ícono",
                                            valueText = settings.accentPreset.label.substringBefore(" "),
                                            accent = accent,
                                            tag = "settings_submenu_appearance",
                                            onClick = { currentPage = SettingsPage.APPEARANCE }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSubmenuRow(
                                            icon = Icons.Rounded.Lyrics,
                                            tileColor = Color(0xFF32ADE6),
                                            title = "Letras sincronizadas",
                                            valueText = lyricsValue,
                                            accent = accent,
                                            tag = "settings_submenu_lyrics",
                                            onClick = { currentPage = SettingsPage.LYRICS }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSubmenuRow(
                                            icon = Icons.Rounded.Folder,
                                            tileColor = Color(0xFF0A84FF),
                                            title = "Descargas y respaldo",
                                            valueText = "${downloadedTracks.size}",
                                            accent = accent,
                                            tag = "settings_submenu_storage",
                                            onClick = { currentPage = SettingsPage.STORAGE_BACKUP }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSubmenuRow(
                                            icon = Icons.Rounded.Hub,
                                            tileColor = Color(0xFF5856D6),
                                            title = "Scrobbling y Discord",
                                            valueText = if (anyIntegrationActive) "Activo" else "Inactivo",
                                            accent = accent,
                                            tag = "settings_submenu_integrations",
                                            onClick = { currentPage = SettingsPage.INTEGRATIONS }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSubmenuRow(
                                            icon = Icons.Rounded.BugReport,
                                            tileColor = Color(0xFF30D158),
                                            title = "Diagnóstico",
                                            subtitle = "Probar portada animada y registros",
                                            accent = accent,
                                            tag = "settings_submenu_diagnostics",
                                            onClick = { currentPage = SettingsPage.DIAGNOSTICS }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSubmenuRow(
                                            icon = Icons.Rounded.Info,
                                            tileColor = Color(0xFF636366),
                                            title = "Acerca de Sonora",
                                            valueText = "v${BuildConfig.VERSION_NAME}",
                                            badgeText = if (updateCheckState is UpdateCheckUiState.UpdateAvailable) "NUEVA" else null,
                                            accent = accent,
                                            tag = "settings_submenu_about",
                                            onClick = { currentPage = SettingsPage.ABOUT }
                                        )
                                    }
                                }
                            }

                            // Quick Audio Access Toggles
                            item {
                                Spacer(Modifier.height(18.dp))
                                SettingsSectionTitle("Accesos rápidos de sonido")
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
                                            subtitle = "Sonido envolvente y profundidad",
                                            checked = settings.dolbyAtmosEnabled,
                                            tag = "root_switch_dolby_atmos",
                                            onCheckedChange = { viewModel.setDolbyAtmosEnabled(it) }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSwitchRow(
                                            icon = Icons.Rounded.SpatialAudio,
                                            tileColor = Color(0xFF0A84FF),
                                            title = "Audio Espacial",
                                            subtitle = "Amplitud sonora 360°",
                                            checked = settings.spatialAudioEnabled,
                                            tag = "root_switch_spatial_audio",
                                            onCheckedChange = { viewModel.setSpatialAudioEnabled(it) }
                                        )
                                        SettingsHairlineDivider(58.dp)
                                        SettingsSwitchRow(
                                            icon = Icons.Rounded.AutoAwesome,
                                            tileColor = Color(0xFFFF2D55),
                                            title = "Mezcla Automática (AutoMix)",
                                            subtitle = "Transiciones calculadas al pulso",
                                            checked = settings.autoMixEnabled,
                                            tag = "root_switch_automix",
                                            onCheckedChange = { viewModel.setAutoMixEnabled(it) }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(18.dp))
                            }
                        }
                    }
                }

                SettingsPage.AUDIO_QUALITY -> SettingsAudioQualityPage(
                    viewModel = viewModel,
                    settings = settings,
                    sleepTimerRemainingSec = sleepTimerRemainingSec,
                    sleepAfterCurrentTrack = sleepAfterCurrentTrack,
                    accent = accent,
                    bottomContentPadding = bottomContentPadding,
                    onStatusBanner = { statusBanner = it }
                )

                SettingsPage.DJ_SONY -> SettingsDjSonyPage(
                    viewModel = viewModel,
                    settings = settings,
                    djLiveState = djLiveState,
                    availableDeviceSpanishVoices = availableDeviceSpanishVoices,
                    accent = accent,
                    bottomContentPadding = bottomContentPadding
                )

                SettingsPage.APPEARANCE -> SettingsAppearancePage(
                    viewModel = viewModel,
                    settings = settings,
                    accent = accent,
                    bottomContentPadding = bottomContentPadding,
                    onStatusBanner = { statusBanner = it }
                )

                SettingsPage.LYRICS -> SettingsLyricsPage(
                    viewModel = viewModel,
                    settings = settings,
                    accent = accent,
                    bottomContentPadding = bottomContentPadding,
                    onStatusBanner = { statusBanner = it }
                )

                SettingsPage.STORAGE_BACKUP -> SettingsStorageBackupPage(
                    viewModel = viewModel,
                    settings = settings,
                    downloadedTracks = downloadedTracks,
                    bottomContentPadding = bottomContentPadding,
                    onExportBackup = { exportBackupLauncher.launch("sonora_respaldo.json") },
                    onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                    onStatusBanner = { statusBanner = it }
                )

                SettingsPage.INTEGRATIONS -> SettingsIntegrationsPage(
                    viewModel = viewModel,
                    settings = settings,
                    accent = accent,
                    bottomContentPadding = bottomContentPadding,
                    onStatusBanner = { statusBanner = it }
                )

                SettingsPage.DIAGNOSTICS -> SettingsDiagnosticsPage(
                    settings = settings,
                    accent = accent,
                    bottomContentPadding = bottomContentPadding
                )

                SettingsPage.ABOUT -> SettingsAboutPage(
                    viewModel = viewModel,
                    settings = settings,
                    updateCheckState = updateCheckState,
                    accent = accent,
                    bottomContentPadding = bottomContentPadding,
                    onStatusBanner = { statusBanner = it }
                )
            }
        }
    }
}
