package moe.ouom.neriplayer.ui.screen.tab.home

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.core.download.policy.toPlaybackSongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.ui.util.rememberPlaylistDisplayCoverUrl
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import kotlin.math.ceil

private const val HomeContinueHorizontalPaddingDp = 8f
private const val HomeContinueCardSpacingDp = 12f
private const val HomeContinueCardMaxWidthDp = 140f
internal const val HomeContinueEntryLimit = 12
internal const val HomeLocalPlaylistCoverCandidateLimit = 24
private const val HomeDownloadedCoverSourceLimit = 64
private const val HomeContinueThreeSlotWidthDp = 300f
private const val HomeContinueTabletWidthDp = 600f

private class ContinuePageModel(
    val items: List<UsageEntry>,
    val page: Int,
    val cardsPerPage: Int,
    val cardWidth: Dp,
    val localPlaylistLookup: Map<Long, LocalPlaylist>,
    val localFilesCoverCandidates: List<SongItem>,
    val offlineMode: Boolean,
    val onClick: (UsageEntry) -> Unit
)

private class ContinueCardModel(
    val entry: UsageEntry,
    val localPlaylist: LocalPlaylist?,
    val localFilesCoverCandidates: List<SongItem>,
    val offlineMode: Boolean,
    val modifier: Modifier,
    onEntryClick: (UsageEntry) -> Unit
) {
    val onClick: () -> Unit = { onEntryClick(entry) }
    val onRemove: () -> Unit = { removeHomeContinueEntry(entry) }
}

internal fun shouldShowHomeContinueSection(
    showContinueCard: Boolean,
    usageLoaded: Boolean,
    hasUsage: Boolean
): Boolean = showContinueCard && (!usageLoaded || hasUsage)

internal fun resolveHomeContinuePagerPage(savedPage: Int, pageCount: Int): Int {
    return savedPage.coerceIn(0, pageCount.coerceAtLeast(1) - 1)
}

internal fun shouldResolveHomeContinueLocalCoverFallback(
    persistedCoverUrl: String?,
    localPlaylist: LocalPlaylist?
): Boolean = localPlaylist != null && shouldValidateHomeContinueCoverReference(persistedCoverUrl)

internal fun shouldValidateHomeContinueCoverReference(
    persistedCoverUrl: String?
): Boolean {
    val normalized = persistedCoverUrl?.trim().orEmpty()
    return normalized.isEmpty() ||
        (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true))
}

internal fun homeLocalFilesCoverCandidates(
    downloadedSongs: List<DownloadedSong>
): List<SongItem> {
    return downloadedSongs.asSequence()
        .take(HomeDownloadedCoverSourceLimit)
        .filter(DownloadedSong::hasHomeCover)
        .take(HomeLocalPlaylistCoverCandidateLimit)
        .map(DownloadedSong::toPlaybackSongItem)
        .toList()
}

private fun DownloadedSong.hasHomeCover(): Boolean {
    if (customCoverUrl.hasHomeCoverValue()) return true
    if (coverPath.hasHomeCoverValue()) return true
    return coverUrl.hasHomeCoverValue()
}

private fun String?.hasHomeCoverValue(): Boolean = this?.trim()?.isNotEmpty() == true

