package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaArtistInfo
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.screen.artist.containsCreator
import moe.ouom.neriplayer.ui.screen.artist.toggleCreatorFollow
import java.io.IOException

/**
 * 模块五 艺人关注按钮。
 *
 * 仅在网易云登录态为 [SavedCookieAuthState.Valid] 时渲染：
 * 未登录时关注无法与账号同步，显示按钮会误导用户。
 * 关注动作复用项目既有的本地收藏逻辑（与艺人详情页同一套 [FavoritePlaylistRepository]）。
 */
@Composable
internal fun SongEncyclopediaArtistFollowButton(
    artist: EncyclopediaArtistInfo,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authState by AppContainer.neteaseCookieRepo.authHealthFlow.collectAsStateWithLifecycle()
    if (authState.state != SavedCookieAuthState.Valid) return

    val repository = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by repository.favorites.collectAsStateWithLifecycle()
    val favorite = remember(artist) {
        FavoritePlaylist(
            id = artist.artistId,
            name = artist.name,
            coverUrl = artist.picUrl,
            trackCount = artist.musicSize ?: 0,
            source = FAVORITE_SOURCE_NETEASE_ARTIST,
            subtitle = null,
            songs = emptyList()
        )
    }
    val followed = favorites.containsCreator(favorite)

    val scope = rememberCoroutineScope()
    var updating by remember(artist.artistId) { mutableStateOf(false) }
    var error by remember(artist.artistId) { mutableStateOf<String?>(null) }

    Column(modifier = modifier) {
        HapticButton(
            onClick = {
                if (updating) return@HapticButton
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
            },
            enabled = !updating,
            modifier = Modifier.heightIn(min = 40.dp),
            shape = neriRoundedShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (followed) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (followed) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                }
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (updating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = if (followed) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    if (followed) CoreCommonR.string.artist_followed else CoreCommonR.string.artist_follow
                ),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
        error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(CoreCommonR.string.artist_follow_failed, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
