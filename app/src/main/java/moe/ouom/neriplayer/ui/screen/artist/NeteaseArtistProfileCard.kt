package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistHeader
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

@Composable
internal fun NeteaseArtistProfileCard(
    header: NeteaseArtistHeader?,
    followUpdating: Boolean,
    offlineMode: Boolean,
    onToggleFollow: () -> Unit
) {
    val context = LocalContext.current
    val coverUrl = header?.coverUrl?.takeIf { it.isNotBlank() } ?: header?.avatarUrl.orEmpty()
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp)
    ) {
        AsyncImage(
            model = offlineCachedImageRequest(
                context = context,
                data = coverUrl,
                offlineMode = offlineMode
            ),
            contentDescription = header?.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        ArtistHeaderDetails(
            header = header,
            followUpdating = followUpdating,
            onToggleFollow = onToggleFollow,
            modifier = Modifier.padding(16.dp),
            tabletProfile = true
        )
    }
}
