package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object ExploreRoute

@Serializable
data object SearchRoute

@Serializable
data object LibraryRoute

@Serializable
data class DetailRoute(
    val browseId: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String = "",
    val params: String? = null
)

@Serializable
data class LocalPlaylistRoute(
    val playlistId: Long
)

@Serializable
data object SettingsRoute

