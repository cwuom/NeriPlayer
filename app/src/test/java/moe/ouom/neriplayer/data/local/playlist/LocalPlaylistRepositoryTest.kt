package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
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

    @Test
    fun `legacy playlist migration preserves previous display order and rewrites added time`() = runTest {
        val playlistId = 44L
        val storageFile = File(tempFolder.root, "legacy_local_playlists.json")
        storageFile.writeText(
            """
            [
              {
                "id": $playlistId,
                "name": "旧歌单",
                "songs": [
                  ${songJson(1L, "oldest", 11L)},
                  ${songJson(2L, "middle", 22L)},
                  ${songJson(3L, "newest", 33L)}
                ],
                "modifiedAt": 1000
              }
            ]
            """.trimIndent()
        )

        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = storageFile,
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, playlist.songOrderVersion)
        assertEquals(listOf("newest", "middle", "oldest"), playlist.songs.map { it.name })
        assertEquals(
            playlist.songs.map { it.addedAt },
            playlist.songs.map { it.addedAt }.sortedDescending()
        )
    }

    @Test
    fun `adding songs to regular playlist places newest songs first`() = runTest {
        val playlistId = 45L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "普通歌单",
                    songs = mutableListOf(localSong(index = 1, name = "old")),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.addPreparedSongsToPlaylistAndCount(
            playlistId,
            listOf(
                localSong(index = 2, name = "new-a"),
                localSong(index = 3, name = "new-b")
            )
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(listOf("new-a", "new-b", "old"), playlist.songs.map { it.name })
        assertEquals(
            playlist.songs.take(2).map { it.addedAt },
            playlist.songs.take(2).map { it.addedAt }.sortedDescending()
        )
    }

    @Test
    fun `adding new favorite places it before existing favorites`() = runTest {
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = FavoritesPlaylist.SYSTEM_ID,
                    name = "我喜欢的音乐",
                    songs = mutableListOf(localSong(index = 1, name = "old")),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.addToFavorites(localSong(index = 2, name = "new"))

        val favorites = repository.playlists.value.single()
        assertEquals(listOf("new", "old"), favorites.songs.map { it.name })
    }

    @Test
    fun `manual reorder keeps user order after adding another song`() = runTest {
        val playlistId = 46L
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "local_playlists.json"),
            normalizePlaylists = { it },
            autoSyncEnabled = false
        )
        val first = localSong(index = 1, name = "first")
        val second = localSong(index = 2, name = "second")
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = playlistId,
                    name = "手动排序",
                    songs = mutableListOf(first, second),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )

        repository.reorderSongs(playlistId, listOf(second.identity(), first.identity()))
        repository.addPreparedSongsToPlaylistAndCount(
            playlistId,
            listOf(localSong(index = 3, name = "new"))
        )

        val playlist = repository.playlists.value.single { it.id == playlistId }
        assertEquals(listOf("new", "second", "first"), playlist.songs.map { it.name })
    }

    private fun mockContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        return context
    }

    private fun localSong(index: Int, name: String = "song-$index"): SongItem {
        val path = File(tempFolder.root, "song-$index.mp3").absolutePath
        return SongItem(
            id = index.toLong(),
            name = name,
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

    private fun remoteNeteaseSong(
        id: Long = 42L,
        name: String = "song",
        addedAt: Long = 0L
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
            audioId = id.toString(),
            addedAt = addedAt
        )
    }

    private fun songJson(id: Long, name: String, addedAt: Long): String {
        return """
            {
              "id": $id,
              "name": "$name",
              "artist": "artist",
              "album": "NeteaseAlbum",
              "albumId": 7,
              "durationMs": 1000,
              "coverUrl": null,
              "channelId": "netease",
              "audioId": "$id",
              "addedAt": $addedAt
            }
        """.trimIndent()
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