@Composable
internal fun ContinueSection(
    items: List<UsageEntry>,
    localPlaylistLookup: Map<Long, LocalPlaylist>,
    localFilesCoverCandidates: List<SongItem>,
    onClick: (UsageEntry) -> Unit,
    savedPage: Int,
    onPageChanged: (Int) -> Unit,
    offlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardsPerPage = remember(maxWidth) {
            resolveHomeContinueCardsPerPage(maxWidth.value)
        }
        val cardWidth = remember(maxWidth, cardsPerPage) {
            resolveHomeContinueCardWidthDp(
                containerWidthDp = maxWidth.value,
                cardsPerPage = cardsPerPage
            ).dp
        }
        val pageCount = remember(items.size, cardsPerPage) {
            ceil(items.size / cardsPerPage.toFloat()).toInt().coerceAtLeast(1)
        }
        val initialPage = remember(savedPage, pageCount) {
            resolveHomeContinuePagerPage(savedPage, pageCount)
        }
        val pagerState = rememberPagerState(
            initialPage = initialPage,
            pageCount = { pageCount }
        )
        LaunchedEffect(pagerState.currentPage) {
            onPageChanged(pagerState.currentPage)
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
        ) { page ->
            ContinuePage(
                model = ContinuePageModel(
                    items = items,
                    page = page,
                    cardsPerPage = cardsPerPage,
                    cardWidth = cardWidth,
                    localPlaylistLookup = localPlaylistLookup,
                    localFilesCoverCandidates = localFilesCoverCandidates,
                    offlineMode = offlineMode,
                    onClick = onClick
                ),
            )
        }
    }
}

@Composable
private fun ContinuePage(model: ContinuePageModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HomeContinueHorizontalPaddingDp.dp),
        horizontalArrangement = Arrangement.spacedBy(HomeContinueCardSpacingDp.dp)
    ) {
        repeat(model.cardsPerPage) { slot ->
            ContinuePageSlot(model, slot)
        }
    }
}

@Composable
private fun ContinuePageSlot(model: ContinuePageModel, slot: Int) {
    val entry = model.items.getOrNull(model.page * model.cardsPerPage + slot)
    if (entry == null) {
        Spacer(Modifier.width(model.cardWidth))
        return
    }
    ContinueSlot(
        ContinueCardModel(
            entry = entry,
            localPlaylist = resolveHomeContinuePlaylist(entry, model.localPlaylistLookup),
            localFilesCoverCandidates = model.localFilesCoverCandidates,
            offlineMode = model.offlineMode,
            modifier = Modifier.width(model.cardWidth),
            onEntryClick = model.onClick
        )
    )
}

@Composable
private fun ContinueSlot(model: ContinueCardModel) {
    ContinueCard(
        entry = model.entry,
        localPlaylist = model.localPlaylist,
        localFilesCoverCandidates = model.localFilesCoverCandidates,
        onClick = model.onClick,
        onRemove = model.onRemove,
        offlineMode = model.offlineMode,
        modifier = model.modifier
    )
}

private fun resolveHomeContinuePlaylist(
    entry: UsageEntry,
    lookup: Map<Long, LocalPlaylist>
): LocalPlaylist? {
    return if (entry.source == PlaylistUsageRepository.SOURCE_LOCAL) lookup[entry.id] else null
}

private fun removeHomeContinueEntry(entry: UsageEntry) {
    AppContainer.launchBackgroundIo {
        AppContainer.playlistUsageRepo.removeEntry(entry.id, entry.source, entry.subtype)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    entry: UsageEntry,
    localPlaylist: LocalPlaylist?,
    localFilesCoverCandidates: List<SongItem>,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    offlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    val displayName = rememberContinueDisplayName(entry)
    val coverUrl = rememberContinueCoverUrl(entry, localPlaylist, localFilesCoverCandidates)
    ContinueCardSurface(entry, displayName, coverUrl, onClick, onRemove, offlineMode, modifier)
}

@Composable
private fun rememberContinueDisplayName(entry: UsageEntry): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(entry.id, entry.name, entry.source, configuration) {
        resolveContinueDisplayName(entry, context)
    }
}

private fun resolveContinueDisplayName(entry: UsageEntry, context: Context): String {
    return resolvedSystemPlaylistName(entry, context) ?: entry.name
}

private fun resolvedSystemPlaylistName(entry: UsageEntry, context: Context): String? {
    return SystemLocalPlaylists.resolve(entry.id, entry.name, context)?.currentName
}

