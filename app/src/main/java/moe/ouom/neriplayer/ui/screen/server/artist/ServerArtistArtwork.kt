package moe.ouom.neriplayer.ui.screen.server.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

@Composable
internal fun ServerArtistArtwork(
    artist: ServerArtist,
    offlineMode: Boolean,
    modifier: Modifier = Modifier,
    sizePx: Int = 256
) {
    var serverFailed by remember(artist.profileId, artist.id, artist.coverUrl, offlineMode) { mutableStateOf(false) }
    var fallbackFailed by remember(artist.profileId, artist.id, artist.fallbackCoverUrl, offlineMode) { mutableStateOf(false) }
    val useServer = !serverFailed && !artist.coverUrl.isNullOrBlank()
    val url = if (useServer) artist.coverUrl else artist.fallbackCoverUrl.takeUnless { fallbackFailed }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        .testTag("serverArtistArtwork:${artist.profileId}:${artist.id}"), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Outlined.AccountCircle, null, Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(
                model = offlineCachedImageRequest(LocalContext.current, url, sizePx = sizePx,
                    allowHardware = false, offlineMode = offlineMode),
                contentDescription = artist.name, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { if (useServer) serverFailed = true else fallbackFailed = true }
            )
        }
    }
}
