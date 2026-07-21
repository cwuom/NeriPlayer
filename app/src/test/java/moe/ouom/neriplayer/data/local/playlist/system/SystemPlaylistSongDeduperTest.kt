package moe.ouom.neriplayer.data.local.playlist.system

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemPlaylistSongDeduperTest {

    @Test
    fun `keeps first remote song when identity repeats`() {
        val first = remoteSong(id = 1L, name = "first")
        val duplicated = remoteSong(id = 1L, name = "duplicated")

        val distinct = listOf(first, duplicated).distinctSystemSongs()

        assertEquals(listOf(first), distinct)
    }

    @Test
    fun `deduplicates matching songs across source playlist batches`() {
        val first = remoteSong(id = 1L, name = "first")
        val duplicated = remoteSong(id = 1L, name = "duplicated")
        val deduper = SystemPlaylistSongDeduper(expectedSongCount = 2)

        deduper.addAll(listOf(first))
        deduper.addAll(listOf(duplicated))

        assertEquals(listOf(first), deduper.songs())
    }

    @Test
    fun `keeps first local song when metadata fallback matches`() {
        val contentAlias = localSong(
            id = 1L,
            mediaUri = "content://media/external/audio/media/100"
        )
        val path = "/storage/emulated/0/Music/周杰伦 - 晴天.mp3"
        val pathAlias = localSong(
            id = 2L,
            mediaUri = path,
            localFilePath = path
        )

        val distinct = listOf(contentAlias, pathAlias).distinctSystemSongs()

        assertEquals(listOf(contentAlias), distinct)
    }

    @Test
    fun `deduplicates a large downloaded local collection while preserving first entries`() {
        val uniqueSongs = List(8_192) { index ->
            localSong(
                id = index.toLong(),
                mediaUri = "/storage/emulated/0/Music/download-$index.mp3",
                localFilePath = "/storage/emulated/0/Music/download-$index.mp3"
            ).copy(
                name = "download-$index",
                localFileName = "download-$index.mp3"
            )
        }
        val duplicateSongs = uniqueSongs.map { song -> song.copy(name = "duplicate-${song.id}") }

        val distinct = (uniqueSongs + duplicateSongs).distinctSystemSongs()

        assertEquals(uniqueSongs, distinct)
    }

    private fun remoteSong(
        id: Long,
        name: String
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = id.toString()
        )
    }

    private fun localSong(
        id: Long,
        mediaUri: String,
        localFilePath: String? = null
    ): SongItem {
        return SongItem(
            id = id,
            name = "晴天",
            artist = "周杰伦",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 269_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            localFileName = "周杰伦 - 晴天.mp3",
            localFilePath = localFilePath,
            channelId = "local",
            audioId = id.toString()
        )
    }
}
