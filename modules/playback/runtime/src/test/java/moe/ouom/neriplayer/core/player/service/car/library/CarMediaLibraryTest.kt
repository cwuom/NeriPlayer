package moe.ouom.neriplayer.core.player.service.car.library

import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMediaLibraryTest {
    @Test
    fun `root provides four browsable categories and configurable labels`() {
        val library = CarMediaLibrary(CarLibrarySnapshot(), CarLibraryLabels(queue = "Queue"))
        val items = library.children(CarMediaIds.ROOT)

        assertEquals(listOf(CarMediaIds.QUEUE, CarMediaIds.PLAYLISTS, CarMediaIds.HISTORY, CarMediaIds.OFFLINE),
            items.map { it.mediaId })
        assertTrue(items.all { it.isBrowsable && !it.isPlayable })
        assertEquals("Queue", items.first().title)
        assertEquals("NeriPlayer", library.getItem(CarMediaIds.ROOT)?.title)
        assertEquals(items.first(), library.getItem(CarMediaIds.QUEUE))
        assertTrue(library.children(CarMediaIds.QUEUE).isEmpty())
        assertNull(library.resolveId(CarMediaIds.QUEUE))
    }

    @Test
    fun `grouped catalogue keeps all four categories reachable with the existing root label`() {
        val library = CarMediaLibrary(
            CarLibrarySnapshot(queue = listOf(song(1))), CarLibraryLabels(root = "Music library"),
            identity = ::identity
        )
        val catalogue = requireNotNull(library.getItem(CarMediaIds.CATALOGUE))

        assertEquals(CarMediaIds.CATALOGUE, catalogue.mediaId)
        assertEquals("Music library", catalogue.title)
        assertTrue(catalogue.isBrowsable)
        assertFalse(catalogue.isPlayable)
        assertEquals(library.children(CarMediaIds.ROOT), library.children(catalogue.mediaId))
        assertEquals(4, library.children(catalogue.mediaId).size)
        assertNull(library.resolveId(catalogue.mediaId))
        val queueItem = library.children(library.children(catalogue.mediaId).first().mediaId).single()
        assertEquals(CarPlaybackSelection(listOf(song(1)), 0), library.resolveId(queueItem.mediaId))
    }

    @Test
    fun `grouped catalogue cannot be forged into a playable song directory`() {
        val library = library(CarLibrarySnapshot(queue = listOf(song(1))))
        val forged = CarMediaIds.song(CarMediaIds.CATALOGUE, 0, identity(song(1)))

        assertNull(library.getItem(forged))
        assertNull(library.resolveId(forged))
        assertTrue(library.children(forged).isEmpty())
    }

    @Test
    fun `queue item publishes displayed metadata without an externally supplied audio URI`() {
        val song = song(1).copy(customName = "Edited title", customArtist = "Edited artist",
            mediaUri = "content://private/audio/1")
        val library = library(CarLibrarySnapshot(queue = listOf(song)))
        val item = library.children(CarMediaIds.QUEUE).single()

        assertEquals("Edited title", item.title)
        assertEquals("Edited artist", item.subtitle)
        assertEquals("Album", item.description)
        assertEquals(song, item.song)
        assertTrue(item.isPlayable)
        assertFalse(item.isBrowsable)
        assertFalse(item.mediaId.contains("content://"))
        assertEquals(item, library.getItem(item.mediaId))
        assertTrue(library.children(item.mediaId).isEmpty())
        assertEquals(listOf(song), library.resolveId(item.mediaId)?.songs)
    }

    @Test
    fun `duplicate songs keep queue position and distinct platform identities`() {
        val first = song(1)
        val otherPlatform = first.copy(channelId = "bilibili", album = "bilibili")
        val songs = listOf(first, first, otherPlatform)
        val library = library(CarLibrarySnapshot(queue = songs))
        val items = library.children(CarMediaIds.QUEUE)

        assertEquals(3, items.map { it.mediaId }.distinct().size)
        items.forEachIndexed { index, item ->
            assertEquals(CarPlaybackSelection(songs, index), library.resolveId(item.mediaId))
        }
        val realIdentityLibrary = CarMediaLibrary(CarLibrarySnapshot(queue = listOf(first, otherPlatform)))
        assertNotEquals(realIdentityLibrary.children(CarMediaIds.QUEUE)[0].mediaId,
            realIdentityLibrary.children(CarMediaIds.QUEUE)[1].mediaId)
    }

    @Test
    fun `same song has different IDs for each owning directory`() {
        val song = song(1)
        val library = library(CarLibrarySnapshot(queue = listOf(song), history = listOf(song),
            offlineSongs = listOf(song), playlists = listOf(playlist(42, song))))
        val ids = listOf(CarMediaIds.QUEUE, CarMediaIds.HISTORY, CarMediaIds.OFFLINE, CarMediaIds.playlist(42))
            .map { library.children(it).single().mediaId }

        assertEquals(4, ids.distinct().size)
        assertEquals(42L, library.resolveId(ids.last())?.localPlaylistId)
        ids.dropLast(1).forEach { assertNull(library.resolveId(it)?.localPlaylistId) }
    }

    @Test
    fun `all local playlists including favorites remain browsable`() {
        val favorites = playlist(-1001, song(1)).copy(name = "我喜欢的音乐")
        val ordinary = playlist(42, song(2))
        val library = library(CarLibrarySnapshot(playlists = listOf(favorites, ordinary)))

        assertEquals(listOf("我喜欢的音乐", "Playlist 42"), library.children(CarMediaIds.PLAYLISTS).map { it.title })
        assertEquals("我喜欢的音乐", library.getItem(CarMediaIds.playlist(-1001))?.title)
        assertEquals(song(1), library.children(CarMediaIds.playlist(-1001)).single().song)
        assertNull(library.getItem(CarMediaIds.playlist(404)))
        assertTrue(library.children(CarMediaIds.playlist(404)).isEmpty())
    }

    @Test
    fun `offline defaults to local files playlist and explicit downloads override it`() {
        val local = song(1).copy(mediaUri = "content://media/audio/1")
        val downloadedRemote = song(2).copy(localFilePath = "/download/two.mp3")
        val snapshot = CarLibrarySnapshot(playlists = listOf(playlist(LocalFilesPlaylist.SYSTEM_ID, local)))

        assertEquals(local, library(snapshot).children(CarMediaIds.OFFLINE).single().song)
        assertEquals(downloadedRemote,
            library(snapshot.copy(offlineSongs = listOf(downloadedRemote))).children(CarMediaIds.OFFLINE).single().song)
        assertTrue(library(snapshot.copy(offlineSongs = emptyList())).children(CarMediaIds.OFFLINE).isEmpty())
        assertTrue(library(CarLibrarySnapshot()).children(CarMediaIds.OFFLINE).isEmpty())
    }

    @Test
    fun `library defensively snapshots queue history and mutable playlist songs`() {
        val queue = mutableListOf(song(1))
        val history = mutableListOf(song(2))
        val playlist = playlist(42, song(3))
        val playlists = mutableListOf(playlist)
        val library = library(CarLibrarySnapshot(queue, playlists, history))
        queue.clear()
        history.clear()
        playlist.songs.clear()
        playlists.clear()

        assertEquals(song(1), library.children(CarMediaIds.QUEUE).single().song)
        assertEquals(song(2), library.children(CarMediaIds.HISTORY).single().song)
        assertEquals(song(3), library.children(CarMediaIds.playlist(42)).single().song)
    }

    @Test
    fun `playback selections do not expose mutable internal playlist storage`() {
        val library = library(CarLibrarySnapshot(playlists = listOf(playlist(42, song(1), song(2)))))
        val id = library.children(CarMediaIds.playlist(42)).first().mediaId
        val selection = requireNotNull(library.resolveId(id))
        (selection.songs as MutableList<SongItem>).clear()

        assertEquals(2, library.children(CarMediaIds.playlist(42)).size)
    }

    @Test
    fun `stale IDs cannot play a different song after reorder removal or source change`() {
        val first = song(1)
        val second = song(2)
        val original = library(CarLibrarySnapshot(queue = listOf(first, second)))
        val id = original.children(CarMediaIds.QUEUE).first().mediaId
        val changedSources = listOf(
            listOf(second, first),
            emptyList(),
            listOf(first.copy(channelId = "bilibili"))
        )

        changedSources.forEach { songs ->
            val changed = library(CarLibrarySnapshot(queue = songs))
            assertNull(changed.resolveId(id))
            assertNull(changed.getItem(id))
        }
        val renamed = first.copy(name = "Updated metadata")
        assertEquals(renamed, library(CarLibrarySnapshot(queue = listOf(renamed))).getItem(id)?.song)
    }

    @Test
    fun `removed owning playlist rejects its old song ID`() {
        val original = library(CarLibrarySnapshot(playlists = listOf(playlist(42, song(1)))))
        val id = original.children(CarMediaIds.playlist(42)).single().mediaId

        assertNull(library(CarLibrarySnapshot()).resolveId(id))
        assertNull(library(CarLibrarySnapshot()).getItem(id))
    }

    @Test
    fun `duplicate playlist IDs use one deterministic snapshot`() {
        val library = library(CarLibrarySnapshot(playlists = listOf(playlist(42, song(1)), playlist(42, song(2)))))

        assertEquals(1, library.children(CarMediaIds.PLAYLISTS).size)
        assertEquals(song(1), library.children(CarMediaIds.playlist(42)).single().song)
    }

    @Test
    fun `search matches title artist album edited metadata and multiple terms case insensitively`() {
        val songs = listOf(song(1).copy(name = "Moonlight", artist = "Alice", album = "Winter"),
            song(2).copy(customName = "Sunrise", customArtist = "BOB", album = "Spring"))
        val library = library(CarLibrarySnapshot(queue = songs))

        listOf("moonLIGHT", " alice ", "Winter", "Moonlight  Alice", "Sunrise", " bob ", "Spring")
            .forEach { query -> assertEquals(1, library.search(query).size) }
        assertEquals(songs.last(), library.search("SUNRISE BOB").single().song)
        assertEquals(songs.first(), library.search("Moonlight Winter").single().song)
        assertTrue(library.search("Moonlight Spring").isEmpty())
        assertNull(library.resolveSearch("unknown"))
    }

    @Test
    fun `search merges sources deduplicates same identity and preserves matching playback queue`() {
        val queueSong = song(1)
        val playlistSong = song(2)
        val historySong = song(3)
        val offlineSong = song(4)
        val library = library(CarLibrarySnapshot(queue = listOf(queueSong, queueSong),
            playlists = listOf(playlist(42, queueSong, playlistSong)), history = listOf(historySong),
            offlineSongs = listOf(offlineSong)))
        val expected = listOf(queueSong, playlistSong, historySong, offlineSong)
        val items = library.search("Song")

        assertEquals(expected, items.map { it.song })
        assertEquals(CarPlaybackSelection(expected, 0), library.resolveSearch("Song"))
        assertEquals(CarPlaybackSelection(expected, 2), library.resolveId(items[2].mediaId))
        assertEquals(items[2], library.getItem(items[2].mediaId))
    }

    @Test
    fun `blank oversized and missing queries return no matches`() {
        val library = library(CarLibrarySnapshot(queue = listOf(song(1))))

        listOf("", " \t\n ", "x".repeat(121)).forEach { query ->
            assertTrue(library.search(query).isEmpty())
            assertNull(library.resolveSearch(query))
        }
    }

    @Test
    fun `untrusted and malformed media IDs cannot enumerate or start arbitrary media`() {
        val library = library(CarLibrarySnapshot(queue = listOf(song(1))))
        val invalid = listOf("", "content://private/audio/1", "https://server/song.mp3", "file:///private/song.mp3",
            "neri-car:v2/queue", "neri-car:v1/unknown", "neri-car:v1/queue/extra",
            "neri-car:v1/playlist/nope", "neri-car:v1/playlist/9999999999999999999999999",
            "neri-car:v1/search/%%%", "neri-car:v1/song/%%%/0/${"0".repeat(64)}",
            "neri-car:v1/page/%%%/0/10", "neri-car:v1/queue" + "x".repeat(1_024))

        invalid.forEach { id ->
            assertTrue(id, library.children(id).isEmpty())
            assertNull(id, library.getItem(id))
            assertNull(id, library.resolveId(id))
        }
    }

    @Test
    fun `well formed but forged song references require correct identity and bounds`() {
        val library = library(CarLibrarySnapshot(queue = listOf(song(1))))
        val invalid = listOf(
            CarMediaIds.song(CarMediaIds.QUEUE, 0, "wrong identity"),
            CarMediaIds.song(CarMediaIds.QUEUE, 1, identity(song(1))),
            CarMediaIds.song(CarMediaIds.QUEUE, Int.MAX_VALUE, identity(song(1))),
            CarMediaIds.song(CarMediaIds.QUEUE, -1, identity(song(1))),
            CarMediaIds.song(CarMediaIds.ROOT, 0, identity(song(1))),
            CarMediaIds.song(CarMediaIds.PLAYLISTS, 0, identity(song(1)))
        )

        invalid.forEach { id ->
            assertNull(library.getItem(id))
            assertNull(library.resolveId(id))
        }
    }

    private fun library(snapshot: CarLibrarySnapshot): CarMediaLibrary = CarMediaLibrary(snapshot, identity = ::identity)

    private fun playlist(id: Long, vararg songs: SongItem) = LocalPlaylist(id, "Playlist $id", songs.toMutableList())

    private fun identity(song: SongItem): String = "${song.channelId}|${song.id}|${song.mediaUri.orEmpty()}"

    private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "Album", 0, 1_000, null,
        channelId = "netease")
}
