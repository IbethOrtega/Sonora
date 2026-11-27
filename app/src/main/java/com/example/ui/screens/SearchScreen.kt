package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.BrowseCard
import com.example.model.ItemKind
import com.example.model.SearchFilter
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.BrowseCardItem
import com.example.ui.components.ErrorStateGlass
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.TrackRowItem
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.viewmodel.SearchUiState

@Composable
fun SearchScreen(
    searchState: SearchUiState,
    searchHistory: List<String>,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onQueryChange: (String) -> Unit,
    onFilterChange: (SearchFilter) -> Unit,
    onSubmitSearch: (String?) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onOpenCard: (BrowseCard) -> Unit,
    onTrackMore: (Track) -> Unit,
    onOpenSongMixer: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    val accent = LocalSonoraAccent.current
    val palette = LocalSonoraPalette.current
    val quickPrompts = listOf(
        "Bad Bunny", "Taylor Swift", "Dua Lipa", "Daft Punk",
        "Música Chill", "Rock en Español", "Lo-Fi Beats", "The Weeknd"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("search_screen"),
        contentPadding = PaddingValues(top = 16.dp, bottom = bottomContentPadding)
    ) {
        item(key = "search_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Buscar",
                        style = MaterialTheme.typography.displayLarge,
                        color = SonoraTextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .sonoraLiquidGlassSurface(
                                shape = RoundedCornerShape(50),
                                blurRadius = 14.dp,
                                refractionHeight = 6.dp,
                                refractionAmount = 10.dp,
                                surfaceAlpha = 0.48f
                            )
                            .clickable(onClick = onOpenSongMixer)
                            .minimumInteractiveComponentSize()
                            .testTag("search_open_mixer_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "Mezclar 2 canciones",
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Mezclar 2 temas",
                                style = MaterialTheme.typography.labelLarge,
                                color = SonoraTextPrimary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            "Artistas, canciones, álbumes o letras",
                            color = SonoraTextTertiary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = accent
                        )
                    },
                    trailingIcon = {
                        if (searchState.query.isNotBlank()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Limpiar búsqueda",
                                    tint = SonoraTextSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            onSubmitSearch(null)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.elevated,
                        unfocusedContainerColor = palette.surface,
                        focusedBorderColor = Color.White.copy(alpha = 0.24f),
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = accent,
                        focusedTextColor = SonoraTextPrimary,
                        unfocusedTextColor = SonoraTextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_input")
                )
            }
            Spacer(Modifier.height(10.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SearchFilter.entries) { filter ->
                    val selected = searchState.filter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onFilterChange(filter) },
                        label = {
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.elevated,
                            selectedLabelColor = accent,
                            containerColor = palette.surface,
                            labelColor = SonoraTextSecondary
                        ),
                        border = null,
                        modifier = Modifier.testTag("search_filter_${filter.name}")
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Autocomplete suggestions powered by NewPipe + YTM
        if (searchState.suggestions.isNotEmpty() && searchState.results == null && !searchState.isSearching) {
            item(key = "autocomplete_suggestions") {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        searchState.suggestions.forEach { suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        focusManager.clearFocus()
                                        onSubmitSearch(suggestion)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = SonoraTextTertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = SonoraTextPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        when {
            searchState.isSearching -> {
                item(key = "searching_loader") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = accent)
                        Text(
                            text = "Buscando en YouTube Music con NewPipe...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary
                        )
                    }
                }
            }

            searchState.error != null -> {
                item(key = "search_error") {
                    ErrorStateGlass(
                        message = searchState.error,
                        onRetry = { onSubmitSearch(null) }
                    )
                }
            }

            searchState.results != null -> {
                val results = searchState.results
                val corrected = results.searchSuggestion
                if (!corrected.isNullOrBlank()) {
                    item(key = "search_did_you_mean") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    focusManager.clearFocus()
                                    onSubmitSearch(corrected)
                                }
                                .testTag("search_correction_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "¿Quisiste decir \"$corrected\"?",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = SonoraTextPrimary
                                )
                            }
                        }
                    }
                }

                if (results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()) {
                    item(key = "empty_results") {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Sin resultados para \"${searchState.query}\"",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = SonoraTextPrimary
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Prueba con otro artista, título o filtro.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SonoraTextSecondary
                                )
                            }
                        }
                    }
                }

                // Virtualized song results list with song titles & high-res album artwork
                if (results.songs.isNotEmpty()) {
                    item(key = "songs_section_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Canciones",
                                style = MaterialTheme.typography.headlineMedium,
                                color = SonoraTextPrimary
                            )
                            Text(
                                text = "${results.songs.size} temas",
                                style = MaterialTheme.typography.labelMedium,
                                color = SonoraTextTertiary
                            )
                        }
                    }

                    item(key = "songs_grouped_card") {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(20.dp),
                            borderWidth = 0.dp
                        ) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                results.songs.forEachIndexed { index, song ->
                                    TrackRowItem(
                                        track = song,
                                        isPlayingCurrent = playbackState.currentTrack?.videoId == song.videoId,
                                        isActuallyPlaying = playbackState.isPlaying,
                                        showDivider = index < results.songs.lastIndex,
                                        onClick = { onPlayTrack(song, results.songs) },
                                        onMoreClick = { onTrackMore(song) }
                                    )
                                }
                            }
                        }
                    }

                    item(key = "songs_spacer") {
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // When filtered specifically to Albums/Artists/Playlists, render a full vertical list with artwork
                if (searchState.filter == SearchFilter.ARTISTS && results.artists.isNotEmpty()) {
                    item(key = "artists_list_header") {
                        SearchListSectionHeader("Artistas", results.artists.size)
                    }
                    items(results.artists, key = { "artist_row_${it.id}" }) { card ->
                        SearchBrowseListRow(card = card, onClick = { onOpenCard(card) })
                    }
                } else if (results.artists.isNotEmpty()) {
                    item(key = "artists_shelf") {
                        SearchCardShelf(
                            title = "Artistas",
                            cards = results.artists,
                            onOpenCard = onOpenCard
                        )
                    }
                }

                if (searchState.filter == SearchFilter.ALBUMS && results.albums.isNotEmpty()) {
                    item(key = "albums_list_header") {
                        SearchListSectionHeader("Álbumes", results.albums.size)
                    }
                    items(results.albums, key = { "album_row_${it.id}" }) { card ->
                        SearchBrowseListRow(card = card, onClick = { onOpenCard(card) })
                    }
                } else if (results.albums.isNotEmpty()) {
                    item(key = "albums_shelf") {
                        SearchCardShelf(
                            title = "Álbumes",
                            cards = results.albums,
                            onOpenCard = onOpenCard
                        )
                    }
                }

                if (searchState.filter == SearchFilter.PLAYLISTS && results.playlists.isNotEmpty()) {
                    item(key = "playlists_list_header") {
                        SearchListSectionHeader("Playlists", results.playlists.size)
                    }
                    items(results.playlists, key = { "playlist_row_${it.id}" }) { card ->
                        SearchBrowseListRow(card = card, onClick = { onOpenCard(card) })
                    }
                } else if (results.playlists.isNotEmpty()) {
                    item(key = "playlists_shelf") {
                        SearchCardShelf(
                            title = "Playlists",
                            cards = results.playlists,
                            onOpenCard = onOpenCard
                        )
                    }
                }
            }

            else -> {
                if (searchHistory.isNotEmpty()) {
                    item(key = "recent_searches") {
                        Text(
                            text = "Búsquedas recientes",
                            style = MaterialTheme.typography.titleLarge,
                            color = SonoraTextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(modifier = Modifier.padding(4.dp)) {
                                searchHistory.forEach { itemQuery ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                focusManager.clearFocus()
                                                onSubmitSearch(itemQuery)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.History,
                                            contentDescription = null,
                                            tint = SonoraTextTertiary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            text = itemQuery,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = SonoraTextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { onDeleteHistory(itemQuery) },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Eliminar del historial",
                                                tint = SonoraTextTertiary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }

                item(key = "suggested_prompts") {
                    Text(
                        text = "Búsquedas sugeridas",
                        style = MaterialTheme.typography.titleLarge,
                        color = SonoraTextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        quickPrompts.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                pair.forEach { prompt ->
                                    GlassCard(
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("quick_search_$prompt"),
                                        shape = RoundedCornerShape(16.dp),
                                        onClick = {
                                            focusManager.clearFocus()
                                            onSubmitSearch(prompt)
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(accent)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = prompt,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = SonoraTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchListSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = SonoraTextPrimary
        )
        Text(
            text = "$count resultados",
            style = MaterialTheme.typography.labelMedium,
            color = SonoraTextTertiary
        )
    }
}

@Composable
private fun SearchBrowseListRow(
    card: BrowseCard,
    onClick: () -> Unit
) {
    val isArtist = card.kind == ItemKind.ARTIST
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("search_browse_row_${card.id}"),
        shape = RoundedCornerShape(18.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SonoraArtwork(
                url = card.artworkUrl,
                contentDescription = card.title,
                modifier = Modifier.size(56.dp),
                cornerRadius = 12.dp,
                isCircular = isArtist
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (card.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = card.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchCardShelf(
    title: String,
    cards: List<BrowseCard>,
    onOpenCard: (BrowseCard) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = SonoraTextPrimary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(cards, key = { "search_${title}_${it.id}" }) { card ->
                BrowseCardItem(card = card, onClick = { onOpenCard(card) })
            }
        }
    }
}
