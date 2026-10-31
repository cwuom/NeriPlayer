package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldConfirmLocalMetadataWriteBack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMetadataWriteBackConfirmationPolicyTest {

    @Test
    fun `asks before writing changed local metadata`() {
        val song = localSong().copy(customName = "Updated title")

        assertTrue(shouldConfirmLocalMetadataWriteBack(song))
    }

    @Test
    fun `asks before writing changed local cover`() {
        val song = localSong().copy(customCoverUrl = "file:///new-cover.jpg")

        assertTrue(shouldConfirmLocalMetadataWriteBack(song))
    }

    @Test
    fun `reopened local edits ask again for existing app metadata overrides`() {
        val song = localSong()
        val overrides = listOf(
            song.copy(customName = "App title"),
            song.copy(customArtist = "App artist"),
            song.copy(customCoverUrl = "file:///app-cover.jpg"),
            song.copy(matchedLyric = "App lyric", lyricSyncEdited = true),
            song.copy(matchedTranslatedLyric = "App translation", lyricSyncEdited = true),
            song.copy(matchedRomanizedLyric = "App romanization", lyricSyncEdited = true),
            song.copy(matchedLyric = "", lyricSyncEdited = true)
        )

        overrides.forEach { edited ->
            assertTrue(
                "existing app overrides must still offer file writeback: $edited",
                shouldConfirmLocalMetadataWriteBack(edited)
            )
        }
    }

    @Test
    fun `local saves ask for a destination even when scanned lyrics are unchanged`() {
        val song = localSong().copy(
            matchedLyric = "Embedded lyric",
            originalLyric = "Embedded lyric",
            matchedTranslatedLyric = "Embedded translation",
            originalTranslatedLyric = "Embedded translation",
            matchedRomanizedLyric = "Embedded romanization",
            originalRomanizedLyric = "Embedded romanization"
        )

        assertTrue(shouldConfirmLocalMetadataWriteBack(song))
    }

    @Test
    fun `local saves always ask while remote metadata saves continue in app`() {
        val localSong = localSong()
        val remoteSong = localSong.copy(mediaUri = null, localFilePath = null, channelId = "netease")

        assertTrue(shouldConfirmLocalMetadataWriteBack(localSong))
        assertFalse(shouldConfirmLocalMetadataWriteBack(remoteSong))
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
