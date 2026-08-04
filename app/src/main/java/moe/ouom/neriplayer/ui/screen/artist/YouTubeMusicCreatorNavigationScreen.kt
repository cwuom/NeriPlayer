package moe.ouom.neriplayer.ui.screen.artist

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist

@Composable
fun YouTubeMusicCreatorNavigationScreen(
    creator: YouTubeMusicCreatorSummary,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onPlaylistClick: (YouTubeMusicPlaylist) -> Unit = {},
    onCreatorClick: (YouTubeMusicCreatorSummary) -> Unit = {},
    offlineMode: Boolean = false
) {
    var selectedSection by remember(creator.browseId) {
        mutableStateOf<YouTubeMusicCreatorSection?>(null)
    }
    BackHandler(enabled = selectedSection != null) {
        selectedSection = null
    }

    val section = selectedSection
    if (section != null) {
        YouTubeMusicCreatorItemsScreen(
            section = section,
            creatorName = creator.title,
            onBack = { selectedSection = null },
            onSongClick = onSongClick,
            offlineMode = offlineMode
        )
    } else {
        YouTubeMusicCreatorDetailScreen(
            creator = creator,
            onBack = onBack,
            onSongClick = onSongClick,
            onPlaylistClick = onPlaylistClick,
            onCreatorClick = onCreatorClick,
            onSectionMoreClick = { selectedSection = it },
            offlineMode = offlineMode
        )
    }
}
