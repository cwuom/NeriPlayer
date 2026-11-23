package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.i18n.asString

internal fun LazyGridScope.addInternationalHome(
    env: HomeMajorSectionEnv,
    shelves: List<InternationalHomeShelf>,
    displayed: List<InternationalHomeShelf>,
    kind: InternationalHomeKind?,
    onSelectKind: (InternationalHomeKind?) -> Unit,
    onRetry: () -> Unit,
    onRetryContinuation: () -> Unit,
    onEnableYouTube: () -> Unit
) {
    val ui = env.ui
    val sections = ui.internationalHomeLayout.visibleSections
    val feed = ui.ytMusicHomeShelves
    val accessible = env.showOnlineFeeds && ui.youtubeHomeEnabled
    val firstOnlineSection = sections.firstOrNull { it != InternationalHomeSectionIds.CONTINUE }
    for (section in sections) {
        if (section == InternationalHomeSectionIds.CONTINUE) {
            addHomeContinueBlock(env)
            continue
        }
        if (!accessible || shelves.isEmpty()) {
            if (section != firstOnlineSection) continue
            fullRow(env.registerKey("international:status")) {
                when {
                    !env.showOnlineFeeds -> Text(stringResource(R.string.international_home_offline))
                    !ui.youtubeHomeEnabled -> HomeFeedMessage(stringResource(R.string.international_home_disabled),
                        R.string.international_home_open_general, onEnableYouTube)
                    feed.loading || ui.loadingMoreYouTubeHome -> SectionLoadingState(env.loadingText)
                    ui.youTubeHomeLoadMoreError != null -> HomeFeedMessage(ui.youTubeHomeLoadMoreError.asString(),
                        CommonR.string.action_retry, onRetryContinuation)
                    feed.error != null -> HomeFeedMessage(feed.error.asString(), CommonR.string.action_retry, onRetry)
                    else -> Text(stringResource(R.string.youtube_home_no_items))
                }
            }
            continue
        }
        if (section == firstOnlineSection && feed.error != null) fullRow(env.registerKey("international:refresh-error")) {
            HomeFeedMessage(feed.error.asString(), CommonR.string.action_retry, onRetry)
        }
        when (section) {
            InternationalHomeSectionIds.SPOTLIGHT -> {
                val shelf = shelves.first()
                val featured = shelf.entries.first()
                fullRow(env.registerKey("international:spotlight")) {
                    InternationalHomeSpotlight(featured) {
                        when (featured) {
                            is InternationalHomeEntry.Track -> env.onSongClick(shelf.songs, featured.queueIndex)
                            is InternationalHomeEntry.Collection -> env.onYouTubeMusicPlaylistClick(featured.playlist)
                            is InternationalHomeEntry.Artist -> env.onYouTubeCreatorClick(featured.creator)
                        }
                    }
                }
            }
            InternationalHomeSectionIds.FEED -> {
                fullRow(env.registerKey("international:filters")) {
                    InternationalHomeFilters(kind, onSelectKind)
                }
                displayed.forEach { shelf ->
                    addInternationalHomeShelf(shelf, env.registerKey, env.onSongClick, env.onYouTubeMusicPlaylistClick,
                        env.onYouTubeCreatorClick, env.favoriteKeys, env.favoriteSongLookup, env.downloadPresenceVersion,
                        env.onFavoriteToggle, env.onShowSnackbar, env.offlineMode)
                }
                if (displayed.isEmpty() && feed.error == null && ui.youTubeHomeLoadMoreError == null) {
                    fullRow(env.registerKey("international:category-status")) {
                        if (feed.loading || ui.loadingMoreYouTubeHome) {
                            SectionLoadingState(env.loadingText)
                        } else {
                            Text(stringResource(R.string.international_home_no_category), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (accessible && firstOnlineSection != null && shelves.isNotEmpty() && ui.youTubeHomeLoadMoreError != null) {
        fullRow(env.registerKey("international:continuation-error")) {
            HomeFeedMessage(ui.youTubeHomeLoadMoreError.asString(), CommonR.string.action_retry, onRetryContinuation)
        }
    }
    if (!env.showContinue && firstOnlineSection == null) fullRow(env.registerKey("international:no-history")) {
        Text(stringResource(R.string.international_home_no_history), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyGridScope.fullRow(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun HomeFeedMessage(message: String, @StringRes action: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        HapticTextButton(onClick = onClick) { Text(stringResource(action)) }
    }
}

@Composable
private fun InternationalHomeFilters(selected: InternationalHomeKind?, onSelect: (InternationalHomeKind?) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("internationalHomeFeed"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(CommonR.string.international_home_feed), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.international_home_all)) })
            InternationalHomeKind.entries.forEach { kind ->
                FilterChip(selected == kind, onClick = { onSelect(kind) }, label = { Text(stringResource(kind.labelRes)) })
            }
        }
    }
}

@get:StringRes
internal val InternationalHomeKind.labelRes: Int get() = when (this) {
    InternationalHomeKind.TRACK -> CommonR.string.artist_tab_songs
    InternationalHomeKind.PLAYLIST -> CommonR.string.library_netease_tab_playlists
    InternationalHomeKind.ALBUM -> CommonR.string.library_netease_tab_albums
    InternationalHomeKind.ARTIST -> CommonR.string.explore_search_type_artist
}
