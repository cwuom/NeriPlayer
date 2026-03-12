package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

fun SongItem.displayCoverUrl(): String? = customCoverUrl ?: coverUrl
fun SongItem.displayName(): String = customName ?: name
fun SongItem.displayArtist(): String = customArtist ?: artist

fun LocalPlaylist.displayCoverUrl(): String? = customCoverUrl ?: songs.lastOrNull()?.displayCoverUrl()
