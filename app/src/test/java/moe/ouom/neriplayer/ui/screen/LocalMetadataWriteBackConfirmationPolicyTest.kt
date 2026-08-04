package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMetadataWriteBackConfirmationPolicyTest {

    @Test
    fun `asks before writing changed local metadata`() {
        val song = localSong()

        assertTrue(
            shouldConfirmLocalMetadataWriteBack(
                song = song,
                title = "Updated title",
                artist = song.artist,
                coverUrl = song.coverUrl.orEmpty()
            )
        )
    }

    @Test
    fun `asks before writing changed local cover`() {
        val song = localSong().copy(coverUrl = "file:///old-cover.jpg")

        assertTrue(
            shouldConfirmLocalMetadataWriteBack(
                song = song,
                title = song.name,
                artist = song.artist,
                coverUrl = "file:///new-cover.jpg"
            )
        )
    }

    @Test
    fun `does not ask for unchanged or remote song metadata`() {
        val localSong = localSong()
        val remoteSong = localSong.copy(mediaUri = null, localFilePath = null, channelId = "netease")

        assertFalse(
            shouldConfirmLocalMetadataWriteBack(
                song = localSong,
                title = localSong.name,
                artist = localSong.artist,
                coverUrl = localSong.coverUrl.orEmpty()
            )
        )
        assertFalse(
            shouldConfirmLocalMetadataWriteBack(
                song = remoteSong,
                title = "Updated title",
                artist = remoteSong.artist,
                coverUrl = remoteSong.coverUrl.orEmpty()
            )
        )
    }

    private fun localSong(): SongItem {
        return SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/1",
            channelId = "local",
            audioId = "1"
        )
    }
}
