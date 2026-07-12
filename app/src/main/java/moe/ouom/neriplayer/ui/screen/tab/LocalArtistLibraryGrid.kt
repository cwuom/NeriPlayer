package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.util.rememberLocalArtistDisplayCoverUrl
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest

@Composable
internal fun LocalArtistLibraryGrid(
    artists: List<LocalArtistSummary>,
    onClick: (LocalArtistSummary) -> Unit,
    offlineMode: Boolean,
    emptyTitleResId: Int = R.string.library_local_artist_empty,
    emptyHintResId: Int = R.string.library_local_artist_hint,
    headerContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = 8.dp + miniPlayerHeight
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize()
    ) {
        if (headerContent != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "local_artist_header") {
                Column(Modifier.fillMaxWidth()) {
                    headerContent()
                }
            }
        }
        if (artists.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LocalArtistEmptyCard(
                    titleResId = emptyTitleResId,
                    hintResId = emptyHintResId
                )
            }
        } else {
            items(
                items = artists,
                key = { artist -> artist.stableKey }
            ) { artist ->
                LocalArtistGridCard(
                    artist = artist,
                    onClick = { onClick(artist) },
                    offlineMode = offlineMode
                )
            }
        }
    }
}

@Composable
private fun LocalArtistEmptyCard(
    titleResId: Int,
    hintResId: Int
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        ListItem(
            headlineContent = { Text(stringResource(titleResId)) },
            supportingContent = {
                Text(
                    text = stringResource(hintResId),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
            }
        )
    }
}

@Composable
internal fun LocalArtistGridCard(
    artist: LocalArtistSummary,
    onClick: () -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val coverUrl = rememberLocalArtistDisplayCoverUrl(artist)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = fastScrollableImageRequest(
                    context = context,
                    data = coverUrl,
                    sizePx = 384,
                    offlineMode = offlineMode
                ),
                contentDescription = artist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = artist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = pluralStringResource(
                    R.plurals.artist_song_count,
                    artist.songs.size,
                    artist.songs.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
