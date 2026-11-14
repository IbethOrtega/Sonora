package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.SpotifyImportState
import com.example.ui.components.GlassCard
import com.example.ui.theme.SonoraBackground
import com.example.ui.theme.SonoraCrimson
import com.example.ui.theme.SonoraSuccess
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary

private const val YTM_SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F"
private const val CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInScreen(
    isSyncing: Boolean,
    authError: String?,
    onCookiesCaptured: (String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    var pageProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(YTM_SIGN_IN_URL) }
    var capturedOnce by remember { mutableStateOf(false) }

    fun checkCookiesAndComplete() {
        val cookieManager = CookieManager.getInstance()
        val ytmCookies = cookieManager.getCookie("https://music.youtube.com").orEmpty()
        val ytCookies = cookieManager.getCookie("https://www.youtube.com").orEmpty()
        val combined = listOf(ytmCookies, ytCookies).filter { it.isNotBlank() }.joinToString("; ")
        val hasAuthCookie = combined.contains("SAPISID=") || combined.contains("__Secure-3PAPISID=")
        if (hasAuthCookie && !capturedOnce) {
            capturedOnce = true
            onCookiesCaptured(combined)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SonoraBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("yt_signin_screen")
    ) {
        // Glass Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xE614141C))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("yt_signin_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Cerrar inicio de sesión",
                    tint = SonoraTextPrimary
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Conectar YouTube Music",
                    style = MaterialTheme.typography.titleLarge,
                    color = SonoraTextPrimary
                )
                Text(
                    text = "Inicia sesión con tu cuenta de Google",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary
                )
            }
            if (isSyncing) {
                CircularProgressIndicator(
                    color = SonoraCrimson,
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.2.dp
                )
            } else {
                TextButton(
                    onClick = {
                        capturedOnce = false
                        checkCookiesAndComplete()
                    },
                    modifier = Modifier.testTag("yt_signin_verify_button")
                ) {
                    Text("Sincronizar", color = SonoraCrimson, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (pageProgress in 1..99) {
            LinearProgressIndicator(
                progress = { pageProgress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = SonoraCrimson,
                trackColor = Color.White.copy(alpha = 0.08f)
            )
        }

        if (!authError.isNullOrBlank()) {
            Surface(
                color = SonoraCrimson.copy(alpha = 0.16f),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = authError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = CHROME_MOBILE_UA
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            pageProgress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            url?.let { currentUrl = it }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let { currentUrl = it }
                            if (url != null && url.startsWith("https://music.youtube.com")) {
                                checkCookiesAndComplete()
                            }
                        }
                    }
                    loadUrl(YTM_SIGN_IN_URL)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyImportSheet(
    importState: SpotifyImportState,
    onDismiss: () -> Unit,
    onImportUrl: (String) -> Unit,
    onImportText: (String, String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onReset: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var urlInput by remember { mutableStateOf("") }
    var textPlaylistTitle by remember { mutableStateOf("") }
    var textTracksInput by remember { mutableStateOf("") }

    val spotifyGreen = Color(0xFF1DB954)

    ModalBottomSheet(
        onDismissRequest = {
            onReset()
            onDismiss()
        },
        containerColor = Color(0xFF15151E),
        contentColor = SonoraTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .testTag("spotify_import_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(spotifyGreen.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LibraryAdd,
                            contentDescription = null,
                            tint = spotifyGreen
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Importar desde Spotify",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary
                        )
                        Text(
                            text = "Convierte playlists a YouTube Music sin anuncios",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
                    }
                }
                IconButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = SonoraTextSecondary)
                }
            }

            Spacer(Modifier.height(16.dp))

            when (importState) {
                is SpotifyImportState.FetchingSpotify -> {
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = spotifyGreen)
                            Text(
                                text = importState.message,
                                style = MaterialTheme.typography.titleMedium,
                                color = SonoraTextPrimary
                            )
                        }
                    }
                }

                is SpotifyImportState.MatchingTracks -> {
                    val progress = if (importState.totalCount > 0) {
                        importState.processedCount.toFloat() / importState.totalCount.toFloat()
                    } else 0f

                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Sync, contentDescription = null, tint = SonoraCrimson)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = importState.playlistTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = SonoraTextPrimary
                                )
                            }
                            Text(
                                text = "Emparejando ${importState.processedCount} de ${importState.totalCount} canciones (${importState.matchedCount} encontradas)...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary
                            )
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = spotifyGreen,
                                trackColor = Color.White.copy(alpha = 0.12f)
                            )
                            if (importState.currentTrackName.isNotBlank()) {
                                Text(
                                    text = "Buscando: ${importState.currentTrackName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SonoraTextTertiary
                                )
                            }
                        }
                    }
                }

                is SpotifyImportState.Completed -> {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        tint = spotifyGreen.copy(alpha = 0.25f)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = SonoraSuccess,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "¡Playlist importada!",
                                style = MaterialTheme.typography.headlineMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "\"${importState.playlistTitle}\" • ${importState.matchedCount} de ${importState.totalCount} canciones listas sin anuncios.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onReset,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("Importar otra", color = SonoraTextPrimary)
                                }
                                Button(
                                    onClick = {
                                        val id = importState.playlistId
                                        onReset()
                                        onDismiss()
                                        onOpenPlaylist(id)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SonoraCrimson),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_imported_playlist_button")
                                ) {
                                    Text("Abrir playlist")
                                }
                            }
                        }
                    }
                }

                is SpotifyImportState.Failed -> {
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "No se pudo importar",
                                style = MaterialTheme.typography.titleLarge,
                                color = SonoraCrimson
                            )
                            Text(
                                text = importState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SonoraTextSecondary
                            )
                            Button(
                                onClick = onReset,
                                colors = ButtonDefaults.buttonColors(containerColor = SonoraCrimson),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Intentar de nuevo")
                            }
                        }
                    }
                }

                SpotifyImportState.Idle -> {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = SonoraTextPrimary,
                        indicator = { tabPositions ->
                            SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = spotifyGreen
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Enlace de Spotify") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Lista de texto") }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    if (selectedTab == 0) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("URL de playlist o álbum de Spotify") },
                            placeholder = { Text("https://open.spotify.com/playlist/...") },
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = spotifyGreen) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        clipboard.getText()?.text?.let { urlInput = it }
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentPaste,
                                        contentDescription = "Pegar del portapapeles",
                                        tint = SonoraTextSecondary
                                    )
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = spotifyGreen,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                                focusedLabelColor = spotifyGreen,
                                cursorColor = spotifyGreen
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spotify_url_input")
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Pega cualquier enlace público de playlist o álbum de Spotify (open.spotify.com o spotify.link). Sonora buscará cada canción en YouTube Music automáticamente.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onImportUrl(urlInput) },
                            enabled = urlInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_spotify_url_import")
                        ) {
                            Icon(Icons.Rounded.LibraryAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Importar playlist de Spotify", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedTextField(
                            value = textPlaylistTitle,
                            onValueChange = { textPlaylistTitle = it },
                            label = { Text("Nombre de la playlist") },
                            placeholder = { Text("Mis favoritas de Spotify") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = spotifyGreen,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                                focusedLabelColor = spotifyGreen
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = textTracksInput,
                            onValueChange = { textTracksInput = it },
                            label = { Text("Canciones (una por línea: Título - Artista)") },
                            placeholder = { Text("Blinding Lights - The Weeknd\nTití Me Preguntó - Bad Bunny") },
                            leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = spotifyGreen) },
                            minLines = 4,
                            maxLines = 7,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = spotifyGreen,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                                focusedLabelColor = spotifyGreen
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spotify_text_input")
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onImportText(textPlaylistTitle, textTracksInput) },
                            enabled = textTracksInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_spotify_text_import")
                        ) {
                            Text("Importar canciones", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
