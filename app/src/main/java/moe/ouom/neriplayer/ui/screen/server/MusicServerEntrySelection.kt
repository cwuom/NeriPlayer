package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.ui.util.savedNonBlankString
import moe.ouom.neriplayer.ui.util.savedNumber
import moe.ouom.neriplayer.ui.util.savedString

internal val serverArtistSummarySaver = mapSaver<ServerArtist?>(
    save = { artist -> artist?.let { mapOf("profileId" to it.profileId, "id" to it.id,
        "name" to it.name, "coverUrl" to it.coverUrl, "albumCount" to it.albumCount,
        "fallbackCoverUrl" to it.fallbackCoverUrl) }.orEmpty() },
    restore = { saved ->
        val profileId = saved.savedNonBlankString("profileId")
        val id = saved.savedNonBlankString("id")
        if (profileId == null || id == null) null else ServerArtist(profileId, id,
            saved.savedString("name").orEmpty(), saved.savedString("coverUrl"),
            saved.savedNumber("albumCount")?.toInt()?.coerceAtLeast(0) ?: 0,
            saved.savedString("fallbackCoverUrl"))
    }
)

internal val serverAlbumSummarySaver = mapSaver<ServerAlbum?>(
    save = { album -> album?.let { mapOf("profileId" to it.profileId, "id" to it.id,
        "name" to it.name, "artist" to it.artist, "coverUrl" to it.coverUrl, "songCount" to it.songCount) }.orEmpty() },
    restore = { saved ->
        val profileId = saved.savedNonBlankString("profileId")
        val id = saved.savedNonBlankString("id")
        if (profileId == null || id == null) null else ServerAlbum(profileId, id,
            saved.savedString("name").orEmpty(), saved.savedString("artist").orEmpty(), saved.savedString("coverUrl"),
            saved.savedNumber("songCount")?.toInt()?.coerceAtLeast(0) ?: 0)
    }
)

internal class MusicServerEntryState(
    openedState: MutableState<Boolean>,
    albumState: MutableState<ServerAlbum?>,
    artistState: MutableState<ServerArtist?>
) {
    var opened by openedState
    var album by albumState
    var artist by artistState

    fun close() { opened = false; album = null; artist = null }

    fun back() {
        when {
            album != null -> album = null
            artist != null -> artist = null
            else -> close()
        }
    }
}

@Composable
internal fun rememberMusicServerEntryState(): MusicServerEntryState {
    val opened = rememberSaveable { mutableStateOf(false) }
    val album = rememberSaveable(stateSaver = serverAlbumSummarySaver) { mutableStateOf<ServerAlbum?>(null) }
    val artist = rememberSaveable(stateSaver = serverArtistSummarySaver) { mutableStateOf<ServerArtist?>(null) }
    return remember(opened, album, artist) { MusicServerEntryState(opened, album, artist) }
}
