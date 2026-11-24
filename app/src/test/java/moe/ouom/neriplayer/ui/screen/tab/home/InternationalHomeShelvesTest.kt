package moe.ouom.neriplayer.ui.screen.tab.home

import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternationalHomeShelvesTest {
    @Test
    fun playbackPrefersMetadataFromArtistAndAlbumLinks() {
        val item = YouTubeMusicHomeItem(
            title = "曲", subtitle = "楽曲 • 歌手 • アルバム", coverUrl = "", videoId = "track",
            artist = "歌手", album = "アルバム"
        )
        val song = requireNotNull(item.toPlayableSongItem("聴いてみよう"))
        assertEquals("歌手", song.artist)
        assertEquals("アルバム", song.album)
        assertEquals("track", song.audioId)
    }

    @Test
    fun mixedShelvesPreserveLocalizedTitlesEntryOrderAndPlaybackQueue() {
        val song = YouTubeMusicHomeItem("曲", "", "", videoId = "track-1")
        val album = YouTubeMusicHomeItem("アルバム", "", "", browseId = "MPREalbum")
        val creator = YouTubeMusicHomeItem("作曲家", "", "", browseId = "UCartist")
        val playlist = YouTubeMusicHomeItem("リスト", "", "", browseId = "VLfeatured")
        val next = song.copy(videoId = "track-2")
        val mixed = listOf(album, song, creator, playlist, next)
        val result = buildInternationalHomeShelves(
            listOf(
                YouTubeMusicHomeShelf("もう一度聴く", mixed),
                YouTubeMusicHomeShelf("Découvertes", listOf(creator, song))
            )
        )
        assertEquals(listOf("もう一度聴く", "Découvertes"), result.map { it.title })
        val shelf = result.first()
        assertEquals(mixed, shelf.entries.map { it.item })
        assertEquals(listOf("track-1", "track-2"), shelf.songs.map { it.audioId })
        val track = shelf.entries.last() as InternationalHomeEntry.Track
        assertEquals(next.videoId, shelf.songs[track.queueIndex].audioId)
        val collections = shelf.entries.filterIsInstance<InternationalHomeEntry.Collection>()
        assertEquals(listOf(InternationalHomeKind.ALBUM, InternationalHomeKind.PLAYLIST), collections.map { it.kind })
        assertEquals(listOf("MPREalbum", "featured"), collections.map { it.playlist.playlistId })
        assertEquals("UCartist", shelf.entries.filterIsInstance<InternationalHomeEntry.Artist>().single().creator.browseId)
        assertEquals(listOf(creator, song), result.last().entries.map { it.item })
    }

    @Test
    fun filtersKeepOriginalShelfKeysAndDropOnlyEmptySections() {
        val song = YouTubeMusicHomeItem("Song", "", "", videoId = "v")
        val playlist = YouTubeMusicHomeItem("Mix", "", "", browseId = "VLlist")
        val album = YouTubeMusicHomeItem("LP", "", "", browseId = "opaque", pageType = "MUSIC_PAGE_TYPE_ALBUM")
        val shelves = buildInternationalHomeShelves(
            listOf(
                YouTubeMusicHomeShelf("Listen", listOf(song, playlist)),
                YouTubeMusicHomeShelf("Listen", listOf(album, song)),
                YouTubeMusicHomeShelf("Related", listOf(album))
            )
        )
        assertEquals(3, shelves.map { it.key }.toSet().size)
        fun feed(kind: InternationalHomeKind?) = InternationalHomeFeed(shelves, InternationalHomeLayout(), kind).shelves
        assertEquals(shelves, feed(null))
        val tracks = feed(InternationalHomeKind.TRACK)
        assertEquals(shelves.take(2).map { it.key }, tracks.map { it.key })
        assertEquals(listOf(song, song), tracks.flatMap { it.entries }.map { it.item })
        assertEquals(listOf(shelves.first().key), feed(InternationalHomeKind.PLAYLIST).map { it.key })
        assertEquals(shelves.drop(1).map { it.key }, feed(InternationalHomeKind.ALBUM).map { it.key })
        assertEquals(emptyList<InternationalHomeShelf>(), feed(InternationalHomeKind.ARTIST))
    }

    @Test
    fun fillingAnEarlierShelfPreservesTheIdentityOfOtherShelvesWithTheSameTitle() {
        val song = YouTubeMusicHomeItem("Song", "", "", videoId = "track")
        val empty = YouTubeMusicHomeShelf("Picks", emptyList(), continuationKey = "a")
        val visible = empty.copy(items = listOf(song), continuationKey = "b")
        val initial = buildInternationalHomeShelves(listOf(empty, visible))
        val updated = buildInternationalHomeShelves(listOf(empty.copy(items = listOf(song)), visible))

        assertEquals(initial.single().key, updated.last().key)
        assertEquals(2, updated.map { it.key }.distinct().size)
    }

    @Test
    fun typedBrowseRoutesWinOverPreviewVideosAndPlayableTracksKeepPlaylistContext() {
        val song = YouTubeMusicHomeItem("Song", "", "", videoId = "song", browseId = "VLqueue")
        val album = song.copy(browseId = "VLalbum", pageType = "MUSIC_PAGE_TYPE_ALBUM")
        val artist = song.copy(browseId = "creator", pageType = "MUSIC_PAGE_TYPE_USER_CHANNEL")
        val playlist = song.copy(pageType = "MUSIC_PAGE_TYPE_PLAYLIST")
        val unknown = song.copy(browseId = "FEunsupported", videoId = "", pageType = "MUSIC_PAGE_TYPE_UNKNOWN")
        val result = buildInternationalHomeShelves(
            listOf(
                YouTubeMusicHomeShelf("Empty", emptyList()),
                YouTubeMusicHomeShelf("Unavailable", listOf(unknown)),
                YouTubeMusicHomeShelf("Routes", listOf(artist, unknown, album, playlist, song))
            )
        ).single()
        assertEquals("Routes", result.title)
        assertEquals(
            listOf(InternationalHomeKind.ARTIST, InternationalHomeKind.ALBUM, InternationalHomeKind.PLAYLIST, InternationalHomeKind.TRACK),
            result.entries.map { it.kind }
        )
        assertEquals("song", result.songs.single().audioId)
        assertNull(unknown.internationalHomeKind())
    }
}
