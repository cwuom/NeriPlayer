package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.haptic.HapticButton
import java.io.IOException

internal suspend fun toggleCreatorFollow(
    repository: FavoritePlaylistRepository,
    favorite: FavoritePlaylist
) {
    if (!repository.awaitInitialized()) {
        throw IOException("Local favorites could not be loaded")
    }
    val followed = repository.isFavorite(favorite.id, favorite.source)
    if (followed) {
        repository.removeFavorite(favorite.id, favorite.source)
    } else {
        repository.addFavorite(
            id = favorite.id,
            name = favorite.name,
            coverUrl = favorite.coverUrl,
            trackCount = favorite.trackCount,
            source = favorite.source,
            browseId = favorite.browseId,
            playlistId = favorite.playlistId,
            subtitle = favorite.subtitle,
            songs = favorite.songs
        )
    }
    if (!repository.awaitInitialized() ||
        repository.isFavorite(favorite.id, favorite.source) == followed
    ) {
        throw IOException("Local follow could not be saved")
    }
}

@Composable
internal fun CreatorFollowButton(
    favorite: FavoritePlaylist,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember(context) {
        FavoritePlaylistRepository.getInstance(context)
    }
    val favorites by repository.favorites.collectAsStateWithLifecycle()
    val followed = favorites.any { it.id == favorite.id && it.source == favorite.source }
    key(favorite.source, favorite.id) {
        val scope = rememberCoroutineScope()
        var updating by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val buttonContentColor = if (followed) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        }
        Column(modifier = modifier) {
            HapticButton(
                onClick = {
                    if (!updating) {
                        updating = true
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    toggleCreatorFollow(repository, favorite)
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                error = failure.message ?: failure.javaClass.simpleName
                            } finally {
                                updating = false
                            }
                        }
                    }
                },
                enabled = !updating,
                modifier = Modifier.widthIn(min = 132.dp).heightIn(min = 48.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (followed) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = buttonContentColor
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
            ) {
                if (updating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (followed) {
                            Icons.Outlined.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (followed) CoreCommonR.string.artist_followed else CoreCommonR.string.artist_follow
                    ),
                    maxLines = 1
                )
            }
            error?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(CoreCommonR.string.artist_follow_failed, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
