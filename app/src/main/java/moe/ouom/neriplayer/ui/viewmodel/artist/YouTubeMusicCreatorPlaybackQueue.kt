package moe.ouom.neriplayer.ui.viewmodel.artist

import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItemsPage
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.SongItem

internal const val YOUTUBE_MUSIC_CREATOR_PLAYBACK_PAGE_LIMIT = 80

internal data class YouTubeMusicCreatorPlaybackQueue(
    val songs: List<SongItem>,
    val startIndex: Int
)

internal fun youtubeMusicCreatorSectionKey(section: YouTubeMusicCreatorSection): String {
    val endpoint = section.moreEndpoint
    return listOf(
        endpoint?.browseId.orEmpty(),
        endpoint?.params.orEmpty(),
        section.title.trim()
    ).joinToString("|")
}

internal fun youtubeMusicCreatorItemKey(item: YouTubeMusicCreatorItem): String {
    return item.videoId.trim().takeIf(String::isNotBlank)
        ?: item.browseId.trim().takeIf(String::isNotBlank)
        ?: item.playlistId.trim().takeIf(String::isNotBlank)
        ?: listOf(item.type.name, item.title.trim(), item.subtitle.trim()).joinToString("|")
}

internal fun mergeYouTubeMusicCreatorItems(
    items: Iterable<YouTubeMusicCreatorItem>
): List<YouTubeMusicCreatorItem> {
    val seenKeys = linkedSetOf<String>()
    return items.filter { seenKeys.add(youtubeMusicCreatorItemKey(it)) }
}

internal fun buildYouTubeMusicCreatorPlaybackQueue(
    items: Iterable<YouTubeMusicCreatorItem>,
    selectedItem: YouTubeMusicCreatorItem
): YouTubeMusicCreatorPlaybackQueue? {
    val playableItems = mergeYouTubeMusicCreatorItems(items)
        .mapNotNull { item -> item.toCreatorSongItem()?.let { item to it } }
    if (playableItems.isEmpty()) {
        return null
    }
    val selectedItemKey = youtubeMusicCreatorItemKey(selectedItem)
    val startIndex = playableItems.indexOfFirst { (item, _) ->
        youtubeMusicCreatorItemKey(item) == selectedItemKey
    }.coerceAtLeast(0)
    return YouTubeMusicCreatorPlaybackQueue(
        songs = playableItems.map { (_, song) -> song },
        startIndex = startIndex
    )
}

internal suspend fun loadYouTubeMusicCreatorPlaybackQueue(
    section: YouTubeMusicCreatorSection,
    selectedItem: YouTubeMusicCreatorItem,
    fetchFirstPage: suspend (
        YouTubeMusicCreatorBrowseEndpoint,
        String
    ) -> YouTubeMusicCreatorItemsPage,
    fetchContinuation: suspend (String) -> YouTubeMusicCreatorItemsPage,
    pageLimit: Int = YOUTUBE_MUSIC_CREATOR_PLAYBACK_PAGE_LIMIT
): YouTubeMusicCreatorPlaybackQueue? {
    val endpoint = section.moreEndpoint ?: return buildYouTubeMusicCreatorPlaybackQueue(
        items = section.items,
        selectedItem = selectedItem
    )
    val items = section.items.toMutableList()
    val firstPage = fetchFirstPage(endpoint, section.title)
    items += firstPage.items

    val seenContinuations = mutableSetOf<String>()
    var continuation = firstPage.continuation?.trim()?.takeIf(String::isNotBlank)
    var loadedPageCount = 1
    val maxPageCount = pageLimit.coerceAtLeast(1)
    while (continuation != null && loadedPageCount < maxPageCount) {
        if (!seenContinuations.add(continuation)) {
            break
        }
        val page = fetchContinuation(continuation)
        items += page.items
        loadedPageCount++
        continuation = page.continuation?.trim()?.takeIf(String::isNotBlank)
    }
    return buildYouTubeMusicCreatorPlaybackQueue(items, selectedItem)
}
