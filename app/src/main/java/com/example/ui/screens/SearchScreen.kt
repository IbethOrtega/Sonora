package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.BrowseCard
import com.example.model.SearchFilter
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.BrowseCardItem
import com.example.ui.components.ErrorStateGlass
import com.example.ui.components.GlassCard
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.SonoraCrimson
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
    onTrackMore: (Track) -> Unit
) {
    val focusManager = LocalFocusManager.current
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
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Buscar",
                    style = MaterialTheme.typography.displayLarge,
                    color = SonoraTextPrimary
                )
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
                            tint = SonoraCrimson
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
                        focusedContainerColor = Color(0xFF1A1A25).copy(alpha = 0.78f),
                        unfocusedContainerColor = Color(0xFF161620).copy(alpha = 0.68f),
                        focusedBorderColor = SonoraCrimson.copy(alpha = 0.85f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                        cursorColor = SonoraCrimson,
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
                            selectedContainerColor = SonoraCrimson,
                            selectedLabelColor = Color.White,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = SonoraTextSecondary
                        ),
                        border = BorderStroke(
                            0.7.dp,
                            if (selected) SonoraCrimson else Color.White.copy(alpha = 0.14f)
                        ),
                        modifier = Modifier.testTag("search_filter_${filter.name}")
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Autocomplete suggestions
        if (searchState.suggestions.isNotEmpty() && searchState.results == null && !searchState.isSearching) {
            item {
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
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SonoraCrimson)
                    }
                }
            }

            searchState.error != null -> {
                item {
                    ErrorStateGlass(
                        message = searchState.error,
                        onRetry = { onSubmitSearch(null) }
                    )
                }
            }

            searchState.results != null -> {
                val results = searchState.results
                if (results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()) {
                    item {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
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

                if (results.songs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Canciones",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                results.songs.forEach { song ->
                                    TrackRowItem(
                                        track = song,
                                        isPlayingCurrent = playbackState.currentTrack?.videoId == song.videoId,
                                        isActuallyPlaying = playbackState.isPlaying,
                                        onClick = { onPlayTrack(song, results.songs) },
                                        onMoreClick = { onTrackMore(song) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                if (results.artists.isNotEmpty()) {
                    item {
                        SearchCardShelf(
                            title = "Artistas",
                            cards = results.artists,
                            onOpenCard = onOpenCard
                        )
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item {
                        SearchCardShelf(
                            title = "Álbumes",
                            cards = results.albums,
                            onOpenCard = onOpenCard
                        )
                    }
                }
                if (results.playlists.isNotEmpty()) {
                    item {
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
                    item {
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

                item {
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
                                                    .background(SonoraCrimson)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = prompt,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = SonoraTextPrimary
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
