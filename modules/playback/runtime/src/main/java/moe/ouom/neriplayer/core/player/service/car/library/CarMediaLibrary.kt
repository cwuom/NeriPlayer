package moe.ouom.neriplayer.core.player.service.car.library

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem

class CarMediaLibrary(
    snapshot: CarLibrarySnapshot,
    private val labels: CarLibraryLabels = CarLibraryLabels(),
    private val identity: (SongItem) -> String = { it.stableKey() }
) {
    private val queue = snapshot.queue.toList()
    private val history = snapshot.history.toList()
    private val playlists = snapshot.playlists.distinctBy { it.id }.map { playlist ->
        playlist.copy(songs = playlist.songs.toMutableList())
    }
    private val offline = (snapshot.offlineSongs ?: LocalFilesPlaylist.firstOrNull(playlists)?.songs).orEmpty().toList()
    private val searchSongs by lazy {
        (queue.asSequence() + playlists.asSequence().flatMap { it.songs.asSequence() } +
            history.asSequence() + offline.asSequence()).distinctBy(identity).toList()
    }

    fun children(parentId: String): List<CarLibraryItem> = when (val route = CarMediaIds.parse(parentId)) {
        is CarMediaRoute.Directory -> directoryChildren(route, 0, directorySize(route))
        is CarMediaRoute.Page -> pageChildren(route)
        else -> emptyList()
    }

    fun getItem(mediaId: String): CarLibraryItem? = when (val route = CarMediaIds.parse(mediaId)) {
        is CarMediaRoute.Directory -> directoryItem(route)
        is CarMediaRoute.Page -> pageItem(route)
        is CarMediaRoute.Song -> resolveSong(route)?.let { songItem(route.directory, route.index, it) }
        null -> null
    }

    fun search(query: String): List<CarLibraryItem> {
        val normalized = CarLibrarySearch.normalize(query) ?: return emptyList()
        return children(CarMediaIds.search(normalized))
    }

    fun resolveSearch(query: String): CarPlaybackSelection? {
        val normalized = CarLibrarySearch.normalize(query) ?: return null
        val songs = matchingSongs(normalized).takeIf { it.isNotEmpty() } ?: return null
        return CarPlaybackSelection(songs, 0)
    }

    fun resolveId(mediaId: String): CarPlaybackSelection? {
        val route = CarMediaIds.parse(mediaId) as? CarMediaRoute.Song ?: return null
        resolveSong(route) ?: return null
        return CarPlaybackSelection(
            songs = directorySongs(route.directory).toList(),
            startIndex = route.index,
            localPlaylistId = route.directory.playlistId
        )
    }

    private fun pageChildren(route: CarMediaRoute.Page): List<CarLibraryItem> {
        if (!CarLibraryPaging.isValidRange(route.start, route.end, directorySize(route.directory))) return emptyList()
        return directoryChildren(route.directory, route.start, route.end)
    }

    private fun pageItem(route: CarMediaRoute.Page): CarLibraryItem? {
        if (!CarLibraryPaging.isValidRange(route.start, route.end, directorySize(route.directory))) return null
        return CarLibraryPaging.item(route.directory.mediaId, route.start, route.end)
    }

    private fun directoryChildren(directory: CarMediaRoute.Directory, start: Int, end: Int): List<CarLibraryItem> {
        if (end - start > CarLibraryPaging.PAGE_SIZE) return CarLibraryPaging.pages(directory.mediaId, start, end)
        return when (directory.kind) {
            CarDirectoryKind.ROOT, CarDirectoryKind.CATALOGUE -> rootItems().subList(start, end)
            CarDirectoryKind.PLAYLISTS -> playlists.subList(start, end).map { playlist ->
                CarLibraryItem(CarMediaIds.playlist(playlist.id), playlist.name)
            }
            else -> directorySongs(directory).subList(start, end).mapIndexed { index, song ->
                songItem(directory, start + index, song)
            }
        }
    }

    private fun directorySize(directory: CarMediaRoute.Directory): Int = when (directory.kind) {
        CarDirectoryKind.ROOT, CarDirectoryKind.CATALOGUE -> rootItems().size
        CarDirectoryKind.PLAYLISTS -> playlists.size
        else -> directorySongs(directory).size
    }

    private fun directorySongs(directory: CarMediaRoute.Directory): List<SongItem> = when (directory.kind) {
        CarDirectoryKind.QUEUE -> queue
        CarDirectoryKind.HISTORY -> history
        CarDirectoryKind.OFFLINE -> offline
        CarDirectoryKind.PLAYLIST -> playlists.firstOrNull { it.id == directory.playlistId }?.songs.orEmpty()
        CarDirectoryKind.SEARCH -> directory.query?.let(::matchingSongs).orEmpty()
        else -> emptyList()
    }

    private fun matchingSongs(query: String): List<SongItem> = CarLibrarySearch.matchingSongs(searchSongs, query)

    private fun resolveSong(route: CarMediaRoute.Song): SongItem? {
        val song = directorySongs(route.directory).getOrNull(route.index) ?: return null
        return song.takeIf { CarMediaIds.identityDigest(identity(it)) == route.digest }
    }

    private fun songItem(directory: CarMediaRoute.Directory, index: Int, song: SongItem): CarLibraryItem =
        CarLibraryItem(
            mediaId = CarMediaIds.song(directory.mediaId, index, identity(song)),
            title = song.displayName(),
            subtitle = song.displayArtist(),
            description = song.album,
            song = song
        )

    private fun directoryItem(directory: CarMediaRoute.Directory): CarLibraryItem? = when (directory.kind) {
        CarDirectoryKind.ROOT, CarDirectoryKind.CATALOGUE -> CarLibraryItem(directory.mediaId, labels.root)
        CarDirectoryKind.PLAYLIST -> playlists.firstOrNull { it.id == directory.playlistId }?.let {
            CarLibraryItem(directory.mediaId, it.name)
        }
        CarDirectoryKind.SEARCH -> CarLibraryItem(directory.mediaId, directory.query.orEmpty())
        else -> rootItems().firstOrNull { it.mediaId == directory.mediaId }
    }

    private fun rootItems(): List<CarLibraryItem> = listOf(
        CarLibraryItem(CarMediaIds.QUEUE, labels.queue),
        CarLibraryItem(CarMediaIds.PLAYLISTS, labels.playlists),
        CarLibraryItem(CarMediaIds.HISTORY, labels.history),
        CarLibraryItem(CarMediaIds.OFFLINE, labels.offline)
    )
}
