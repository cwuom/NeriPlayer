package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

@Composable
internal fun CreatorDetailProfileCard(
    title: String,
    coverUrl: String?,
    subtitle: String,
    offlineMode: Boolean,
    modifier: Modifier = Modifier,
    tabletProfile: Boolean = false,
    artwork: (@Composable (Modifier) -> Unit)? = null
) {
    val context = LocalContext.current
    val coverModifier = (if (tabletProfile) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(96.dp))
        .clip(neriRoundedShape(18.dp))
    val cover: @Composable () -> Unit = {
        if (artwork != null) {
            artwork(coverModifier)
        } else if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = offlineCachedImageRequest(context = context, data = coverUrl,
                    sizePx = if (tabletProfile) 512 else 256, allowHardware = false, offlineMode = offlineMode),
                contentDescription = title, contentScale = ContentScale.Crop, modifier = coverModifier
            )
        } else {
            Box(coverModifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AccountCircle, null, Modifier.size(if (tabletProfile) 72.dp else 52.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val identity: @Composable () -> Unit = {
        Text(title, maxLines = if (tabletProfile) 3 else 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    Card(
        modifier = modifier.fillMaxWidth(), shape = neriRoundedShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (tabletProfile) {
            Column(Modifier.padding(16.dp)) {
                cover()
                Spacer(Modifier.height(20.dp))
                identity()
            }
        } else {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                cover()
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { identity() }
            }
        }
    }
}