@Composable
private fun rememberContinueCoverUrl(
    entry: UsageEntry,
    localPlaylist: LocalPlaylist?,
    localFilesCoverCandidates: List<SongItem>
): String? {
    val resolved = if (localPlaylist == null) {
        null
    } else {
        rememberContinueLocalCover(entry, localPlaylist, localFilesCoverCandidates)
    }
    return selectContinueCoverUrl(resolved, entry.picUrl)
}

internal fun selectContinueCoverUrl(resolved: String?, persisted: String?): String? {
    val usableResolved = resolved?.takeIf(String::isNotBlank)
    return usableResolved ?: persisted?.takeIf(String::isNotBlank)
}

@Composable
private fun rememberContinueLocalCover(
    entry: UsageEntry,
    localPlaylist: LocalPlaylist,
    localFilesCoverCandidates: List<SongItem>
): String? {
    if (!shouldValidateHomeContinueCoverReference(entry.picUrl)) return null
    return rememberPlaylistDisplayCoverUrl(
        playlist = localPlaylist,
        additionalCoverCandidates = coverCandidatesForPlaylist(localPlaylist, localFilesCoverCandidates),
        preferredCoverUrl = entry.picUrl
    )
}

private fun coverCandidatesForPlaylist(
    playlist: LocalPlaylist,
    localFilesCoverCandidates: List<SongItem>
): List<SongItem> {
    return if (playlist.id == LocalFilesPlaylist.SYSTEM_ID) localFilesCoverCandidates else emptyList()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCardSurface(
    entry: UsageEntry,
    displayName: String,
    coverUrl: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    offlineMode: Boolean,
    modifier: Modifier
) {
    val view = LocalView.current
    var showMenu by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showMenu = true
                }
            )
    ) {
        ContinueCover(displayName, coverUrl, offlineMode)
        ContinueCardDetails(displayName, entry.trackCount)
        ContinueRemoveMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onRemove = {
                showMenu = false
                onRemove()
            }
        )
    }
}

@Composable
private fun ContinueCover(displayName: String, coverUrl: String?, offlineMode: Boolean) {
    AsyncImage(
        model = fastScrollableImageRequest(
            context = LocalContext.current,
            data = coverUrl,
            sizePx = 384,
            offlineMode = offlineMode
        ),
        contentDescription = displayName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun ContinueCardDetails(displayName: String, trackCount: Int) {
    Column(modifier = Modifier.padding(6.dp)) {
        Text(
            text = displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = pluralStringResource(CoreCommonR.plurals.home_song_count_format, trackCount, trackCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun ContinueRemoveMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(CoreCommonR.string.continue_playing_remove)) },
            leadingIcon = {
                Icon(imageVector = Icons.Outlined.DeleteForever, contentDescription = null)
            },
            onClick = onRemove
        )
    }
}

internal fun resolveHomeContinueCardsPerPage(containerWidthDp: Float): Int {
    val preferredMinimumSlots = if (containerWidthDp >= HomeContinueThreeSlotWidthDp) 3 else 2
    val availableWidth = (containerWidthDp - HomeContinueHorizontalPaddingDp * 2f)
        .coerceAtLeast(0f)
    val slotsNeededToAvoidSlack = ceil(
        (availableWidth + HomeContinueCardSpacingDp) /
            (HomeContinueCardMaxWidthDp + HomeContinueCardSpacingDp)
    ).toInt()
    val tabletMinimumSlots = if (containerWidthDp >= HomeContinueTabletWidthDp) 4 else 0
    return maxOf(preferredMinimumSlots, tabletMinimumSlots, slotsNeededToAvoidSlack, 1)
}

internal fun resolveHomeContinueCardWidthDp(
    containerWidthDp: Float,
    cardsPerPage: Int
): Float {
    val slots = cardsPerPage.coerceAtLeast(1)
    val availableWidth = containerWidthDp -
        HomeContinueHorizontalPaddingDp * 2f -
        HomeContinueCardSpacingDp * (slots - 1)
    return (availableWidth / slots)
        .coerceAtLeast(0f)
}
