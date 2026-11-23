package moe.ouom.neriplayer.ui.screen.tab.home

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist

internal enum class InternationalHomeKind { TRACK, PLAYLIST, ALBUM, ARTIST }

internal sealed class InternationalHomeEntry(val item: YouTubeMusicHomeItem, val kind: InternationalHomeKind) {
    class Track(item: YouTubeMusicHomeItem, val song: SongItem, val queueIndex: Int) :
        InternationalHomeEntry(item, InternationalHomeKind.TRACK)

    class Collection(item: YouTubeMusicHomeItem, kind: InternationalHomeKind, val playlist: YouTubeMusicPlaylist) :
        InternationalHomeEntry(item, kind)

    class Artist(item: YouTubeMusicHomeItem, val creator: YouTubeMusicCreatorSummary) :
        InternationalHomeEntry(item, InternationalHomeKind.ARTIST)
}

internal data class InternationalHomeShelf(
    val key: String,
    val title: String,
    val entries: List<InternationalHomeEntry>
) {
    val songs: List<SongItem> = entries.mapNotNull { (it as? InternationalHomeEntry.Track)?.song }
}

internal fun YouTubeMusicHomeItem.internationalHomeKind(): InternationalHomeKind? = when {
    browseId.isNotBlank() && pageType.endsWith("_ALBUM") -> InternationalHomeKind.ALBUM
    browseId.isNotBlank() && pageType.endsWith("_PLAYLIST") -> InternationalHomeKind.PLAYLIST
    browseId.isNotBlank() && (pageType.endsWith("_ARTIST") || pageType.endsWith("_USER_CHANNEL")) ->
        InternationalHomeKind.ARTIST
    pageType.isBlank() && browseId.startsWith("MPRE") -> InternationalHomeKind.ALBUM
    pageType.isBlank() && browseId.startsWith("UC") -> InternationalHomeKind.ARTIST
    videoId.isNotBlank() -> InternationalHomeKind.TRACK
    pageType.isBlank() && browseId.startsWith("VL") -> InternationalHomeKind.PLAYLIST
    else -> null
}

internal fun buildInternationalHomeShelves(shelves: List<YouTubeMusicHomeShelf>): List<InternationalHomeShelf> {
    val titleCounts = mutableMapOf<String, Int>()
    return shelves.mapNotNull { shelf ->
        var songIndex = 0
        val entries = shelf.items.mapNotNull { item ->
            when (val kind = item.internationalHomeKind()) {
                InternationalHomeKind.TRACK -> item.toPlayableSongItem(shelf.title)?.let {
                    InternationalHomeEntry.Track(item, it, songIndex++)
                }
                InternationalHomeKind.ALBUM, InternationalHomeKind.PLAYLIST -> item.toPlaylist()?.let {
                    InternationalHomeEntry.Collection(item, kind, it)
                }
                InternationalHomeKind.ARTIST -> item.toCreator()?.let { InternationalHomeEntry.Artist(item, it) }
                null -> null
            }
        }
        if (entries.isEmpty()) return@mapNotNull null
        val key = shelf.continuationKey?.let { "youtube:continued:$it" } ?: run {
            val occurrence = titleCounts.getOrDefault(shelf.title, 0)
            titleCounts[shelf.title] = occurrence + 1
            "youtube:shelf:${shelf.title}:$occurrence"
        }
        InternationalHomeShelf(key, shelf.title, entries)
    }
}

internal fun List<InternationalHomeShelf>.filterHomeShelves(kind: InternationalHomeKind?): List<InternationalHomeShelf> {
    if (kind == null) return this
    return mapNotNull { shelf ->
        val matches = shelf.entries.filter { it.kind == kind }
        when (matches.size) {
            0 -> null
            shelf.entries.size -> shelf
            else -> shelf.copy(entries = matches)
        }
    }
}
