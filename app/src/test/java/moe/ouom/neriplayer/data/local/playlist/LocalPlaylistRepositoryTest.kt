package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class LocalPlaylistRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `concurrent prepared adds keep every distinct song`() = runTest {
        val playlistId = 42L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = playlistId, name = "并发歌单")))

        val songs = (1..40).map(::localSong)
        val addResults = songs.map { song ->
            async(Dispatchers.Default) {
                repository.addPreparedSongsToPlaylistAndCount(playlistId, listOf(song))
            }
        }.awaitAll()

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(songs.size, addResults.sum())
        assertEquals(
            songs.map { it.localFilePath }.toSet(),
            playlist.songs.map { it.localFilePath }.toSet()
        )
    }

    @Test
    fun `scanned adds skip local metadata duplicates in regular playlist`() = runTest {
        val playlistId = 43L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(listOf(LocalPlaylist(id = playlistId, name = "扫描歌单")))

        val contentAlias = scannedAliasSong(
            id = 1L,
            mediaUri = "content://media/external/audio/media/100"
        )
        val pathAlias = scannedAliasSong(
            id = 2L,
            mediaUri = File(tempFolder.root, "周杰伦 - 晴天.mp3").absolutePath,
            localFilePath = File(tempFolder.root, "周杰伦 - 晴天.mp3").absolutePath
        )

        val firstAdd = repository.addScannedSongsToPlaylistAndCount(playlistId, listOf(contentAlias))
        val secondAdd = repository.addScannedSongsToPlaylistAndCount(playlistId, listOf(pathAlias))
        val playlist = repository.playlists.value.single { it.id == playlistId }

        assertEquals(1, firstAdd)
        assertEquals(0, secondAdd)
        assertEquals(1, playlist.songs.size)
        assertEquals(contentAlias.mediaUri, playlist.songs.single().mediaUri)
        assertEquals(contentAlias.localFileName, playlist.songs.single().localFileName)
    }

    @Test
    fun `adding downloaded local copy to favorites keeps original favorite order`() = runTest {
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        val remoteSong = remoteNeteaseSong(addedAt = 11L)
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = "我喜欢的音乐",
                    songs = mutableListOf(remoteSong),
                    modifiedAt = 10L
                )
            )
        )

        repository.addToFavorites(downloadedLocalCopy(remoteSong))

        val favorites = repository.playlists.value.single()
        assertEquals(1, favorites.songs.size)
        assertEquals(remoteSong, favorites.songs.single())
        assertEquals(11L, favorites.songs.single().addedAt)
    }

    private fun mockContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        return context
    }

    private fun localSong(index: Int): SongItem {
        val path = File(tempFolder.root, "song-$index.mp3").absolutePath
        return SongItem(
            id = index.toLong(),
            name = "song-$index",
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1000L + index,
            coverUrl = null,
            mediaUri = path,
            localFilePath = path
        )
    }

    private fun scannedAliasSong(
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

    private fun remoteNeteaseSong(addedAt: Long = 0L): SongItem {
        return SongItem(
            id = 42L,
            name = "song",
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42",
            addedAt = addedAt
        )
    }

    private fun downloadedLocalCopy(source: SongItem): SongItem {
        val path = File(tempFolder.root, "song.mp3").absolutePath
        return SongItem(
            id = 99L,
            name = source.name,
            artist = source.artist,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = source.durationMs,
            coverUrl = null,
            mediaUri = path,
            localFileName = "song.mp3",
            localFilePath = path,
            channelId = "local",
            audioId = "99",
            sourceStableKey = source.stableKey()
        )
    }
}
