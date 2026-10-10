package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalPlaylistCoverNormalizationTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `room primary local files playlists drop their custom cover`() {
        val repository = repository()
        val localFiles = LocalPlaylist(
            id = LocalFilesPlaylist.SYSTEM_ID,
            name = "Local Files",
            customCoverUrl = "content://covers/local-files.jpg"
        )
        val roadTrip = LocalPlaylist(id = 5L, name = "Road trip", customCoverUrl = "content://covers/road-trip.jpg")
        val clean = listOf(localFiles.copy(customCoverUrl = null), roadTrip)

        assertEquals(clean, repository.normalizeRoomPrimaryLocalFilesCover(listOf(localFiles, roadTrip)))
        assertSame(clean, repository.normalizeRoomPrimaryLocalFilesCover(clean))
    }

    @Test
    fun `local and custom cover paths are mapped to the network original they came from`() {
        val repository = repository()
        val song = localSong(1)

        repository.saveCoverMapping(song.copy(coverUrl = LOCAL_COVER, customCoverUrl = null, originalCoverUrl = NETWORK_COVER))
        repository.saveCoverMapping(song.copy(coverUrl = null, customCoverUrl = CUSTOM_COVER, originalCoverUrl = NETWORK_COVER))
        repository.saveCoverMapping(song.copy(coverUrl = UNMAPPED_COVER, customCoverUrl = UNMAPPED_COVER, originalCoverUrl = null))

        val mapper = CoverUrlMapper.getInstance(mockContext())
        assertEquals(NETWORK_COVER, mapper.getNetworkUrl(LOCAL_COVER))
        assertEquals(NETWORK_COVER, mapper.getNetworkUrl(CUSTOM_COVER))
        assertEquals(UNMAPPED_COVER, mapper.getNetworkUrl(UNMAPPED_COVER))
    }

    private fun repository() = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "cover_normalization.json"),
        storage = RecordingStorage(primary = null)
    )

    private companion object {
        const val NETWORK_COVER = "https://p1.music.126.net/night-drive.jpg"
        const val LOCAL_COVER = "/storage/emulated/0/Music/Night Drive.jpg"
        const val CUSTOM_COVER = "/data/user/0/moe.ouom.neriplayer/files/custom_song_covers/1.jpg"
        const val UNMAPPED_COVER = "/storage/emulated/0/Music/unmapped.jpg"
    }
}
