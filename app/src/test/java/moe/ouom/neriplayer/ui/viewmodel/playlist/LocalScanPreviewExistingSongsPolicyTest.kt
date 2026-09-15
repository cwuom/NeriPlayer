package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalScanPreviewExistingSongsPolicyTest {

    @Test
    fun `marks local songs from any local playlist`() {
        val existing = localSong(
            id = 1L,
            mediaUri = "content://media/external/audio/media/100",
            fileName = "existing.mp3"
        )
        val matchingScanResult = localSong(
            id = 2L,
            mediaUri = "content://media/external/audio/media/100",
            fileName = "existing.mp3"
        )
        val newScanResult = localSong(
            id = 3L,
            mediaUri = "content://media/external/audio/media/101",
            fileName = "new.mp3"
        )

        val existingKeys = scannedSongKeysAlreadyInLocalPlaylists(
            scannedSongs = listOf(matchingScanResult, newScanResult),
            localPlaylists = listOf(
                LocalPlaylist(id = 77L, name = "导出的本地歌单", songs = mutableListOf(existing))
            )
        )

        assertEquals(setOf(matchingScanResult.stableKey()), existingKeys)
    }

    @Test
    fun `does not mark a different source with matching metadata as existing`() {
        val path = File("/music/Artist - Existing.mp3").absolutePath
        val existing = localSong(
            id = 1L,
            mediaUri = path,
            localFilePath = path,
            fileName = "Artist - Existing.mp3",
            name = "Existing",
            artist = "Artist"
        )
        val matchingScanResult = localSong(
            id = 2L,
            mediaUri = "content://media/external/audio/media/100",
            fileName = "Artist - Existing.mp3",
            name = "Existing",
            artist = "Artist"
        )
        val newScanResult = localSong(
            id = 3L,
            mediaUri = "content://media/external/audio/media/101",
            fileName = "Artist - New.mp3",
            name = "New",
            artist = "Artist"
        )

        val existingKeys = scannedSongKeysAlreadyInLocalPlaylists(
            scannedSongs = listOf(matchingScanResult, newScanResult),
            localPlaylists = listOf(
                LocalPlaylist(id = 78L, name = "别的本地歌单", songs = mutableListOf(existing))
            )
        )

        assertTrue(existingKeys.isEmpty())
    }

    @Test
    fun `marks downloaded song rescanned as local from any playlist`() {
        val path = File("/music/downloaded.mp3").absolutePath
        val onlineSource = SongItem(
            id = 42L,
            name = "Online",
            artist = "Artist",
            album = "Online album",
            albumId = 7L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val downloadedSong = localSong(
            id = 1L,
            mediaUri = path,
            localFilePath = path,
            fileName = "downloaded.mp3",
            name = "Online",
            sourceStableKey = onlineSource.stableKey()
        )
        val scannedSong = localSong(
            id = 2L,
            mediaUri = path,
            localFilePath = path,
            fileName = "downloaded.mp3",
            name = "Online"
        )

        val existingKeys = scannedSongKeysAlreadyInLocalPlaylists(
            scannedSongs = listOf(scannedSong),
            localPlaylists = listOf(
                LocalPlaylist(id = 79L, name = "下载歌曲", songs = mutableListOf(downloadedSong))
            )
        )

        assertEquals(setOf(scannedSong.stableKey()), existingKeys)
    }

    @Test
    fun `marks scanned online derived local song from another playlist`() {
        val path = File("/music/shared.mp3").absolutePath
        val existingPureLocalSong = localSong(
            id = 1L,
            mediaUri = path,
            localFilePath = path,
            fileName = "shared.mp3",
            name = "Shared"
        )
        val onlineSource = SongItem(
            id = 43L,
            name = "Shared",
            artist = "Artist",
            album = "Online album",
            albumId = 8L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = "bili",
            audioId = "43"
        )
        val onlineDerivedScanResult = localSong(
            id = 2L,
            mediaUri = path,
            localFilePath = path,
            fileName = "shared.mp3",
            name = "Shared",
            sourceStableKey = onlineSource.stableKey()
        )

        val existingKeys = scannedSongKeysAlreadyInLocalPlaylists(
            scannedSongs = listOf(onlineDerivedScanResult),
            localPlaylists = listOf(
                LocalPlaylist(
                    id = 80L,
                    name = "纯本地歌曲",
                    songs = mutableListOf(existingPureLocalSong)
                )
            )
        )

        assertEquals(setOf(onlineDerivedScanResult.stableKey()), existingKeys)
    }

    @Test
    fun `does not mark remote playlist export as an added local song`() {
        val path = File("/music/exported-online.mp3").absolutePath
        val onlineSource = SongItem(
            id = 44L,
            name = "Exported online",
            artist = "Artist",
            album = "Online album",
            albumId = 9L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = "youtube",
            audioId = "44"
        )
        val scannedSong = localSong(
            id = 2L,
            mediaUri = path,
            localFilePath = path,
            fileName = "exported-online.mp3",
            name = "Exported online",
            sourceStableKey = onlineSource.stableKey()
        )

        val existingKeys = scannedSongKeysAlreadyInLocalPlaylists(
            scannedSongs = listOf(scannedSong),
            localPlaylists = listOf(
                LocalPlaylist(
                    id = 81L,
                    name = "远端导出歌单",
                    songs = mutableListOf(onlineSource)
                )
            )
        )

        assertEquals(emptySet<String>(), existingKeys)
    }

    private fun localSong(
        id: Long,
        mediaUri: String,
        fileName: String,
        name: String = fileName.substringBeforeLast('.'),
        artist: String = "Artist",
        localFilePath: String? = null,
        sourceStableKey: String? = null
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            localFilePath = localFilePath,
            localFileName = fileName,
            sourceStableKey = sourceStableKey
        )
    }
}
