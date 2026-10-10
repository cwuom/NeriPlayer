package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YouTubeMusicCreatorTabletProfile(
    header: YouTubeMusicCreatorHeader,
    offlineMode: Boolean,
    followFavorite: FavoritePlaylist?
) {
    val context = LocalContext.current
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("youtubeCreatorTabletProfile"),
        shape = RoundedCornerShape(28.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1.25f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (header.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = context,
                        data = header.coverUrl,
                        sizePx = 768,
                        offlineMode = offlineMode
                    ),
                    contentDescription = header.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = header.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (header.subtitle.isNotBlank()) {
                Text(
                    text = header.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (followFavorite != null) {
                CreatorFollowButton(favorite = followFavorite)
            }
            val metadata = listOf(
                header.monthlyListenerCountText,
                header.subscriberCountText
            ).filter { it.isNotBlank() && !header.subtitle.contains(it, ignoreCase = true) }.distinct()
            if (metadata.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    metadata.forEach { value ->
                        AssistChip(onClick = {}, label = { Text(value) })
                    }
                }
            }
            if (header.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = header.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
